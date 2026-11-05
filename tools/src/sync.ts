import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { load, repoRoot, revision, serialize, writeAtomic } from './rules.ts';

export const ANDROID_PACKAGE = 'dev.grod';
/** App-specific external dir: adb can write it and the app reads it without any storage permission. */
export const ANDROID_RULES_PATH = `/sdcard/Android/data/${ANDROID_PACKAGE}/files/rules.json`;

/** Copies baked into each platform build. They carry the revision so devices can report what they run. */
const bundledCopies = ['extension/rules.json', 'android/app/src/main/assets/rules.json'];

export type SyncReport = { revision: string; wrote: string[]; adb: string };

export function sync({ adb = true } = {}): SyncReport {
  const file = load();
  const rev = revision();
  const compiled = serialize({ ...file, revision: rev } as typeof file);
  const wrote: string[] = [];
  for (const rel of bundledCopies) {
    const path = join(repoRoot, rel);
    // Only write into platform folders that exist; a missing folder means that platform is not checked out.
    if (!existsSync(join(repoRoot, rel.split('/')[0]))) continue;
    mkdirSync(dirname(path), { recursive: true });
    writeAtomic(path, compiled);
    wrote.push(relative(repoRoot, path));
  }
  return { revision: rev, wrote, adb: adb ? pushToPhone(compiled) : 'skipped' };
}

function pushToPhone(compiled: string): string {
  let devices: string[];
  try {
    devices = execFileSync('adb', ['devices'], { encoding: 'utf8' })
      .split('\n')
      .slice(1)
      .filter((l) => l.trim().endsWith('\tdevice'))
      .map((l) => l.split('\t')[0]);
  } catch {
    return 'adb not found';
  }
  if (devices.length === 0) return 'no phone connected';
  const tmp = join(repoRoot, 'android/.rules-push.json');
  mkdirSync(dirname(tmp), { recursive: true });
  writeAtomic(tmp, compiled);
  const results = devices.map((serial) => {
    try {
      execFileSync('adb', ['-s', serial, 'shell', 'mkdir', '-p', dirname(ANDROID_RULES_PATH)]);
      execFileSync('adb', ['-s', serial, 'push', tmp, ANDROID_RULES_PATH], { stdio: 'pipe' });
      return `${serial}: pushed`;
    } catch (e) {
      return `${serial}: push failed (${(e as Error).message.split('\n')[0]})`;
    }
  });
  return results.join(', ');
}
