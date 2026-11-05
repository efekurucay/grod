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
  type Rule,
} from './rules.ts';
import { sync } from './sync.ts';

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

const afterEdit = 'Saved. Call sync to push it to the phone; the macOS app and extension pick it up on their own.';

server.registerTool(
  'rules_guide',
  {
    description:
      'Read first before writing a rule: how each platform applies rules, selector and view-id advice, plus the JSON schema.',
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
  { description: 'One rule as stored, with every selector and view id.', inputSchema: z.object({ id: z.string() }) },
  ({ id }) => tool(() => findRule(load(), id)),
);

server.registerTool(
  'upsert_rule',
  {
    description:
      'Add a rule or replace the rule with the same id. The rule is validated against the schema before saving; invalid rules are rejected with the reasons. Prefer removing the feed (hide/cover/feed) over blocking a whole app.',
    inputSchema: z.object({ rule: z.record(z.string(), z.unknown()).describe('A complete rule object, see rules_guide') }),
  },
  ({ rule }) =>
    tool(() => {
      const file = load();
      const result = upsertRule(file, rule as unknown as Rule);
      save(file);
      return `${result} ${String(rule.id)}. ${afterEdit}`;
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
      return `${id} ${enabled ? 'enabled' : 'disabled'}. ${afterEdit}`;
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
      return `removed ${id}. ${afterEdit}`;
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
