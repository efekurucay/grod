// Runs on every page at document_start. Pages whose host has no enabled rule stop after one storage read.
(() => {
  const api = globalThis.browser ?? globalThis.chrome;
  const core = globalThis.grodCore;
  // Parsing a selector against an empty fragment validates it without scanning the page.
  const probe = document.createDocumentFragment();

  let rules = [];
  let hiddenList = '';
  let style = null;
  let lastHref = '';
  let watching = false;

  function send(message) {
    // A script orphaned by an extension reload has no runtime left; the next page load gets a fresh one.
    Promise.resolve()
      .then(() => api.runtime.sendMessage(message))
      .catch(() => {});
  }

  function apply(stored) {
    rules = stored ? core.rulesForHost(stored.file, location.hostname) : [];
    const selectors = core.hideSelectors(rules, (s) => probe.querySelector(s));
    hiddenList = selectors.join(',');
    if (selectors.length || style) {
      style ??= document.createElement('style');
      style.textContent = core.css(selectors);
      if (!style.isConnected) document.documentElement.append(style);
    }
    if (!rules.length) return;
    if (!watching) watch();
    lastHref = '';
    tick();
  }

  // display:none does not stop playback, so audio of a hidden reel would keep going.
  function pauseIfHidden(video) {
    if (hiddenList && !video.paused && video.closest(hiddenList)) video.pause();
  }

  function route() {
    const decision = core.decide(rules, location.pathname + location.search);
    if (!decision) return;
    if (decision.action === 'redirect') {
      send({ type: 'fired', rule: decision.rule.id });
      location.replace(location.origin + decision.target);
    } else {
      send({ type: 'block', rule: decision.rule.id, from: location.href });
    }
  }

  function tick() {
    for (const video of document.getElementsByTagName('video')) pauseIfHidden(video);
    if (location.href === lastHref) return;
    lastHref = location.href;
    route();
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
    document.addEventListener(
      'play',
      (event) => {
        if (event.target.tagName === 'VIDEO') pauseIfHidden(event.target);
      },
      true,
    );
    // Firefox content scripts may lack the Navigation API, and some SPAs change the URL without any event we see.
    setInterval(tick, 500);
  }

  api.storage.local.get('rules').then(({ rules: stored }) => apply(stored));
  api.storage.onChanged.addListener((changes, area) => {
    if (area === 'local' && changes.rules) apply(changes.rules.newValue);
  });
})();
