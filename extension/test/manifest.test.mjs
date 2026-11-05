import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import vm from 'node:vm';

const root = new URL('../', import.meta.url);
const read = (path) => JSON.parse(readFileSync(new URL(path, root), 'utf8'));

test('every file the manifest references exists', () => {
  const manifest = read('manifest.json');
  const files = [
    manifest.background.service_worker,
    ...manifest.background.scripts,
    ...manifest.content_scripts.flatMap((c) => c.js),
    manifest.action.default_popup,
    'blocked.html',
    'blocked.js',
    'popup.js',
  ];
  for (const file of files) assert.ok(existsSync(new URL(file, root)), file);
});

test('bundled rules.json is a valid rules copy', () => {
  vm.runInThisContext(readFileSync(new URL('lib/rules-core.js', root), 'utf8'));
  const bundled = read('rules.json');
  assert.equal(globalThis.grodCore.isValidRulesFile(bundled), true);
  assert.equal(typeof bundled.revision, 'string');
});
