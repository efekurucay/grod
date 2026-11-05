const api = globalThis.browser ?? globalThis.chrome;
const core = globalThis.grodCore;
const {
  rules: stored,
  fetchedAt,
  counts = {},
  pendingChannels = [],
} = await api.storage.local.get(['rules', 'fetchedAt', 'counts', 'pendingChannels']);
const [tab] = await api.tabs.query({ active: true, currentWindow: true });

const text = (id, value) => (document.getElementById(id).textContent = value);

if (stored) {
  text('revision', stored.revision);
  text('source', stored.source);
}
if (fetchedAt) text('fetched', new Date(fetchedAt).toLocaleString());

// tab.url is only visible for hosts the extension may access, which <all_urls> covers.
if (stored && tab?.url) {
  const ids = core.rulesForHost(stored.file, new URL(tab.url).hostname).map((r) => r.id);
  if (ids.length) text('tab', ids.join(', '));
}

const list = document.getElementById('rules');
for (const rule of stored?.file.rules ?? []) {
  const item = document.createElement('li');
  item.className = rule.enabled ? '' : 'off';
  item.title = rule.id;
  const dot = document.createElement('span');
  dot.className = 'dot';
  const title = document.createElement('span');
  title.className = 'title';
  title.textContent = rule.enabled ? rule.title : `${rule.title} (off)`;
  const count = document.createElement('span');
  count.className = 'count';
  count.textContent = String(counts[rule.id] ?? 0);
  item.append(dot, title, count);
  list.append(item);
}

const channels = core.youtubeChannels(stored?.file, pendingChannels);
document.getElementById('channels-section').hidden = !channels.length;
for (const channel of channels) {
  const item = document.createElement('li');
  item.title = channel.id ?? channel.handle;
  const name = document.createElement('span');
  name.className = 'title';
  name.textContent = channel.name;
  const handle = document.createElement('span');
  handle.className = 'count';
  // Pending: applied here, not yet in the rules file the phone gets.
  handle.textContent = channel.rule === core.pendingRule ? `${channel.handle} · pending` : channel.handle;
  item.append(name, handle);
  document.getElementById('channels').append(item);
}
