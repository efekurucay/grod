import { createHash } from 'node:crypto';
import { readFileSync, renameSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Ajv2020 } from 'ajv/dist/2020.js';

export type Matcher = { viewId?: string; text?: string; desc?: string; selected?: boolean };

export type Rule = {
  id: string;
  title: string;
  why?: string;
  enabled: boolean;
  web?: {
    hosts: string[];
    hide?: string[];
    redirect?: { from: string; to: string }[];
    block?: string[];
  };
  android?: {
    packages: string[];
    feed?: Matcher[];
    exempt?: Matcher[];
    cover?: Matcher[];
    action?: 'back' | 'home';
  };
  macos?: { quitApps?: string[] };
};

export type RulesFile = { $schema?: string; schemaVersion: 1; rules: Rule[] };

/** Repo root. GROD_HOME wins so the CLI works from any checkout. */
export const repoRoot = process.env.GROD_HOME
  ? resolve(process.env.GROD_HOME)
  : resolve(dirname(fileURLToPath(import.meta.url)), '../..');

export const rulesPath = join(repoRoot, 'rules/grod.rules.json');
const schemaPath = join(repoRoot, 'rules/grod.rules.schema.json');

const ajv = new Ajv2020({ allErrors: true, strict: false });
let validateSchema: ReturnType<typeof ajv.compile> | undefined;

/** Schema errors plus the checks JSON Schema cannot express. Empty array = valid. */
export function validate(data: unknown): string[] {
  validateSchema ??= ajv.compile(JSON.parse(readFileSync(schemaPath, 'utf8')));
  if (!validateSchema(data)) {
    return (validateSchema.errors ?? []).map((e) => `${e.instancePath || '/'} ${e.message}`);
  }
  const errors: string[] = [];
  const seen = new Set<string>();
  for (const rule of (data as RulesFile).rules) {
    if (seen.has(rule.id)) errors.push(`duplicate rule id "${rule.id}"`);
    seen.add(rule.id);
    const patterns = [...(rule.web?.block ?? []), ...(rule.web?.redirect ?? []).map((r) => r.from)];
    for (const p of patterns) {
      try {
        new RegExp(p);
      } catch (e) {
        errors.push(`${rule.id}: bad regex ${JSON.stringify(p)}: ${(e as Error).message}`);
      }
    }
    for (const r of rule.web?.redirect ?? []) {
      // A redirect into a blocked path would bounce the user to the blocked page every time.
      const target = r.to.replace(/\$\d/g, 'x');
      if ((rule.web?.block ?? []).some((b) => safeTest(b, target))) {
        errors.push(`${rule.id}: redirect target ${r.to} is blocked by the same rule`);
      }
    }
    if (!rule.web && !rule.android && !rule.macos) errors.push(`${rule.id}: no platform section`);
  }
  return errors;
}

function safeTest(pattern: string, s: string): boolean {
  try {
    return new RegExp(pattern).test(s);
  } catch {
    return false;
  }
}

export function load(): RulesFile {
  const data = JSON.parse(readFileSync(rulesPath, 'utf8'));
  const errors = validate(data);
  if (errors.length) throw new Error(`rules/grod.rules.json is invalid:\n  ${errors.join('\n  ')}`);
  return data as RulesFile;
}

export function serialize(file: RulesFile): string {
  return JSON.stringify(file, null, 2) + '\n';
}

/** Validates before writing and replaces the file atomically, so readers never see half a file. */
export function save(file: RulesFile): void {
  const errors = validate(file);
  if (errors.length) throw new Error(`refusing to save invalid rules:\n  ${errors.join('\n  ')}`);
  writeAtomic(rulesPath, serialize(file));
}

export function writeAtomic(path: string, content: string): void {
  const tmp = `${path}.tmp-${process.pid}`;
  writeFileSync(tmp, content);
  renameSync(tmp, path);
}

/** Hash of the file's raw bytes (docs/rules.md#revision); every platform shows it. */
export function revision(): string {
  return createHash('sha256').update(readFileSync(rulesPath)).digest('hex').slice(0, 12);
}

export function findRule(file: RulesFile, id: string): Rule {
  const rule = file.rules.find((r) => r.id === id);
  if (!rule) throw new Error(`no rule "${id}". Known: ${file.rules.map((r) => r.id).join(', ')}`);
  return rule;
}

export function upsertRule(file: RulesFile, rule: Rule): 'added' | 'updated' {
  const i = file.rules.findIndex((r) => r.id === rule.id);
  if (i === -1) {
    file.rules.push(rule);
    return 'added';
  }
  file.rules[i] = rule;
  return 'updated';
}

export function removeRule(file: RulesFile, id: string): void {
  findRule(file, id);
  file.rules = file.rules.filter((r) => r.id !== id);
}

export function platforms(rule: Rule): string[] {
  return (['web', 'android', 'macos'] as const).filter((p) => rule[p]);
}
