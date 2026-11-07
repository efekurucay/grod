import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

// Same classic script the browser loads; running it in this realm keeps assert's deep equality simple.
vm.runInThisContext(readFileSync(new URL('../lib/rules-core.js', import.meta.url), 'utf8'));
const core = globalThis.grodCore;
const file = JSON.parse(readFileSync(new URL('../../rules/grod.rules.json', import.meta.url), 'utf8'));

const forHost = (host, f = file) => core.rulesForHost(f, host);
const decide = (host, target) => core.decide(forHost(host), target);

test('accepts only schemaVersion 1 files with a rules array', () => {
  assert.equal(core.isValidRulesFile(file), true);
  assert.equal(core.isValidRulesFile({ ...file, schemaVersion: 2 }), false);
  assert.equal(core.isValidRulesFile({ schemaVersion: 1 }), false);
  assert.equal(core.isValidRulesFile(null), false);
  assert.equal(core.isValidRulesFile('rules'), false);
});

test('host matches itself and subdomains only', () => {
  assert.deepEqual(forHost('youtube.com').map((r) => r.id), ['youtube']);
  assert.deepEqual(forHost('m.youtube.com').map((r) => r.id), ['youtube']);
  assert.deepEqual(forHost('www.youtube.com').map((r) => r.id), ['youtube']);
  assert.deepEqual(forHost('notyoutube.com'), []);
  assert.deepEqual(forHost('youtube.com.example.org'), []);
});

test('rules without a web section never match', () => {
  assert.equal(forHost('snapchat.com').length, 0);
});

test('YouTube Shorts URL redirects to the normal player', () => {
  const d = decide('www.youtube.com', '/shorts/abcDEF12345?si=x');
  assert.equal(d.action, 'redirect');
  assert.equal(d.rule.id, 'youtube');
  assert.equal(d.target, '/watch?v=abcDEF12345');
  assert.equal(decide('www.youtube.com', '/watch?v=abcDEF12345'), null);
});

test('Instagram Reels feed is blocked, single reels and posts stay', () => {
  assert.equal(decide('www.instagram.com', '/reels/').action, 'block');
  assert.equal(decide('www.instagram.com', '/reels/ABC123/').action, 'block');
  assert.equal(decide('www.instagram.com', '/reels?x=1').action, 'block');
  assert.equal(decide('www.instagram.com', '/reel/ABC/'), null);
  assert.equal(decide('www.instagram.com', '/p/ABC/'), null);
  assert.equal(decide('www.instagram.com', '/'), null);
});

test('TikTok is blocked on every path', () => {
  for (const target of ['/', '/@someone/video/123', '/foryou?lang=en']) {
    assert.equal(decide('www.tiktok.com', target).rule.id, 'tiktok');
  }
});

test('disabled rules are ignored', () => {
  const off = structuredClone(file);
  off.rules.find((r) => r.id === 'youtube').enabled = false;
  assert.deepEqual(forHost('www.youtube.com', off), []);
});

test('redirect runs before block, and a no-op redirect falls through to block', () => {
  const rules = [
    { id: 'a', enabled: true, web: { hosts: ['x.com'], block: ['^/'] } },
    { id: 'b', enabled: true, web: { hosts: ['x.com'], redirect: [{ from: '^/same', to: '/same' }, { from: '^/old', to: '/new' }] } },
  ];
  assert.deepEqual(core.decide(rules, '/old/1'), { action: 'redirect', rule: rules[1], target: '/new/1' });
  assert.equal(core.decide(rules, '/same').action, 'block');
});

test('an invalid regex is skipped without breaking other rules', () => {
  const rules = [
    { id: 'bad', enabled: true, web: { hosts: ['x.com'], block: ['('], redirect: [{ from: '[', to: '/' }] } },
    { id: 'good', enabled: true, web: { hosts: ['x.com'], block: ['^/feed'] } },
  ];
  assert.equal(core.decide(rules, '/feed').rule.id, 'good');
  assert.equal(core.decide(rules, '/other'), null);
});

const settings = core.scrollSettings(file);
const S = 1000;

// Scrolls steadily every 250 ms from `start` for `seconds`, all on one rule; returns the last result.
function scrollFor(session, start, seconds, rule = 'a') {
  let result = { session, pause: null };
  for (let t = start; t <= start + seconds * S; t += 250) {
    result = core.scrollEvent(result.session, settings, t, rule);
    if (result.started) return result;
  }
  return result;
}

test('scroll settings take the file values and fall back to the defaults', () => {
  assert.deepEqual(core.scrollSettings(undefined), core.scrollDefaults);
  assert.deepEqual(core.scrollSettings({ scroll: { freeSeconds: 20, growth: 'x', maxPauseSeconds: -1 } }), {
    ...core.scrollDefaults,
    freeSeconds: 20,
  });
  assert.deepEqual(settings, { freeSeconds: 60, firstPauseSeconds: 10, growth: 2, maxPauseSeconds: 300, resetMinutes: 15 });
});

test('pauses double from firstPauseSeconds and stop at maxPauseSeconds', () => {
  assert.deepEqual([0, 1, 2, 3, 4, 5, 6].map((n) => core.pauseSeconds(settings, n)), [10, 20, 40, 80, 160, 300, 300]);
});

test('the first scroll event of a session adds nothing', () => {
  const { session, pause } = core.scrollEvent(null, settings, 1000, 'a');
  assert.deepEqual(session, { scrolled: 0, total: 0, pauses: 0, last: 1000, pause: null });
  assert.equal(pause, null);
});

test('steady scrolling reaches freeSeconds and starts the first pause', () => {
  const { session, pause, started } = scrollFor(null, 0, 120);
  assert.equal(started, true);
  assert.deepEqual(pause, { rule: 'a', until: 60 * S + 10 * S, total: 60 * S });
  assert.deepEqual(session, { scrolled: 0, total: 60 * S, pauses: 1, last: 60 * S, pause });
});

test('a gap between scroll events adds at most 5 s', () => {
  let { session } = core.scrollEvent(null, settings, 0, 'a');
  session = core.scrollEvent(session, settings, 2 * S, 'a').session;
  assert.equal(session.scrolled, 2 * S);
  session = core.scrollEvent(session, settings, 2 * S + 4 * 60 * S, 'a').session;
  assert.equal(session.scrolled, 7 * S);
  assert.equal(session.total, 7 * S);
});

test('scroll events during a pause are ignored and return the same pause', () => {
  const first = scrollFor(null, 0, 120);
  const during = core.scrollEvent(first.session, settings, first.pause.until - 1, 'b');
  assert.equal(during.started, false);
  assert.equal(during.session, first.session);
  assert.equal(during.pause, first.pause);
  // Right after it ends the gap since the last counted event is capped, then counting resumes.
  const after = core.scrollEvent(first.session, settings, first.pause.until, 'a');
  assert.equal(after.pause, null);
  assert.equal(after.session.scrolled, 5 * S);
});

test('each pause in a session is longer, capped at maxPauseSeconds', () => {
  let result = { session: null };
  let t = 0;
  const lengths = [];
  for (let i = 0; i < 7; i++) {
    result = scrollFor(result.session, t, 120);
    lengths.push((result.pause.until - result.session.last) / S);
    t = result.pause.until;
  }
  assert.deepEqual(lengths, [10, 20, 40, 80, 160, 300, 300]);
  assert.equal(result.session.pauses, 7);
});

test('resetMinutes without scrolling starts a new session', () => {
  const first = scrollFor(null, 0, 120);
  const late = first.session.last + 15 * 60 * S;
  const { session } = core.scrollEvent(first.session, settings, late, 'a');
  assert.deepEqual(session, { scrolled: 0, total: 0, pauses: 0, last: late, pause: null });
  const next = scrollFor(session, late, 120);
  assert.equal(next.pause.until - next.session.last, 10 * S);
  const almost = core.scrollEvent(first.session, settings, late - 1, 'a').session;
  assert.equal(almost.pauses, 1);
});

test('the pause goes to the rule whose host was scrolled last, with the session total', () => {
  let { session } = scrollFor(null, 0, 50, 'youtube');
  ({ session } = core.scrollEvent(session, settings, session.last + 4 * S, 'instagram'));
  const result = scrollFor(session, session.last + 250, 20, 'instagram');
  assert.equal(result.started, true);
  assert.equal(result.pause.rule, 'instagram');
  assert.equal(result.pause.total, 60 * S);
});
