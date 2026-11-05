// Runs on every page at document_start. Pages whose host has no enabled rule stop after one storage read.
(() => {
  const api = globalThis.browser ?? globalThis.chrome;
  const core = globalThis.grodCore;
  const youtube = core.isYouTube(location.hostname);
  // Parsing a selector against an empty fragment validates it without scanning the page.
  const probe = document.createDocumentFragment();
  const check = (selector) => probe.querySelector(selector);
  const TILE = core.channelTiles.join(', ');
  const OWNER_LINKS = '#owner a[href], ytm-slim-owner-renderer a[href]';
  // Logged-out users get the Polymer listbox, logged-in users the view-model list.
  const MENU_LISTS = 'ytd-popup-container tp-yt-paper-listbox, ytd-popup-container yt-list-view-model';
  const MENU_CSS = `.grod-menu-item { display: flex; align-items: center; min-height: 36px; padding: 0 16px;
  font: 400 14px/20px Roboto, Arial, sans-serif; color: var(--yt-spec-text-primary, inherit); cursor: pointer; }
.grod-menu-item:hover, .grod-menu-item:focus { background: var(--yt-spec-10-percent-layer, rgba(128, 128, 128, 0.15)); outline: none; }`;

  let stored = null;
  let pending = [];
  let rules = [];
  let channels = [];
  let hiddenList = '';
  let style = null;
  let lastHref = '';
  let watching = false;
  // The owner link renders after a watch page loads, so it is checked until this time after each navigation.
  let ownerUntil = 0;

  function send(message) {
    // A script orphaned by an extension reload has no runtime left; the next page load gets a fresh one.
    Promise.resolve()
      .then(() => api.runtime.sendMessage(message))
      .catch(() => {});
  }

  function apply() {
    rules = stored ? core.rulesForHost(stored.file, location.hostname) : [];
    channels = youtube ? core.youtubeChannels(stored?.file, pending) : [];
    const selectors = [
      ...core.hideSelectors(rules, check),
      ...core.validSelectors(core.channelSelectors(channels), check),
    ];
    hiddenList = selectors.join(',');
    if (selectors.length || style || youtube) {
      style ??= document.createElement('style');
      style.textContent = core.css(selectors) + (youtube ? '\n' + MENU_CSS : '');
      if (!style.isConnected) document.documentElement.append(style);
    }
    if (!rules.length && !channels.length) return;
    if (!watching) watch();
    lastHref = '';
    tick();
  }

  // display:none does not stop playback, so audio of a hidden reel would keep going.
  function pauseIfHidden(video) {
    if (hiddenList && !video.paused && video.closest(hiddenList)) video.pause();
  }

  function block(rule) {
    ownerUntil = 0;
    send({ type: 'block', rule: rule.id, from: location.href });
  }

  function route() {
    const decision = core.decide(rules, location.pathname + location.search);
    if (decision?.action === 'redirect') {
      send({ type: 'fired', rule: decision.rule.id });
      location.replace(location.origin + decision.target);
      return;
    }
    if (decision) return block(decision.rule);
    const channel = core.channelForPath(channels, location.pathname);
    if (channel) return block(channel.rule);
    ownerUntil = location.pathname === '/watch' && channels.length ? Date.now() + 8000 : 0;
  }

  function checkOwner() {
    for (const link of document.querySelectorAll(OWNER_LINKS)) {
      const channel = core.channelForPath(channels, link.pathname);
      if (channel) return block(channel.rule);
    }
  }

  function tick() {
    for (const video of document.getElementsByTagName('video')) pauseIfHidden(video);
    if (ownerUntil > Date.now()) checkOwner();
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

  // The clicked link first, so right-clicking a channel name picks that channel over the tile's others.
  function channelOf(element) {
    const link = element.closest('a[href]');
    const tileLinks = element.closest(TILE)?.querySelectorAll('a[href]') ?? [];
    const links = [...(link ? [link] : []), ...tileLinks];
    return core.channelFromLinks(links.map((a) => ({ href: a.getAttribute('href'), text: a.textContent })));
  }

  function closeMenu() {
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', keyCode: 27, bubbles: true }));
  }

  function menuItem(channel) {
    const item = document.createElement('div');
    item.className = 'grod-menu-item';
    item.setAttribute('role', 'menuitem');
    item.tabIndex = 0;
    item.textContent = document.documentElement.lang.startsWith('tr') ? 'grod: Kanalı engelle' : 'grod: Block channel';
    item.addEventListener('click', (event) => {
      event.stopPropagation();
      item.remove();
      send({ type: 'channel', channel });
      closeMenu();
    });
    item.addEventListener('keydown', (event) => {
      if (event.key === 'Enter') item.click();
    });
    return item;
  }

  // YouTube opens and fills the popup after the click and may re-render it, so the item is (re)placed for a short while.
  // It goes before the list, not inside it: the list treats its children as YouTube menu items.
  let offerTimer = 0;
  function offerMenuItem(channel) {
    let tries = 0;
    offerTimer = setInterval(() => {
      const list = [...document.querySelectorAll(MENU_LISTS)].find((el) => el.checkVisibility());
      if (list && !list.parentElement.querySelector(':scope > .grod-menu-item')) list.before(menuItem(channel));
      if (++tries === 20) clearInterval(offerTimer);
    }, 100);
  }

  function setupChannelBlocking() {
    let contextChannel = null;
    document.addEventListener(
      'pointerdown',
      (event) => {
        if (event.target.closest('.grod-menu-item')) return;
        clearInterval(offerTimer);
        for (const item of document.querySelectorAll('.grod-menu-item')) item.remove();
        const tile = event.target.closest('button, yt-icon-button, [role="button"]')?.closest(TILE);
        const channel = tile && channelOf(tile);
        if (channel) offerMenuItem(channel);
      },
      true,
    );
    document.addEventListener('contextmenu', (event) => (contextChannel = channelOf(event.target)), true);
    // The background's context menu click asks which channel the right-clicked element belongs to.
    api.runtime.onMessage.addListener((message, _sender, respond) => {
      if (message?.type === 'context-channel') respond(contextChannel);
    });
  }

  if (youtube) setupChannelBlocking();
  api.storage.local.get(['rules', 'pendingChannels']).then((items) => {
    stored = items.rules ?? null;
    pending = items.pendingChannels ?? [];
    apply();
  });
  api.storage.onChanged.addListener((changes, area) => {
    if (area !== 'local' || !(changes.rules || changes.pendingChannels)) return;
    if (changes.rules) stored = changes.rules.newValue ?? null;
    if (changes.pendingChannels) pending = changes.pendingChannels.newValue ?? [];
    apply();
  });
})();
