const api = globalThis.browser ?? globalThis.chrome;
const core = globalThis.grodCore;
const params = new URLSearchParams(location.search);
const { rules: stored } = await api.storage.local.get('rules');
const rule = stored?.file.rules.find((r) => r.id === params.get('rule'));

if (rule) {
  document.getElementById('title').textContent = rule.title;
  document.getElementById('why').textContent = rule.why ?? '';
  document.title = `${rule.title} · grod`;
}

// history.back() would land on the blocked URL and block again, so go to the site root unless that is blocked too.
document.getElementById('back').addEventListener('click', () => {
  let from;
  try {
    from = new URL(params.get('from'));
  } catch {
    history.back();
    return;
  }
  const hostRules = stored ? core.rulesForHost(stored.file, from.hostname) : [];
  const rootBlocked = core.decide(hostRules, '/')?.action === 'block';
  location.replace(rootBlocked ? 'about:blank' : `${from.origin}/`);
});
