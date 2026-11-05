import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { addChannel, normalizeHandle } from './channels.ts';
import { load, repoRoot, revision, save, serialize, writeAtomic, type Channel } from './rules.ts';

export const ANDROID_PACKAGE = 'dev.grod';
/** App-specific external dir: adb can write it and the app reads it without any storage permission. */
export const ANDROID_RULES_PATH = `/sdcard/Android/data/${ANDROID_PACKAGE}/files/rules.json`;

/** Copies baked into each platform build. They carry the revision so devices can report what they run. */
const bundledCopies = ['extension/rules.json', 'android/app/src/main/assets/rules.json'];

/** Channels blocked from the phone's share sheet; sync folds them into the rules, then empties the file. */
export const ANDROID_CHANNELS_PATH = `/sdcard/Android/data/${ANDROID_PACKAGE}/files/channels-added.json`;

export type SyncReport = { revision: string; wrote: string[]; adb: string; pulledChannels: string[] };

export function sync({ adb: wanted = true } = {}): SyncReport {
  // GROD_ADB=0 wins over every caller, so test copies of the rules can never reach a real phone.
  const adb = wanted && process.env.GROD_ADB !== '0';
  const devices = adb ? phones() : [];
  const pulledChannels = devices.length ? pullChannels(devices) : [];
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
  return { revision: rev, wrote, adb: adb ? pushToPhone(compiled, devices) : 'skipped', pulledChannels };
}

/** One serial per phone: a phone on USB and on Wi-Fi adb at once shows up twice. */
function phones(): string[] {
  let serials: string[];
  try {
    serials = execFileSync('adb', ['devices'], { encoding: 'utf8' })
      .split('\n')
      .slice(1)
      .filter((l) => l.trim().endsWith('\tdevice'))
      .map((l) => l.split('\t')[0]);
  } catch {
    return [];
  }
  const seen = new Set<string>();
  return serials.filter((serial) => {
    let id = serial;
    try {
      id = execFileSync('adb', ['-s', serial, 'shell', 'getprop', 'ro.serialno'], { encoding: 'utf8' }).trim() || serial;
    } catch {
      // Keep the adb serial as the identity.
    }
    if (seen.has(id)) return false;
    seen.add(id);
    return true;
  });
}

function pullChannels(devices: string[]): string[] {
  const file = load();
  const added: string[] = [];
  for (const serial of devices) {
    let channels: Channel[];
    try {
      const raw = execFileSync('adb', ['-s', serial, 'exec-out', 'cat', ANDROID_CHANNELS_PATH], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
      channels = JSON.parse(raw).channels ?? [];
    } catch {
      continue; // No file yet, or the app is not installed.
    }
    for (const c of channels) {
      if (typeof c?.name === 'string' && typeof c?.handle === 'string' && addChannel(file, c)) added.push(`${c.name} (${normalizeHandle(c.handle)})`);
    }
  }
  if (added.length) save(file);
  // Emptied only after the rules hold them, so a failed save never loses a channel.
  const tmp = join(repoRoot, 'android/.channels-empty.json');
  mkdirSync(dirname(tmp), { recursive: true });
  writeAtomic(tmp, '{"channels":[]}\n');
  for (const serial of devices) {
    try {
      execFileSync('adb', ['-s', serial, 'shell', 'test', '-f', ANDROID_CHANNELS_PATH], { stdio: 'ignore' });
      execFileSync('adb', ['-s', serial, 'push', tmp, ANDROID_CHANNELS_PATH], { stdio: 'ignore' });
    } catch {
      // No file on this phone: nothing to empty.
    }
  }
  return added;
}

function pushToPhone(compiled: string, devices: string[]): string {
  if (devices.length === 0) return execFileSyncOk() ? 'no phone connected' : 'adb not found';
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

function execFileSyncOk(): boolean {
  try {
    execFileSync('adb', ['version'], { stdio: 'ignore' });
    return true;
  } catch {
    return false;
  }
}

/** Every edit ends here, so nothing needs a rebuild or reload: copies are rewritten and a connected phone gets the file. */
export function deliver(): string {
  const report = sync();
  const phone = report.adb.includes('pushed') ? 'phone updated' : `phone: ${report.adb}, it updates at the next "grod sync" with the phone connected`;
  return `Live: macOS app now, extension within a minute, ${phone}.`;
}
