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

const cem = { name: 'Çin Malı Cem Yılmaz', handle: '@ÇinMalıCemYılmaz' };
const CEM_HREF = '/@%C3%87inMal%C4%B1CemY%C4%B1lmaz';
const channelFile = {
  schemaVersion: 1,
  rules: [
    { id: 'yt-channels', title: 'Channels', enabled: true, youtubeChannels: [cem, { name: 'X', handle: '@xchan', id: 'UC1234567890123456789012' }] },
    { id: 'off', title: 'Off', enabled: false, youtubeChannels: [{ name: 'Off', handle: '@offchan' }] },
  ],
};

test('channel lists merge from enabled rules plus pending, one entry per handle', () => {
  const pending = [{ name: 'Dup', handle: '@%C3%A7inmal%C4%B1cemy%C4%B1lmaz' }, { name: 'New', handle: '@new' }, { handle: 'bad' }];
  const channels = core.youtubeChannels(channelFile, pending);
  assert.deepEqual(channels.map((c) => [c.handle, c.rule.id]), [
    ['@ÇinMalıCemYılmaz', 'yt-channels'],
    ['@xchan', 'yt-channels'],
    ['@new', core.pendingRule.id],
  ]);
  assert.deepEqual(core.youtubeChannels(undefined, []), []);
});

test('handles compare case-insensitively after percent-decoding', () => {
  assert.equal(core.handleKey(CEM_HREF.slice(1)), '@çinmalıcemyılmaz');
  assert.equal(core.handleKey(CEM_HREF.slice(1)), core.handleKey(cem.handle));
  assert.equal(core.handleKey('@MrBeast'), core.handleKey('@mrbeast'));
  assert.equal(core.handleKey('@bad%E0'), '@bad%e0');
  assert.equal(core.handleKey(cem.handle.normalize('NFD')), core.handleKey(cem.handle));
});

test('channel pages and anything below them are blocked with the owning rule', () => {
  const channels = core.youtubeChannels(channelFile);
  for (const path of [CEM_HREF, `${CEM_HREF}/videos`, '/@çinmalıcemyılmaz', '/@ÇinMalıCemYılmaz/shorts', '/channel/UC1234567890123456789012/about']) {
    assert.equal(core.channelForPath(channels, path)?.rule.id, 'yt-channels', path);
  }
  for (const path of ['/@offchan', '/@ÇinMalıCemYılmazz', '/watch', '/', '/channel/UCother', '/results']) {
    assert.equal(core.channelForPath(channels, path), null, path);
  }
});

test('channel selectors cover every tile type with raw and encoded handles and the channel id', () => {
  const selectors = core.channelSelectors(core.youtubeChannels(channelFile));
  assert.equal(selectors.length, core.channelTiles.length);
  assert.ok(core.channelTiles.includes('ytd-video-renderer') && core.channelTiles.includes('ytm-video-with-context-renderer'));
  const video = selectors.find((s) => s.startsWith('ytd-video-renderer:has('));
  assert.ok(video.includes(`a[href="${CEM_HREF}" i]`));
  assert.ok(video.includes(`a[href^="${CEM_HREF}/" i]`));
  assert.ok(video.includes('a[href="/@ÇinMalıCemYılmaz" i]'));
  assert.ok(video.includes('a[href="/channel/UC1234567890123456789012"]'));
  assert.ok(!video.includes('offchan'));
  assert.deepEqual(core.channelSelectors([]), []);
  const decomposed = core.channelSelectors([{ ...cem, handle: cem.handle.normalize('NFD') }])[1];
  assert.ok(decomposed.includes(`a[href="${CEM_HREF}" i]`), decomposed);
});

test('quotes in a handle cannot break out of the selector string', () => {
  const [selector] = core.channelSelectors([{ name: 'Q', handle: '@a"b\\c' }]);
  assert.ok(selector.includes('a[href="/@a\\"b\\\\c" i]'), selector);
});

test('a tile yields its channel: handle, shortest link text as name, and id', () => {
  const links = [
    { href: '/watch?v=abc', text: 'Some video title' },
    { href: CEM_HREF, text: '' },
    { href: CEM_HREF, text: '\n  Çin Malı Cem Yılmaz\n ' },
    { href: 'https://www.youtube.com/channel/UC1234567890123456789012', text: '' },
    { href: '/@someoneelse', text: 'Other' },
  ];
  assert.deepEqual(core.channelFromLinks(links), { ...cem, id: 'UC1234567890123456789012' });
  assert.deepEqual(core.channelFromLinks([{ href: '/@solo', text: '' }]), { name: 'solo', handle: '@solo' });
  assert.equal(core.channelFromLinks([{ href: '/watch?v=1', text: 'x' }]), null);
});
