// Shown in an iframe by content.js. The query carries the pause from the background: rule id, until (epoch ms), total (ms scrolled).
const api = globalThis.browser ?? globalThis.chrome;
const params = new URLSearchParams(location.search);
const until = Number(params.get('until'));
const total = Number(params.get('total'));
const { rules: stored } = await api.storage.local.get('rules');
const rule = stored?.file.rules.find((r) => r.id === params.get('rule'));

const $ = (id) => document.getElementById(id);
if (rule) {
  $('title').textContent = rule.title;
  $('why').textContent = rule.why ?? '';
}
const minutes = Math.round(total / 60000);
$('scrolled').textContent = minutes < 1 ? 'under a minute' : minutes === 1 ? '1 minute' : `${minutes} minutes`;

// content.js listens only to this frame's window, so these strings are the whole protocol.
$('leave').addEventListener('click', () => parent.postMessage('grod:leave', '*'));
$('continue').addEventListener('click', () => parent.postMessage('grod:continue', '*'));

function update() {
  const left = Math.max(0, Math.ceil((until - Date.now()) / 1000));
  $('countdown').textContent = left ? `${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')}` : 'done';
  if (left) return;
  clearInterval(timer);
  $('continue').hidden = false;
}
const timer = setInterval(update, 250);
update();
