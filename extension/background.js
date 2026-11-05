// Chrome runs this file as a service worker; Firefox runs background.scripts, which loads rules-core first.
if (!globalThis.grodCore) importScripts('lib/rules-core.js');

const api = globalThis.browser ?? globalThis.chrome;
const core = globalThis.grodCore;
const LOCALHOST_URL = 'http://127.0.0.1:47770/rules.json';

async function fetchRules(url) {
  try {
    const res = await fetch(url, { cache: 'no-store' });
    if (!res.ok) return null;
    const file = await res.json();
    return core.isValidRulesFile(file) ? file : null;
  } catch {
    return null;
  }
}

// Writes only on change: every open tab listens to the `rules` key.
async function save(file, source) {
  const { rules } = await api.storage.local.get('rules');
  if (rules?.source === source && JSON.stringify(rules.file) === JSON.stringify(file)) return;
  await api.storage.local.set({ rules: { file, revision: String(file.revision ?? 'unknown'), source } });
}

// A failed fetch keeps whatever is stored.
async function refresh() {
  const file = await fetchRules(LOCALHOST_URL);
  if (!file) return false;
  await save(file, 'localhost');
  // Separate key so the per-minute timestamp does not re-send the rules to every tab.
  await api.storage.local.set({ fetchedAt: Date.now() });
  return true;
}

// preferBundled: an install or extension reload is how a new `grod sync` copy reaches the extension,
// so it replaces a stored copy when localhost is not reachable.
async function load(preferBundled) {
  api.alarms.create('refresh', { periodInMinutes: 1 });
  if (await refresh()) return;
  const { rules } = await api.storage.local.get('rules');
  if (rules && !preferBundled) return;
  const file = await fetchRules(api.runtime.getURL('rules.json'));
  if (file) await save(file, 'bundled');
}

// Serialized so fires from several tabs at once are all counted.
let counting = Promise.resolve();
function countFire(ruleId) {
  counting = counting.then(async () => {
    const { counts = {} } = await api.storage.local.get('counts');
    counts[ruleId] = (counts[ruleId] ?? 0) + 1;
    await api.storage.local.set({ counts });
  });
}

api.runtime.onInstalled.addListener(({ reason }) => load(reason === 'install' || reason === 'update'));
api.runtime.onStartup.addListener(() => load(false));
api.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === 'refresh') refresh();
});

// Content scripts cannot open extension pages in every browser, so the background navigates the tab.
api.runtime.onMessage.addListener((message, sender) => {
  if (typeof message?.rule !== 'string') return;
  countFire(message.rule);
  if (message.type === 'block' && sender.tab) {
    const query = new URLSearchParams({ rule: message.rule, from: message.from });
    api.tabs.update(sender.tab.id, { url: api.runtime.getURL(`blocked.html?${query}`) });
  }
});
