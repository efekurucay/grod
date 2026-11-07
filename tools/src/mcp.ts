import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { McpServer } from '@modelcontextprotocol/server';
import { StdioServerTransport } from '@modelcontextprotocol/server/stdio';
import * as z from 'zod/v4';
import {
  findRule,
  load,
  platforms,
  removeRule,
  repoRoot,
  revision,
  save,
  upsertRule,
  openingDefaults,
  scrollDefaults,
  type Rule,
} from './rules.ts';
import { phoneSummary, readPhone, searchNotifications } from './phone.ts';
import { history, snapshot } from './review.ts';
import { deliver, sync } from './sync.ts';

const server = new McpServer({ name: 'grod', version: '1.0.0' });

type Result = { content: { type: 'text'; text: string }[]; isError?: boolean };

/** Every tool returns text; thrown errors become tool errors the agent can read and fix. */
function tool(fn: () => unknown): Result {
  try {
    const out = fn();
    return { content: [{ type: 'text', text: typeof out === 'string' ? out : JSON.stringify(out, null, 2) }] };
  } catch (e) {
    return { content: [{ type: 'text', text: (e as Error).message }], isError: true };
  }
}


server.registerTool(
  'rules_guide',
  {
    description:
      'Read first before writing a rule: how the scroll pause and web link rules work on each platform, plus the JSON schema.',
    inputSchema: z.object({}),
  },
  () =>
    tool(
      () =>
        readFileSync(join(repoRoot, 'docs/rules.md'), 'utf8') +
        '\n\n## Schema\n\n```json\n' +
        readFileSync(join(repoRoot, 'rules/grod.rules.schema.json'), 'utf8') +
        '```\n',
    ),
);

server.registerTool(
  'list_rules',
  { description: 'All distraction rules with enabled state and platforms, plus the current revision.', inputSchema: z.object({}) },
  () =>
    tool(() => ({
      revision: revision(),
      rules: load().rules.map((r) => ({ id: r.id, title: r.title, enabled: r.enabled, platforms: platforms(r) })),
    })),
);

server.registerTool(
  'get_rule',
  { description: 'One rule as stored: its Android packages, web hosts and link rules.', inputSchema: z.object({ id: z.string() }) },
  ({ id }) => tool(() => findRule(load(), id)),
);

server.registerTool(
  'upsert_rule',
  {
    description:
      'Add a rule or replace the rule with the same id. The rule is validated against the schema before saving; invalid rules are rejected with the reasons. A rule names an app\'s Android packages and its web hosts; scrolling in them counts toward the scroll pause.',
    inputSchema: z.object({ rule: z.record(z.string(), z.unknown()).describe('A complete rule object, see rules_guide') }),
  },
  ({ rule }) =>
    tool(() => {
      const file = load();
      const result = upsertRule(file, rule as unknown as Rule);
      save(file);
      return `${result} ${String(rule.id)}. ${deliver()}`;
    }),
);

server.registerTool(
  'set_rule_enabled',
  { description: 'Turn one rule on or off.', inputSchema: z.object({ id: z.string(), enabled: z.boolean() }) },
  ({ id, enabled }) =>
    tool(() => {
      const file = load();
      findRule(file, id).enabled = enabled;
      save(file);
      return `${id} ${enabled ? 'enabled' : 'disabled'}. ${deliver()}`;
    }),
);

server.registerTool(
  'remove_rule',
  { description: 'Delete a rule. Prefer set_rule_enabled false if it may come back.', inputSchema: z.object({ id: z.string() }) },
  ({ id }) =>
    tool(() => {
      const file = load();
      removeRule(file, id);
      save(file);
      return `removed ${id}. ${deliver()}`;
    }),
);

server.registerTool(
  'set_scroll_pause',
  {
    description:
      'Scroll pause settings: after every freeSeconds of scrolling in the apps and sites of enabled rules, a pause of firstPauseSeconds, then growth times longer each time up to maxPauseSeconds; resetMinutes without scrolling starts over. Give only what changes; returns the full settings.',
    inputSchema: z.object({
      freeSeconds: z.number().int().min(10).max(3600).optional(),
      firstPauseSeconds: z.number().int().min(1).max(600).optional(),
      growth: z.number().min(1).max(10).optional(),
      maxPauseSeconds: z.number().int().min(1).max(3600).optional(),
      resetMinutes: z.number().int().min(1).max(1440).optional(),
    }),
  },
  (changes) =>
    tool(() => {
      const file = load();
      file.scroll = { ...file.scroll, ...Object.fromEntries(Object.entries(changes).filter(([, v]) => v !== undefined)) };
      save(file);
      return `${JSON.stringify({ ...scrollDefaults, ...file.scroll })}. ${deliver()}`;
    }),
);

const allowEntry = z
  .object({ package: z.string().optional(), category: z.string().optional(), titleContains: z.string().optional() })
  .describe('Every field present must match: app package (com.whatsapp), Android category (event, reminder, msg, …) or part of the title (a contact name)');

server.registerTool(
  'set_notification_batching',
  {
    description:
      'Android notification batching: hold app notifications (no sound, not in the shade) and deliver them together on the clock, e.g. every 60 minutes on the hour. Calls and alarms always ring. Give only the fields to change. Evidence (Fitz et al. 2019): three batches a day helped attention and mood; hourly batches helped little.',
    inputSchema: z.object({
      enabled: z.boolean().optional(),
      deliverEvery: z.number().int().min(15).max(720).optional().describe('Minutes between deliveries'),
      deliverAt: z.array(z.string()).optional().describe('Fixed local times like ["09:00","13:00","18:00"]; replaces deliverEvery. Research favors about three a day.'),
      keepDays: z.number().int().min(0).max(365).optional().describe('Days of notification history kept on the phone'),
      allow: z.array(allowEntry).optional().describe('Replaces the whole list of notifications that arrive at once'),
    }),
  },
  (change) =>
    tool(() => {
      const file = load();
      file.notifications = { enabled: false, deliverEvery: 60, allow: [{ category: 'event' }, { category: 'reminder' }], keepDays: 30, ...file.notifications };
      for (const [k, v] of Object.entries(change)) if (v !== undefined) (file.notifications as Record<string, unknown>)[k] = v;
      save(file);
      return `${JSON.stringify(file.notifications)}. ${deliver()}`;
    }),
);

server.registerTool(
  'allow_notifications',
  { description: 'Let matching notifications arrive at once instead of waiting for the next batch.', inputSchema: z.object({ entry: allowEntry }) },
  ({ entry }) =>
    tool(() => {
      const file = load();
      if (!file.notifications) throw new Error('notification batching is not set up; call set_notification_batching first');
      const clean = Object.fromEntries(Object.entries(entry).filter(([, v]) => v));
      if (!Object.keys(clean).length) throw new Error('give package, category or titleContains');
      file.notifications.allow = [...(file.notifications.allow ?? []).filter((a) => JSON.stringify(a) !== JSON.stringify(clean)), clean];
      save(file);
      return `allowed ${JSON.stringify(clean)}. ${deliver()}`;
    }),
);

server.registerTool(
  'phone_stats',
  {
    description:
      'What pulled the user in over the last days, read from the phone over adb: notifications per app and per hour, how many grod held, scroll pauses per rule and whether the user left or continued after them, and screen time per app. Use it to suggest rule, scroll pause or notification changes.',
    inputSchema: z.object({ days: z.number().int().min(1).max(365).default(7) }),
  },
  ({ days }) => tool(() => phoneSummary(days)),
);

server.registerTool(
  'search_notifications',
  {
    description:
      'Search the notification history kept on the phone (text and title). withdrawnOnly lists notifications the app later took back: deleted messages, but also messages read on another device.',
    inputSchema: z.object({
      query: z.string().optional(),
      app: z.string().optional().describe('App name or package'),
      withdrawnOnly: z.boolean().default(false),
      days: z.number().int().min(1).max(365).default(30),
    }),
  },
  ({ query, app, withdrawnOnly, days }) => tool(() => searchNotifications(readPhone(days).logs, { query, app, withdrawnOnly })),
);

server.registerTool(
  'weekly_reviews',
  {
    description:
      'Past weekly review snapshots (numbers per week) and their notes, stored outside the repo in ~/.config/grod/reviews. take: true first saves this week from the phone over adb. Compare weeks before proposing changes.',
    inputSchema: z.object({ take: z.boolean().default(false) }),
  },
  ({ take }) =>
    tool(() => {
      const taken = take ? snapshot() : undefined;
      return { taken, reviews: history() };
    }),
);

server.registerTool(
  'set_opening_check',
  {
    description:
      'Android opening check: when a distracting app comes to the front after awayMinutes away, grod asks Message / Look something up / Browse. Quick purposes open at once with a window (quickMinutes until learned from past sessions); Browse waits. Give only what changes; returns the full settings.',
    inputSchema: z.object({
      awayMinutes: z.number().int().min(1).max(240).optional(),
      quickMinutes: z.number().int().min(1).max(10).optional(),
    }),
  },
  (changes) =>
    tool(() => {
      const file = load();
      file.opening = { ...file.opening, ...Object.fromEntries(Object.entries(changes).filter(([, v]) => v !== undefined)) };
      save(file);
      return `${JSON.stringify({ ...openingDefaults, ...file.opening })}. ${deliver()}`;
    }),
);

server.registerTool(
  'set_launcher',
  {
    description:
      'grod launcher on Android (text-only home screen). Distracting apps are listed small and go through the opening check; hidden apps are only found by search. Apps named in android rules are distracting already. Give only what changes.',
    inputSchema: z.object({
      addDistracting: z.array(z.string()).optional().describe('Package names, e.g. com.twitter.android'),
      removeDistracting: z.array(z.string()).optional(),
      hide: z.array(z.string()).optional(),
      show: z.array(z.string()).optional(),
    }),
  },
  ({ addDistracting = [], removeDistracting = [], hide = [], show = [] }) =>
    tool(() => {
      const file = load();
      const l = (file.launcher ??= { distracting: [], hidden: [] });
      const set = (list: string[] = [], add: string[], remove: string[]) => [...new Set([...list.filter((p) => !remove.includes(p)), ...add])];
      l.distracting = set(l.distracting, addDistracting, removeDistracting);
      l.hidden = set(l.hidden, hide, show);
      save(file);
      return `${JSON.stringify(l)}. ${deliver()}`;
    }),
);

server.registerTool(
  'sync',
  {
    description:
      'Write the platform copies (extension, Android assets) and push the rules to a phone connected over adb. Returns the revision each device should show.',
    inputSchema: z.object({ adb: z.boolean().default(true).describe('Push to a connected Android phone') }),
  },
  ({ adb }) => tool(() => sync({ adb })),
);

await server.connect(new StdioServerTransport());
