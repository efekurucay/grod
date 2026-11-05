import { execFileSync } from 'node:child_process';
import { gunzipSync } from 'node:zlib';
import { ANDROID_PACKAGE } from './sync.ts';

export type NotificationEvent = {
  t: number;
  e: 'posted' | 'removed';
  pkg: string;
  app?: string;
  cat?: string;
  title?: string;
  text?: string;
  held?: boolean;
  reason?: string;
};
export type RuleEvent = { t: number; rule: string; pkg: string; action: string };
export type Usage = { generated: number; days: Record<string, Record<string, number>> };

/** docs/data.md: the logs sit in the app's private storage; a debuggable build lets adb read them with run-as. */
function runAs(serial: string | undefined, command: string, binary = false): Buffer {
  const target = serial ? ['-s', serial] : [];
  return execFileSync('adb', [...target, 'exec-out', 'run-as', ANDROID_PACKAGE, 'sh', '-c', command], {
    stdio: ['ignore', 'pipe', 'ignore'],
    maxBuffer: 256 * 1024 * 1024,
    encoding: binary ? 'buffer' : undefined,
  }) as Buffer;
}

function lines<T>(raw: Buffer, name: string): T[] {
  const text = name.endsWith('.gz') ? gunzipSync(raw).toString('utf8') : raw.toString('utf8');
  return text
    .split('\n')
    .filter(Boolean)
    .flatMap((l) => {
      try {
        return [JSON.parse(l) as T];
      } catch {
        return []; // A line cut off by a crash mid-write.
      }
    });
}

export function readLogs(days: number, serial?: string) {
  const since = Date.now() - days * 86_400_000;
  const cutoff = new Date(since).toISOString().slice(0, 10);
  let names: string[];
  try {
    names = runAs(serial, 'ls files/log').toString('utf8').split(/\s+/).filter(Boolean);
  } catch {
    throw new Error('Cannot read grod logs: connect the phone over adb (USB or android/wifi.sh) with the grod app installed.');
  }
  const notifications: NotificationEvent[] = [];
  const events: RuleEvent[] = [];
  for (const name of names) {
    const day = name.match(/(\d{4}-\d{2}-\d{2})/)?.[1];
    if (!day || day < cutoff) continue;
    const raw = runAs(serial, `cat files/log/${name}`, true);
    if (name.startsWith('notifications-')) notifications.push(...lines<NotificationEvent>(raw, name));
    if (name.startsWith('events-')) events.push(...lines<RuleEvent>(raw, name));
  }
  return { notifications: notifications.filter((n) => n.t >= since), events: events.filter((e) => e.t >= since) };
}

export function readUsage(serial?: string): Usage | null {
  const target = serial ? ['-s', serial] : [];
  try {
    execFileSync('adb', [...target, 'shell', 'am', 'broadcast', '-a', 'dev.grod.EXPORT', '-n', `${ANDROID_PACKAGE}/.ExportReceiver`], { stdio: 'ignore' });
    // The receiver writes asynchronously; usage queries take well under a second.
    execFileSync('sleep', ['1.5']);
    return JSON.parse(runAs(serial, 'cat files/export/usage.json').toString('utf8')) as Usage;
  } catch {
    return null;
  }
}

const top = (counts: Map<string, number>, n = 10) =>
  [...counts.entries()].sort((a, b) => b[1] - a[1]).slice(0, n);

/** The numbers an agent needs to see what pulls the user in, without dumping message text. */
export function summarize(days: number, logs: ReturnType<typeof readLogs>, usage: Usage | null) {
  const posted = logs.notifications.filter((n) => n.e === 'posted');
  const byApp = new Map<string, number>();
  const heldByApp = new Map<string, number>();
  for (const n of posted) {
    const app = n.app ?? n.pkg;
    byApp.set(app, (byApp.get(app) ?? 0) + 1);
    if (n.held) heldByApp.set(app, (heldByApp.get(app) ?? 0) + 1);
  }
  const byHour = new Array(24).fill(0);
  for (const n of posted) byHour[new Date(n.t).getHours()]++;
  const fires = new Map<string, number>();
  for (const e of logs.events) fires.set(e.rule, (fires.get(e.rule) ?? 0) + 1);
  const deleted = logs.notifications.filter((n) => n.e === 'removed' && n.reason === 'app').length;

  let screenTime: [string, number][] = [];
  if (usage) {
    const total = new Map<string, number>();
    const cutoff = new Date(Date.now() - days * 86_400_000).toISOString().slice(0, 10);
    for (const [day, apps] of Object.entries(usage.days)) {
      if (day < cutoff) continue;
      for (const [pkg, ms] of Object.entries(apps)) total.set(pkg, (total.get(pkg) ?? 0) + ms);
    }
    screenTime = top(total).map(([pkg, ms]) => [pkg, Math.round(ms / 60_000)]);
  }

  return {
    days,
    notifications: {
      total: posted.length,
      held: posted.filter((n) => n.held).length,
      perDay: Math.round((posted.length / days) * 10) / 10,
      topApps: top(byApp).map(([app, count]) => ({ app, count, held: heldByApp.get(app) ?? 0 })),
      byHour,
      withdrawnByApp: deleted,
    },
    ruleFires: top(fires, 50).map(([rule, count]) => ({ rule, count })),
    screenTimeMinutes: usage ? screenTime.map(([pkg, minutes]) => ({ pkg, minutes })) : 'usage access not granted or app not reachable',
  };
}

/** Turkish-aware and dotless-i-blind, so "GELİYOR", "geliyor" and "Instagram" all match the way people type. */
const fold = (s: string) => s.toLocaleLowerCase('tr').replace(/ı/g, 'i');

/** History search, e.g. a WhatsApp message the sender deleted. */
export function searchNotifications(
  logs: ReturnType<typeof readLogs>,
  { query, app, withdrawnOnly, limit = 50 }: { query?: string; app?: string; withdrawnOnly?: boolean; limit?: number },
) {
  const q = query ? fold(query) : undefined;
  const posted = logs.notifications.filter((n) => n.e === 'posted');
  const withdrawn = new Set(
    logs.notifications.filter((n) => n.e === 'removed' && n.reason === 'app').map((n) => `${n.pkg}|${n.title}`),
  );
  return posted
    .filter((n) => !app || fold(n.app ?? n.pkg).includes(fold(app)) || n.pkg === app)
    .filter((n) => !q || fold(`${n.title ?? ''} ${n.text ?? ''}`).includes(q))
    .filter((n) => !withdrawnOnly || withdrawn.has(`${n.pkg}|${n.title}`))
    .sort((a, b) => b.t - a.t)
    .slice(0, limit)
    .map((n) => ({ time: new Date(n.t).toLocaleString('tr-TR'), app: n.app ?? n.pkg, title: n.title, text: n.text, held: n.held ?? false }));
}
