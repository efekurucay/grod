import { strict as assert } from 'node:assert';
import { test } from 'node:test';
import { searchNotifications, summarize, type NotificationEvent } from '../src/phone.ts';

const now = Date.now();
const n = (o: Partial<NotificationEvent>): NotificationEvent => ({ t: now - 3_600_000, e: 'posted', pkg: 'com.whatsapp', app: 'WhatsApp', ...o });
const logs = {
  notifications: [
    n({ title: 'Ayşe', text: 'geliyor musun?', held: true }),
    n({ title: 'Ayşe', text: 'yanlış kişi', held: true }),
    n({ e: 'removed', title: 'Ayşe', reason: 'app' }),
    n({ pkg: 'com.instagram.android', app: 'Instagram', title: 'x liked your post', held: true }),
    n({ pkg: 'com.google.android.calendar', app: 'Calendar', cat: 'event', title: 'Standup', held: false }),
  ],
  events: [
    { t: now, rule: 'instagram', pkg: 'com.instagram.android', action: 'paused' },
    { t: now, rule: 'instagram', pkg: 'com.instagram.android', action: 'left' },
    { t: now, rule: 'instagram', pkg: 'com.instagram.android', action: 'paused' },
    { t: now, rule: 'youtube', pkg: 'com.google.android.youtube', action: 'paused' },
    { t: now, rule: 'instagram', pkg: 'com.instagram.android', action: 'opening', purpose: 'message' },
    { t: now, rule: 'instagram', pkg: 'com.instagram.android', action: 'session', purpose: 'message', ms: 180_000 },
  ],
};

test('summarize counts per app, held, withdrawn and actions per rule', () => {
  const s = summarize(7, logs, { generated: now, days: { [new Date().toISOString().slice(0, 10)]: { 'com.instagram.android': 3_600_000 } } });
  assert.equal(s.notifications.total, 4);
  assert.equal(s.notifications.held, 3);
  assert.deepEqual(s.notifications.topApps[0], { app: 'WhatsApp', count: 2, held: 2 });
  assert.equal(s.notifications.withdrawnByApp, 1);
  assert.deepEqual(s.ruleActions[0], { rule: 'instagram', paused: 2, left: 1, opening: 1, session: 1 });
  assert.deepEqual(s.openings, [{ purpose: 'message', openings: 1, sessions: 1, medianMinutes: 3 }]);
  assert.deepEqual(s.screenTimeMinutes, [{ pkg: 'com.instagram.android', minutes: 60 }]);
});

test('search finds text, filters by app and by withdrawn', () => {
  assert.equal(searchNotifications(logs, { query: 'GELİYOR' }).length, 1);
  assert.equal(searchNotifications(logs, { app: 'instagram' }).length, 1);
  assert.equal(searchNotifications(logs, { withdrawnOnly: true }).length, 2);
});

test('isoWeek names weeks the ISO way', async () => {
  const { isoWeek } = await import('../src/review.ts');
  assert.equal(isoWeek(new Date(2026, 10, 6)), '2026-W45');
  assert.equal(isoWeek(new Date(2027, 0, 1)), '2026-W53');
  assert.equal(isoWeek(new Date(2026, 0, 1)), '2026-W01');
});
