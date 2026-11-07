// Runs on every page at document_start. Pages whose host has no enabled rule stop after one storage read.
(() => {
  const api = globalThis.browser ?? globalThis.chrome;
  const core = globalThis.grodCore;
  const PAUSE_URL = api.runtime.getURL('pause.html');
  // Scroll events fire many times a second. One message per interval adds up to the same time, because the
  // background counts the time between messages; at most this much is lost at the end of each burst.
  const SEND_EVERY_MS = 250;
  const OVERLAY_CSS = `:host { all: initial !important; display: block !important; position: fixed !important;
  inset: 0 !important; z-index: 2147483647 !important; margin: 0 !important; padding: 0 !important; border: 0 !important; }
iframe { display: block; width: 100%; height: 100%; margin: 0; border: 0; }`;

  let rules = [];
  let lastHref = '';
  let watching = false;
  let lastSent = 0;
  // { host, frame } while the pause screen shows.
  let overlay = null;

  function send(message) {
    // A script orphaned by an extension reload has no runtime left; the next page load gets a fresh one.
    return Promise.resolve()
      .then(() => api.runtime.sendMessage(message))
      .catch(() => null);
  }

  function apply(stored) {
    rules = stored ? core.rulesForHost(stored.file, location.hostname) : [];
    if (!rules.length) return;
    if (!watching) watch();
    lastHref = '';
    tick();
  }

  function route() {
    const decision = core.decide(rules, location.pathname + location.search);
    if (decision?.action === 'redirect') location.replace(location.origin + decision.target);
    else if (decision) send({ type: 'block', rule: decision.rule.id, from: location.href });
  }

  function tick() {
    // A site that re-renders the whole document can drop the overlay; it goes back until "Continue".
    if (overlay && !overlay.host.isConnected) mount();
    if (location.href === lastHref) return;
    lastHref = location.href;
    route();
  }

  // The pause screen is an extension page in an iframe under a closed shadow root: the site's CSS cannot restyle it,
  // its scripts cannot reach into it, and every click lands on it. The popover top layer keeps it above the site's
  // own stacking contexts; the z-index covers browsers without popover.
  function showPause(pause) {
    const host = document.createElement('div');
    host.setAttribute('popover', 'manual');
    const root = host.attachShadow({ mode: 'closed' });
    const style = document.createElement('style');
    style.textContent = OVERLAY_CSS;
    const frame = document.createElement('iframe');
    frame.src = `${PAUSE_URL}?${new URLSearchParams({ rule: pause.rule, until: pause.until, total: pause.total })}`;
    root.append(style, frame);
    overlay = { host, frame };
    mount();
    for (const video of document.getElementsByTagName('video')) video.pause();
  }

  function mount() {
    document.documentElement.append(overlay.host);
    overlay.host.showPopover?.();
    overlay.frame.focus();
  }

  function onScroll() {
    if (overlay || !rules.length || document.visibilityState !== 'visible') return;
    const now = Date.now();
    if (now - lastSent < SEND_EVERY_MS) return;
    lastSent = now;
    send({ type: 'scroll', rule: rules[0].id }).then((pause) => {
      if (pause && !overlay) showPause(pause);
    });
  }

  // Only the pause frame itself can end the pause; the site cannot post as that window.
  function onFrameMessage(event) {
    if (!overlay || event.source !== overlay.frame.contentWindow) return;
    if (event.data === 'grod:continue') {
      overlay.host.remove();
      overlay = null;
    }
    if (event.data === 'grod:leave') send({ type: 'leave' });
  }

  function watch() {
    watching = true;
    globalThis.navigation?.addEventListener('currententrychange', tick);
    addEventListener('popstate', tick);
    document.addEventListener('yt-navigate-finish', tick);
    // A page restored from the back-forward cache keeps its old lastHref but must be checked again.
    addEventListener('pageshow', (event) => {
      if (!event.persisted) return;
      lastHref = '';
      tick();
    });
    // scroll does not bubble; capturing on document sees the window's scroll and every element's.
    document.addEventListener('scroll', onScroll, { capture: true, passive: true });
    document.addEventListener(
      'play',
      (event) => {
        if (overlay && event.target.tagName === 'VIDEO') event.target.pause();
      },
      true,
    );
    addEventListener('message', onFrameMessage);
    // Firefox content scripts may lack the Navigation API, and some SPAs change the URL without any event we see.
    setInterval(tick, 500);
  }

  api.storage.local.get('rules').then((items) => apply(items.rules ?? null));
  api.storage.onChanged.addListener((changes, area) => {
    if (area === 'local' && changes.rules) apply(changes.rules.newValue ?? null);
  });
})();
