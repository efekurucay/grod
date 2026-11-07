import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import vm from 'node:vm';
import { execFileSync } from 'node:child_process';

const root = new URL('../', import.meta.url);
const read = (path) => JSON.parse(readFileSync(new URL(path, root), 'utf8'));

test('every file the manifest references exists', () => {
  const manifest = read('manifest.json');
  const files = [
    manifest.background.service_worker,
    ...manifest.content_scripts.flatMap((c) => c.js),
    manifest.action.default_popup,
    'blocked.html',
    'blocked.js',
    'popup.js',
    ...manifest.web_accessible_resources.flatMap((w) => w.resources),
    'pause.js',
  ];
  for (const file of files) assert.ok(existsSync(new URL(file, root)), file);
});

test('Chrome manifest has no Firefox-only background key', () => {
  assert.deepEqual(read('manifest.json').background, { service_worker: 'background.js' });
});

test('Firefox build references only files it contains', () => {
  execFileSync(process.execPath, [new URL('scripts/firefox.mjs', root).pathname]);
  const dist = new URL('dist/firefox/', root);
  const manifest = JSON.parse(readFileSync(new URL('manifest.json', dist), 'utf8'));
  assert.equal(manifest.background.service_worker, undefined);
  const files = [
    ...manifest.background.scripts,
    ...manifest.content_scripts.flatMap((c) => c.js),
    ...manifest.web_accessible_resources.flatMap((w) => w.resources),
    manifest.action.default_popup,
    'pause.js',
  ];
  for (const file of files) assert.ok(existsSync(new URL(file, dist)), file);
});

test('bundled rules.json is a valid rules copy', () => {
  vm.runInThisContext(readFileSync(new URL('lib/rules-core.js', root), 'utf8'));
  const bundled = read('rules.json');
  assert.equal(globalThis.grodCore.isValidRulesFile(bundled), true);
  assert.equal(typeof bundled.revision, 'string');
});
