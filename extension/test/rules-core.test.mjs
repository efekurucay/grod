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
  assert.deepEqual(forHost('youtube.com').map((r) => r.id), ['youtube-shorts']);
  assert.deepEqual(forHost('m.youtube.com').map((r) => r.id), ['youtube-shorts']);
  assert.deepEqual(forHost('www.youtube.com').map((r) => r.id), ['youtube-shorts']);
  assert.deepEqual(forHost('notyoutube.com'), []);
  assert.deepEqual(forHost('youtube.com.example.org'), []);
});

test('rules without a web section never match', () => {
  assert.equal(forHost('snapchat.com').length, 0);
});

test('YouTube Shorts URL redirects to the normal player', () => {
  const d = decide('www.youtube.com', '/shorts/abcDEF12345?si=x');
  assert.equal(d.action, 'redirect');
  assert.equal(d.rule.id, 'youtube-shorts');
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
  off.rules.find((r) => r.id === 'youtube-shorts').enabled = false;
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

test('invalid selectors are skipped and each valid one gets its own CSS rule', () => {
  const rules = [
    { id: 'a', enabled: true, web: { hosts: ['x.com'], hide: ['a[href="/reels/"]', 'div:::bad', 'nav'] } },
    { id: 'b', enabled: true, web: { hosts: ['x.com'], hide: ['nav'] } },
  ];
  const check = (selector) => {
    if (selector.includes(':::')) throw new SyntaxError(selector);
  };
  const selectors = core.hideSelectors(rules, check);
  assert.deepEqual(selectors, ['a[href="/reels/"]', 'nav']);
  assert.equal(
    core.css(selectors),
    'a[href="/reels/"] { display: none !important; }\nnav { display: none !important; }',
  );
});
