import { readFileSync } from 'node:fs';
import {
  findRule,
  load,
  platforms,
  removeRule,
  revision,
  rulesPath,
  save,
  upsertRule,
  validate,
  scrollDefaults,
  type Rule,
} from './rules.ts';
import { phoneSummary, readPhone, searchNotifications } from './phone.ts';
import { history, snapshot } from './review.ts';
import { deliver, sync } from './sync.ts';

const HELP = `grod CLI — get rid of distracting. Keep what matters.

Usage:
  grod list                     rules, state and platforms
  grod show <id>                one rule as JSON
  grod add <file.json | ->      add or replace a rule (JSON object, "-" = stdin)
  grod enable <id>
  grod disable <id>
  grod remove <id>
  grod scroll                   scroll pause settings
  grod scroll free <seconds>    scrolling allowed before each pause
  grod scroll first <seconds>   length of the first pause
  grod scroll growth <number>   each pause is this many times longer
  grod scroll max <seconds>     longest pause
  grod scroll reset <minutes>   minutes without scrolling that start over
  grod notifications            batching settings (Android)
  grod notifications on|off
  grod notifications every <minutes>
  grod notifications at 09:00,13:00,18:00   fixed delivery times ("at off" to go back to every)
  grod notifications keep <days>
  grod notifications allow|disallow <package> | category:<c> | title:<text>
  grod phone stats [days]       what pulled you in: notifications, scroll pauses, screen time
  grod phone search <text> [--app A] [--withdrawn] [--days N]
  grod launcher                 home screen settings (Android)
  grod launcher distracting|calm <package>   mark or unmark a distracting app
  grod launcher hide|show <package>
  grod launcher pause <seconds>
  grod review                   past weekly reviews
  grod review snapshot          save this week's numbers from the phone
  grod review set <1-7> <HH:MM> weekly reminder on the phone (1 = Monday)
  grod validate                 check rules/grod.rules.json
  grod sync [--no-adb]          write platform copies, push to a connected phone
  grod revision                 revision every platform shows
  grod mcp                      run the MCP server on stdio

Rules file: ${rulesPath}`;

async function main(argv: string[]): Promise<void> {
  const [cmd, arg] = argv;
  switch (cmd) {
    case 'list': {
      const file = load();
      for (const r of file.rules) {
        console.log(`${r.enabled ? '●' : '○'} ${r.id.padEnd(22)} ${r.title.padEnd(24)} ${platforms(r).join(', ')}`);
      }
      console.log(`\nrevision ${revision()}`);
      return;
    }
    case 'show':
      console.log(JSON.stringify(findRule(load(), need(arg, 'id')), null, 2));
      return;
    case 'add': {
      const src = need(arg, 'file');
      const rule = JSON.parse(readFileSync(src === '-' ? 0 : src, 'utf8')) as Rule;
      const file = load();
      const result = upsertRule(file, rule);
      save(file);
      console.log(`${result} ${rule.id}.`);
      console.log(deliver());
      return;
    }
    case 'enable':
    case 'disable': {
      const file = load();
      findRule(file, need(arg, 'id')).enabled = cmd === 'enable';
      save(file);
      console.log(`${arg} ${cmd}d.`);
      console.log(deliver());
      return;
    }
    case 'remove': {
      const file = load();
      removeRule(file, need(arg, 'id'));
      save(file);
      console.log(`removed ${arg}.`);
      console.log(deliver());
      return;
    }
    case 'scroll':
      return scroll(argv.slice(1));
    case 'notifications':
      return notifications(argv.slice(1));
    case 'phone':
      return phone(argv.slice(1));
    case 'review':
      return review(argv.slice(1));
    case 'launcher':
      return launcher(argv.slice(1));
    case 'validate': {
      const errors = validate(JSON.parse(readFileSync(rulesPath, 'utf8')));
      if (errors.length) {
        console.error(errors.join('\n'));
        process.exitCode = 1;
      } else console.log(`valid, revision ${revision()}`);
      return;
    }
    case 'sync': {
      const report = sync({ adb: !argv.includes('--no-adb') });
      console.log(`revision ${report.revision}`);
      for (const w of report.wrote) console.log(`  wrote ${w}`);
      console.log(`  phone: ${report.adb}`);
      console.log('  macOS app and extension pick the file up on their own.');
      return;
    }
    case 'revision':
      console.log(revision());
      return;
    case 'mcp':
      import('./mcp.ts');
      return;
    default:
      console.log(HELP);
      if (cmd && cmd !== 'help' && cmd !== '--help') process.exitCode = 1;
  }
}

function scroll([sub, value]: string[]): void {
  const file = load();
  const keys = { free: 'freeSeconds', first: 'firstPauseSeconds', growth: 'growth', max: 'maxPauseSeconds', reset: 'resetMinutes' } as const;
  if (!sub) {
    console.log(JSON.stringify({ ...scrollDefaults, ...file.scroll }, null, 2));
    return;
  }
  if (!(sub in keys)) throw new Error('usage: grod scroll [free|first|max <seconds> | growth <number> | reset <minutes>]');
  file.scroll = { ...file.scroll, [keys[sub as keyof typeof keys]]: Number(need(value, 'number')) };
  save(file);
  console.log(JSON.stringify({ ...scrollDefaults, ...file.scroll }));
  console.log(deliver());
}

function notifications([sub, value]: string[]): void {
  const file = load();
  const n = (file.notifications ??= { enabled: false, deliverEvery: 60, allow: [{ category: 'event' }, { category: 'reminder' }], keepDays: 30 });
  if (!sub) {
    console.log(JSON.stringify(n, null, 2));
    return;
  }
  if (sub === 'on' || sub === 'off') n.enabled = sub === 'on';
  else if (sub === 'every') {
    n.deliverEvery = Number(need(value, 'minutes'));
    delete n.deliverAt;
  } else if (sub === 'at') {
    const times = need(value, 'HH:MM,HH:MM');
    if (times === 'off') delete n.deliverAt;
    else n.deliverAt = times.split(',').map((x) => x.trim());
  }
  else if (sub === 'keep') n.keepDays = Number(need(value, 'days'));
  else if (sub === 'allow' || sub === 'disallow') {
    const entry = parseAllow(need(value, 'package | category:<c> | title:<text>'));
    const same = (a: object) => JSON.stringify(a) === JSON.stringify(entry);
    n.allow = (n.allow ?? []).filter((a) => !same(a));
    if (sub === 'allow') n.allow.push(entry);
  } else throw new Error('usage: grod notifications [on|off|every <min>|keep <days>|allow|disallow <entry>]');
  save(file);
  console.log(JSON.stringify(n, null, 2));
  console.log(deliver());
}

function parseAllow(value: string) {
  if (value.startsWith('category:')) return { category: value.slice(9) };
  if (value.startsWith('title:')) return { titleContains: value.slice(6) };
  return { package: value };
}

function phone([sub, ...rest]: string[]): void {
  const flag = (name: string) => {
    const i = rest.indexOf(`--${name}`);
    return i === -1 ? undefined : rest[i + 1];
  };
  if (sub === 'stats') {
    const days = Number(rest[0] ?? 7);
    console.log(JSON.stringify(phoneSummary(days), null, 2));
    return;
  }
  if (sub === 'search') {
    const days = Number(flag('days') ?? 30);
    const query = rest[0]?.startsWith('--') ? undefined : rest[0];
    const hits = searchNotifications(readPhone(days).logs, { query, app: flag('app'), withdrawnOnly: rest.includes('--withdrawn') });
    for (const h of hits) console.log(`${h.time}  ${h.app}  ${h.title ?? ''}: ${h.text ?? ''}`);
    return;
  }
  throw new Error('usage: grod phone stats [days] | grod phone search <text> [--app A] [--withdrawn] [--days N]');
}

function launcher([sub, value]: string[]): void {
  const file = load();
  const l = (file.launcher ??= { distracting: [], hidden: [], pauseSeconds: 10 });
  const toggle = (list: string[] | undefined, pkg: string, on: boolean) => {
    const rest = (list ?? []).filter((p) => p !== pkg);
    return on ? [...rest, pkg] : rest;
  };
  if (!sub) {
    console.log(JSON.stringify(l, null, 2));
    return;
  }
  if (sub === 'distracting' || sub === 'calm') l.distracting = toggle(l.distracting, need(value, 'package'), sub === 'distracting');
  else if (sub === 'hide' || sub === 'show') l.hidden = toggle(l.hidden, need(value, 'package'), sub === 'hide');
  else if (sub === 'pause') l.pauseSeconds = Number(need(value, 'seconds'));
  else throw new Error('usage: grod launcher [distracting|calm|hide|show <package> | pause <seconds>]');
  save(file);
  console.log(JSON.stringify(l, null, 2));
  console.log(deliver());
}

function review([sub, a, b]: string[]): void {
  if (!sub) {
    for (const r of history()) console.log(`${r.week}  ${r.json}${r.notes ? `  ${r.notes}` : ''}`);
    return;
  }
  if (sub === 'snapshot') {
    console.log(snapshot());
    return;
  }
  if (sub === 'set') {
    const file = load();
    file.review = { weekday: Number(need(a, 'weekday 1-7')), time: need(b, 'HH:MM') };
    save(file);
    console.log(JSON.stringify(file.review));
    console.log(deliver());
    return;
  }
  throw new Error('usage: grod review [snapshot | set <1-7> <HH:MM>]');
}

function need(value: string | undefined, name: string): string {
  if (!value) throw new Error(`missing <${name}>. Run "grod help".`);
  return value;
}

main(process.argv.slice(2)).catch((e) => {
  console.error((e as Error).message);
  process.exitCode = 1;
});
