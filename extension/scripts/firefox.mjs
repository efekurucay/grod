// Firefox runs extension backgrounds as event pages (background.scripts), Chrome only as a service worker,
// and each browser flags the other's key. manifest.json is the Chrome one; this writes the Firefox copy.
import { cpSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const root = new URL('..', import.meta.url).pathname;
const out = join(root, 'dist/firefox');
const files = ['background.js', 'content.js', 'blocked.html', 'blocked.js', 'pause.html', 'pause.js', 'popup.html', 'popup.js', 'rules.json', 'lib', 'icons', 'ui'];

rmSync(out, { recursive: true, force: true });
for (const f of files) cpSync(join(root, f), join(out, f), { recursive: true });
const manifest = JSON.parse(readFileSync(join(root, 'manifest.json'), 'utf8'));
manifest.background = { scripts: ['lib/rules-core.js', 'background.js'] };
writeFileSync(join(out, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n');
console.log(`Firefox build: ${out}/manifest.json`);
