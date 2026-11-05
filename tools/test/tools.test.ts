import { strict as assert } from 'node:assert';
import { spawn } from 'node:child_process';
import { cpSync, mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { test } from 'node:test';

const realRoot = resolve(import.meta.dirname, '../..');

/** A throwaway copy of the repo's rules so tests can save without touching the real file. */
function sandbox(): string {
  const home = mkdtempSync(join(tmpdir(), 'grod-'));
  cpSync(join(realRoot, 'rules'), join(home, 'rules'), { recursive: true });
  cpSync(join(realRoot, 'docs'), join(home, 'docs'), { recursive: true });
  return home;
}

process.env.GROD_HOME = sandbox();
const rules = await import('../src/rules.ts');

test('the repo rules file is valid', () => {
  const data = JSON.parse(readFileSync(join(realRoot, 'rules/grod.rules.json'), 'utf8'));
  assert.deepEqual(rules.validate(data), []);
});

test('validate catches what the schema cannot', () => {
  const file = rules.load();
  const bad = structuredClone(file);
  bad.rules.push(structuredClone(bad.rules[0]));
  bad.rules[0].web!.block = ['(unclosed'];
  bad.rules.push({ id: 'loop', title: 'Loop', enabled: true, web: { hosts: ['a.com'], redirect: [{ from: '^/x', to: '/y' }], block: ['^/y'] } });
  bad.rules.push({ id: 'empty', title: 'Empty', enabled: true });
  const errors = rules.validate(bad).join('\n');
  assert.match(errors, /duplicate rule id/);
  assert.match(errors, /bad regex/);
  assert.match(errors, /redirect target \/y is blocked/);
  assert.match(errors, /empty: no platform section/);
});

test('schema rejects unknown fields and bad ids', () => {
  const file = rules.load();
  const bad = structuredClone(file) as unknown as { rules: Record<string, unknown>[] };
  bad.rules[0].id = 'Not Kebab';
  bad.rules[1].color = 'red';
  assert.ok(rules.validate(bad).length >= 2);
});

test('upsert, save and revision round-trip', () => {
  const before = rules.revision();
  const file = rules.load();
  assert.equal(rules.upsertRule(file, { id: 'x-explore', title: 'X Explore', enabled: true, web: { hosts: ['x.com'], block: ['^/explore'] } }), 'added');
  rules.save(file);
  assert.notEqual(rules.revision(), before);
  assert.equal(rules.findRule(rules.load(), 'x-explore').title, 'X Explore');
  const invalid = rules.load();
  invalid.rules[0].id = 'BAD ID';
  assert.throws(() => rules.save(invalid), /refusing to save/);
});

test('MCP server lists its tools and edits rules over stdio', async () => {
  const home = sandbox();
  const child = spawn(process.execPath, [join(realRoot, 'tools/bin/grod.js'), 'mcp'], {
    env: { ...process.env, GROD_HOME: home },
    stdio: ['pipe', 'pipe', 'inherit'],
  });
  const replies = new Map<number, any>();
  let buffer = '';
  child.stdout.on('data', (chunk) => {
    buffer += chunk;
    for (let i = buffer.indexOf('\n'); i !== -1; i = buffer.indexOf('\n')) {
      const msg = JSON.parse(buffer.slice(0, i));
      buffer = buffer.slice(i + 1);
      if (msg.id !== undefined) replies.set(msg.id, msg);
    }
  });
  const send = (msg: object) => child.stdin.write(JSON.stringify({ jsonrpc: '2.0', ...msg }) + '\n');
  const reply = async (id: number) => {
    for (let t = 0; t < 100 && !replies.has(id); t++) await new Promise((r) => setTimeout(r, 50));
    return replies.get(id);
  };

  send({ id: 1, method: 'initialize', params: { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'test', version: '0' } } });
  assert.ok((await reply(1))?.result, 'initialize answered');
  send({ method: 'notifications/initialized' });
  send({ id: 2, method: 'tools/list' });
  const names = (await reply(2)).result.tools.map((t: { name: string }) => t.name).sort();
  assert.deepEqual(names, ['get_rule', 'list_rules', 'remove_rule', 'rules_guide', 'set_rule_enabled', 'sync', 'upsert_rule']);

  send({ id: 3, method: 'tools/call', params: { name: 'set_rule_enabled', arguments: { id: 'tiktok', enabled: false } } });
  assert.match((await reply(3)).result.content[0].text, /tiktok disabled/);
  send({ id: 4, method: 'tools/call', params: { name: 'upsert_rule', arguments: { rule: { id: 'Bad', title: '', enabled: true } } } });
  assert.equal((await reply(4)).result.isError, true);
  child.kill();

  const saved = JSON.parse(readFileSync(join(home, 'rules/grod.rules.json'), 'utf8'));
  assert.equal(saved.rules.find((r: { id: string }) => r.id === 'tiktok').enabled, false);
});
