// Chrome runs this file as a service worker; Firefox runs background.scripts, which loads rules-core first.
if (!globalThis.grodCore) importScripts('lib/rules-core.js');

const api = globalThis.browser ?? globalThis.chrome;
const core = globalThis.grodCore;
const LOCALHOST_URL = 'http://127.0.0.1:47770/rules.json';
const CHANNELS_URL = 'http://127.0.0.1:47770/youtube-channels';
const MENU_ID = 'grod-block-channel';

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

// The macOS app writes the channel into the rules file (`grod channel add`), which reaches every platform.
async function postChannel(channel) {
  try {
    await fetch(CHANNELS_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Grod': '1' },
      body: JSON.stringify(channel),
    });
  } catch {
    // The app is not running; refresh() sends the channel again once it is.
  }
}

// Pending channels apply at once through storage; each is dropped when a rules file lists its handle.
function blockChannel(channel) {
  if (!core.isChannel(channel)) return;
  const entry = { name: channel.name, handle: channel.handle, ...(channel.id && { id: channel.id }) };
  return serial(async () => {
    const { rules, pendingChannels = [] } = await api.storage.local.get(['rules', 'pendingChannels']);
    const key = core.handleKey(entry.handle);
    if (core.youtubeChannels(rules?.file, pendingChannels).some((c) => core.handleKey(c.handle) === key)) return;
    await api.storage.local.set({ pendingChannels: [...pendingChannels, entry] });
    postChannel(entry);
  });
}

function prunePending(file) {
  const listed = new Set(core.youtubeChannels(file).map((c) => core.handleKey(c.handle)));
  return serial(async () => {
    const { pendingChannels = [] } = await api.storage.local.get('pendingChannels');
    const left = pendingChannels.filter((c) => !listed.has(core.handleKey(c.handle)));
    if (left.length !== pendingChannels.length) await api.storage.local.set({ pendingChannels: left });
    return left;
  });
}

// Writes only on change: every open tab listens to the `rules` key.
async function save(file, source) {
  const { rules } = await api.storage.local.get('rules');
  if (rules?.source !== source || JSON.stringify(rules.file) !== JSON.stringify(file)) {
    await api.storage.local.set({ rules: { file, revision: String(file.revision ?? 'unknown'), source } });
  }
  return prunePending(file);
}

// A failed fetch keeps whatever is stored.
async function refresh() {
  const file = await fetchRules(LOCALHOST_URL);
  if (!file) return false;
  const left = await save(file, 'localhost');
  for (const channel of left) postChannel(channel);
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

function countFire(ruleId) {
  serial(async () => {
    const { counts = {} } = await api.storage.local.get('counts');
    counts[ruleId] = (counts[ruleId] ?? 0) + 1;
    await api.storage.local.set({ counts });
  });
}

// Menus outlive the background, so they are created once per install or update.
async function createMenu() {
  await api.contextMenus.removeAll();
  api.contextMenus.create({
    id: MENU_ID,
    title: 'grod: Block channel',
    contexts: ['link'],
    documentUrlPatterns: ['*://*.youtube.com/*'],
  });
}

api.runtime.onInstalled.addListener(({ reason }) => {
  createMenu();
  load(reason === 'install' || reason === 'update');
});
api.runtime.onStartup.addListener(() => load(false));
api.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === 'refresh') refresh();
});

// Only the content script knows which tile or link was right-clicked.
api.contextMenus.onClicked.addListener(async (info, tab) => {
  if (info.menuItemId !== MENU_ID || !tab?.id) return;
  const channel = await api.tabs.sendMessage(tab.id, { type: 'context-channel' }).catch(() => null);
  if (channel) blockChannel(channel);
});

// Content scripts cannot open extension pages in every browser, so the background navigates the tab.
api.runtime.onMessage.addListener((message, sender) => {
  if (message?.type === 'channel') return void blockChannel(message.channel);
  if (typeof message?.rule !== 'string') return;
  countFire(message.rule);
  if (message.type === 'block' && sender.tab) {
    const query = new URLSearchParams({ rule: message.rule, from: message.from });
    api.tabs.update(sender.tab.id, { url: api.runtime.getURL(`blocked.html?${query}`) });
  }
});
