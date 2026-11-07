// Chrome runs this file as a service worker; Firefox runs background.scripts, which loads rules-core first.
if (!globalThis.grodCore) importScripts('lib/rules-core.js');

const api = globalThis.browser ?? globalThis.chrome;
const core = globalThis.grodCore;
const LOCALHOST_URL = 'http://127.0.0.1:47770/rules.json';

// Storage read-modify-writes run one at a time so concurrent messages do not overwrite each other.
let queue = Promise.resolve();
function serial(task) {
  queue = queue.then(task, task);
  return queue;
}

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
  if (rules?.source !== source || JSON.stringify(rules.file) !== JSON.stringify(file)) {
    await api.storage.local.set({ rules: { file, revision: String(file.revision ?? 'unknown'), source } });
  }
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

// docs/rules.md "Scroll pause". The session lives in storage.session: shared by every tab, kept across
// service worker restarts, gone when the browser quits. Returns the pause the scrolling tab must show, or null.
function scrolled(ruleId) {
  return serial(async () => {
    const [{ rules }, { session }] = await Promise.all([api.storage.local.get('rules'), api.storage.session.get('session')]);
    const result = core.scrollEvent(session ?? null, core.scrollSettings(rules?.file), Date.now(), ruleId);
    await api.storage.session.set({ session: result.session });
    if (result.started) {
      const { pauses = {} } = await api.storage.local.get('pauses');
      pauses[ruleId] = (pauses[ruleId] ?? 0) + 1;
      await api.storage.local.set({ pauses });
    }
    return result.pause;
  });
}

api.runtime.onInstalled.addListener(({ reason }) => load(reason === 'install' || reason === 'update'));
api.runtime.onStartup.addListener(() => load(false));
api.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === 'refresh') refresh();
});

// Content scripts cannot close tabs, nor open extension pages in every browser, so the background acts on the tab.
api.runtime.onMessage.addListener((message, sender, respond) => {
  const tabId = sender.tab?.id;
  if (message?.type === 'scroll' && typeof message.rule === 'string') {
    scrolled(message.rule).then(respond, () => respond(null));
    // Answering after an await: Chrome keeps the channel open only when the listener returns true.
    return true;
  }
  if (message?.type === 'leave' && tabId !== undefined) api.tabs.remove(tabId);
  if (message?.type === 'block' && typeof message.rule === 'string' && tabId !== undefined) {
    const query = new URLSearchParams({ rule: message.rule, from: message.from });
    api.tabs.update(tabId, { url: api.runtime.getURL(`blocked.html?${query}`) });
  }
});
