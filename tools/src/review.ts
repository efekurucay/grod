import { mkdirSync, readdirSync, statSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { phoneSummary } from './phone.ts';
import { revision, writeAtomic } from './rules.ts';

/** Outside the repository on purpose: reviews hold personal data and the repo may be public. */
export const reviewsDir = process.env.GROD_REVIEWS ?? join(homedir(), '.config/grod/reviews');

/** ISO 8601 week name, e.g. 2026-W45, so files sort by time and match the review notes. */
export function isoWeek(date = new Date()): string {
  const d = new Date(Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()));
  const day = d.getUTCDay() || 7;
  d.setUTCDate(d.getUTCDate() + 4 - day);
  const yearStart = Date.UTC(d.getUTCFullYear(), 0, 1);
  const week = Math.ceil(((d.getTime() - yearStart) / 86_400_000 + 1) / 7);
  return `${d.getUTCFullYear()}-W${String(week).padStart(2, '0')}`;
}

export function snapshot(days = 7): string {
  const data = { week: isoWeek(), taken: new Date().toISOString(), rulesRevision: revision(), ...phoneSummary(days) };
  mkdirSync(reviewsDir, { recursive: true });
  const path = join(reviewsDir, `${data.week}.json`);
  writeAtomic(path, JSON.stringify(data, null, 2) + '\n');
  return path;
}

export function history(): { week: string; json: string; notes?: string; modified: string }[] {
  let names: string[];
  try {
    names = readdirSync(reviewsDir);
  } catch {
    return [];
  }
  return names
    .filter((n) => n.endsWith('.json'))
    .sort()
    .map((n) => {
      const week = n.slice(0, -5);
      const notes = names.includes(`${week}.md`) ? join(reviewsDir, `${week}.md`) : undefined;
      return { week, json: join(reviewsDir, n), notes, modified: statSync(join(reviewsDir, n)).mtime.toISOString() };
    });
}
