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

// One g-row: title, optional muted line under it, optional muted text at the end.
function row({ title, meta, end, off, tooltip }) {
  const item = document.createElement('li');
  item.className = off ? 'g-row g-row--off' : 'g-row';
  item.title = tooltip;
  const main = document.createElement('span');
  main.className = 'g-row__main';
  const name = document.createElement('span');
  name.className = 'g-row__title';
  name.textContent = title;
  main.append(name);
  if (meta) {
    const line = document.createElement('span');
    line.className = 'g-row__meta';
    line.textContent = meta;
    main.append(line);
  }
  item.append(main);
  if (end) {
    const tail = document.createElement('span');
    tail.className = 'g-row__end';
    tail.textContent = end;
    item.append(tail);
  }
  return item;
}

const list = document.getElementById('rules');
for (const rule of stored?.file.rules ?? []) {
  list.append(
    row({
      title: rule.title,
      meta: rule.enabled ? '' : 'Off',
      end: String(counts[rule.id] ?? 0),
      off: !rule.enabled,
      tooltip: rule.id,
    }),
  );
}

const channels = core.youtubeChannels(stored?.file, pendingChannels);
document.getElementById('channels-section').hidden = !channels.length;
for (const channel of channels) {
  document.getElementById('channels').append(
    row({
      title: channel.name,
      meta: channel.handle,
      // Pending: applied here, not yet in the rules file the phone gets.
      end: channel.rule === core.pendingRule ? 'Pending' : '',
      tooltip: channel.id ?? channel.handle,
    }),
  );
}
