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
  type Rule,
} from './rules.ts';
import { sync } from './sync.ts';

const HELP = `grod — get rid of distracting

Usage:
  grod list                     rules, state and platforms
  grod show <id>                one rule as JSON
  grod add <file.json | ->      add or replace a rule (JSON object, "-" = stdin)
  grod enable <id>
  grod disable <id>
  grod remove <id>
  grod validate                 check rules/grod.rules.json
  grod sync [--no-adb]          write platform copies, push to a connected phone
  grod revision                 revision every platform shows
  grod mcp                      run the MCP server on stdio

Rules file: ${rulesPath}`;

function main(argv: string[]): void {
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
      console.log(`${result} ${rule.id}. Run "grod sync" to push it to every platform.`);
      return;
    }
    case 'enable':
    case 'disable': {
      const file = load();
      findRule(file, need(arg, 'id')).enabled = cmd === 'enable';
      save(file);
      console.log(`${arg} ${cmd}d. Run "grod sync" to push it to every platform.`);
      return;
    }
    case 'remove': {
      const file = load();
      removeRule(file, need(arg, 'id'));
      save(file);
      console.log(`removed ${arg}. Run "grod sync" to push it to every platform.`);
      return;
    }
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

function need(value: string | undefined, name: string): string {
  if (!value) throw new Error(`missing <${name}>. Run "grod help".`);
  return value;
}

try {
  main(process.argv.slice(2));
} catch (e) {
  console.error((e as Error).message);
  process.exitCode = 1;
}
