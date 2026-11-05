const api = globalThis.browser ?? globalThis.chrome;
const core = globalThis.grodCore;
const { rules: stored, fetchedAt, counts = {} } = await api.storage.local.get(['rules', 'fetchedAt', 'counts']);
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
