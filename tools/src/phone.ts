import { execFileSync } from 'node:child_process';
import { gunzipSync } from 'node:zlib';
import { ANDROID_PACKAGE, grodPhone } from './sync.ts';

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
export type RuleEvent = { t: number; rule: string; pkg: string; action: string; purpose?: string; ms?: number };
export type Usage = { generated: number; days: Record<string, Record<string, number>> };

const EXPORT_DIR = `/sdcard/Android/data/${ANDROID_PACKAGE}/files/export`;

function adb(serial: string | undefined, args: string[], binary = false): Buffer {
  return execFileSync('adb', [...(serial ? ['-s', serial] : []), ...args], {
    stdio: ['ignore', 'pipe', 'ignore'],
    maxBuffer: 256 * 1024 * 1024,
    encoding: binary ? 'buffer' : undefined,
  }) as Buffer;
}

function broadcast(serial: string | undefined, action: string): void {
  adb(serial, ['shell', 'am', 'broadcast', '-a', action, '-n', `${ANDROID_PACKAGE}/.ExportReceiver`]);
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

/**
 * docs/data.md "Export": release builds are not debuggable, so the app copies its private logs to its
 * external dir on request; they are read and the copy is deleted at once so it does not linger in shared storage.
 */
export function readPhone(days: number, serial = grodPhone()) {
  const since = Date.now() - days * 86_400_000;
  const cutoff = new Date(since).toISOString().slice(0, 10);
  try {
    broadcast(serial, 'dev.grod.EXPORT');
  } catch {
    throw new Error('Cannot reach grod on the phone: connect it over adb (USB or wifi.sh) with the grod app installed.');
  }
  try {
    // The export runs on the app's worker thread; wait until usage.json, written last, appears.
    let names: string[] = [];
    for (let i = 0; i < 20; i++) {
      execFileSync('sleep', ['0.25']);
      try {
        names = adb(serial, ['shell', 'ls', `${EXPORT_DIR}/log`]).toString('utf8').split(/\s+/).filter(Boolean);
        adb(serial, ['shell', 'test', '-f', `${EXPORT_DIR}/usage.json`]);
        break;
      } catch {
        names = [];
      }
    }
    const notifications: NotificationEvent[] = [];
    const events: RuleEvent[] = [];
    for (const name of names) {
      const day = name.match(/(\d{4}-\d{2}-\d{2})/)?.[1];
      if (!day || day < cutoff) continue;
      const raw = adb(serial, ['exec-out', 'cat', `${EXPORT_DIR}/log/${name}`], true);
      if (name.startsWith('notifications-')) notifications.push(...lines<NotificationEvent>(raw, name));
      if (name.startsWith('events-')) events.push(...lines<RuleEvent>(raw, name));
    }
    let usage: Usage | null = null;
    try {
      const parsed = JSON.parse(adb(serial, ['exec-out', 'cat', `${EXPORT_DIR}/usage.json`]).toString('utf8'));
      usage = parsed.days ? (parsed as Usage) : null;
    } catch {
      usage = null;
    }
    return {
      logs: { notifications: notifications.filter((n) => n.t >= since), events: events.filter((e) => e.t >= since) },
      usage,
    };
  } finally {
    try {
      broadcast(serial, 'dev.grod.EXPORT_CLEAR');
    } catch {
      // The phone went away mid-read; the app's next export overwrites the copy anyway.
    }
  }
}

const top = (counts: Map<string, number>, n = 10) =>
  [...counts.entries()].sort((a, b) => b[1] - a[1]).slice(0, n);

/** The numbers an agent needs to see what pulls the user in, without dumping message text. */
export type Logs = { notifications: NotificationEvent[]; events: RuleEvent[] };

export function summarize(days: number, logs: Logs, usage: Usage | null) {
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
  // Per rule and action: scroll pauses log "paused" then "left" or "continued"; the launcher logs "opened" or "backed-out".
  const actions = new Map<string, Record<string, number>>();
  for (const e of logs.events) {
    const counts = actions.get(e.rule) ?? {};
    counts[e.action] = (counts[e.action] ?? 0) + 1;
    actions.set(e.rule, counts);
  }
  // docs/rules.md "Opening check": what openings were for and how long those sessions lasted.
  const purposes = new Map<string, { openings: number; minutes: number[] }>();
  for (const e of logs.events) {
    if (!e.purpose || (e.action !== 'opening' && e.action !== 'session')) continue;
    const p = purposes.get(e.purpose) ?? { openings: 0, minutes: [] };
    if (e.action === 'opening') p.openings++;
    else if (typeof e.ms === 'number') p.minutes.push(e.ms / 60_000);
    purposes.set(e.purpose, p);
  }
  const median = (xs: number[]) => {
    const v = [...xs].sort((a, b) => a - b);
    return v.length ? Math.round(v[Math.floor(v.length / 2)] * 10) / 10 : null;
  };
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
    ruleActions: [...actions.entries()].map(([rule, counts]) => ({ rule, ...counts })),
    openings: [...purposes.entries()].map(([purpose, p]) => ({ purpose, openings: p.openings, sessions: p.minutes.length, medianMinutes: median(p.minutes) })),
    screenTimeMinutes: usage ? screenTime.map(([pkg, minutes]) => ({ pkg, minutes })) : 'usage access not granted or app not reachable',
  };
}

export function phoneSummary(days: number, serial?: string) {
  const { logs, usage } = readPhone(days, serial);
  return summarize(days, logs, usage);
}

/** Turkish-aware and dotless-i-blind, so "GELİYOR", "geliyor" and "Instagram" all match the way people type. */
const fold = (s: string) => s.toLocaleLowerCase('tr').replace(/ı/g, 'i');

/** History search, e.g. a WhatsApp message the sender deleted. */
export function searchNotifications(
  logs: Logs,
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
