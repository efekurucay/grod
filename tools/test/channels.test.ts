import { strict as assert } from 'node:assert';
import { test } from 'node:test';
import { addChannel, classify, listChannels, normalizeHandle, parseChannelPage, removeChannel, sameHandle } from '../src/channels.ts';
import type { RulesFile } from '../src/rules.ts';

test('handles compare equal whether percent-encoded or not', () => {
  assert.equal(normalizeHandle('@%C3%87inMal%C4%B1CemY%C4%B1lmaz'), '@ÇinMalıCemYılmaz');
  assert.equal(normalizeHandle('cmylmz'), '@cmylmz');
  assert.ok(sameHandle('@CMYLMZ', '@cmylmz'));
  assert.equal(normalizeHandle('@C\u0327inMal\u0131'), '@ÇinMalı');
});

test('classify reads every link shape people paste', () => {
  assert.deepEqual(classify('https://youtu.be/aqz-KE-bpKQ?si=x'), { kind: 'video', url: 'https://www.youtube.com/watch?v=aqz-KE-bpKQ' });
  assert.deepEqual(classify('https://m.youtube.com/watch?v=abc&t=3'), { kind: 'video', url: 'https://www.youtube.com/watch?v=abc' });
  assert.deepEqual(classify('youtube.com/shorts/xyz?si=1'), { kind: 'video', url: 'https://www.youtube.com/shorts/xyz' });
  assert.deepEqual(classify('@cmylmz'), { kind: 'channel', url: 'https://www.youtube.com/@cmylmz' });
  assert.deepEqual(classify('https://www.youtube.com/@cmylmz/videos'), { kind: 'channel', url: 'https://www.youtube.com/@cmylmz' });
  assert.throws(() => classify('https://vimeo.com/1'), /not a YouTube link/);
});

test('parseChannelPage takes name, handle and id from the page', () => {
  const html = `<meta property="og:title" content="Tom &amp; Jerry"><link rel="canonical" href="https://www.youtube.com/channel/UCBfQnAjHnWyFRmUQ4Rf8CGw">"vanityChannelUrl":"http://www.youtube.com/@%C3%87inMal%C4%B1"`;
  assert.deepEqual(parseChannelPage(html), { name: 'Tom & Jerry', handle: '@ÇinMalı', id: 'UCBfQnAjHnWyFRmUQ4Rf8CGw' });
  assert.throws(() => parseChannelPage('<html></html>'), /name and handle/);
});

test('add creates the rule once, dedupes handles, remove works by handle or name', () => {
  const file: RulesFile = { schemaVersion: 1, rules: [] };
  assert.equal(addChannel(file, { name: 'Cem Yılmaz', handle: '@cmylmz' }), true);
  assert.equal(addChannel(file, { name: 'dup', handle: '@CMYLMZ' }), false);
  assert.equal(addChannel(file, { name: 'Çin Malı', handle: '@%C3%87inMal%C4%B1' }), true);
  assert.equal(file.rules.length, 1);
  assert.deepEqual(listChannels(file).map((c) => c.handle), ['@cmylmz', '@ÇinMalı']);
  assert.equal(removeChannel(file, 'çin malı').length, 1);
  assert.equal(removeChannel(file, '@cmylmz').length, 1);
  assert.equal(removeChannel(file, '@nobody').length, 0);
});
