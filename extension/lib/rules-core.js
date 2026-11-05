// Pure Web-rule logic (docs/rules.md "Web"), shared by the content script, background, extension pages and Node tests.
// Classic script, not a module, so content_scripts and background.scripts can load it as-is.
(() => {
  // docs/rules.md "Revision": a copy is accepted only if it has schemaVersion 1 and a rules array.
  function isValidRulesFile(file) {
    return file !== null && typeof file === 'object' && file.schemaVersion === 1 && Array.isArray(file.rules);
  }

  function rulesForHost(file, host) {
    return file.rules.filter(
      (rule) =>
        rule?.enabled === true &&
        Array.isArray(rule.web?.hosts) &&
        rule.web.hosts.some((h) => host === h || host.endsWith('.' + h)),
    );
  }

  // One malformed pattern must not stop the other rules from running.
  function regex(pattern) {
    try {
      return new RegExp(pattern);
    } catch {
      return null;
    }
  }

  // target = pathname + search. Redirects across all rules run before any block.
  function decide(rules, target) {
    for (const rule of rules) {
      for (const { from, to } of rule.web.redirect ?? []) {
        const re = regex(from);
        if (!re?.test(target)) continue;
        const next = target.replace(re, to);
        if (next !== target) return { action: 'redirect', rule, target: next };
      }
    }
    for (const rule of rules) {
      for (const pattern of rule.web.block ?? []) {
        if (regex(pattern)?.test(target)) return { action: 'block', rule };
      }
    }
    return null;
  }

  // check(selector) throws for a selector the browser cannot parse; such selectors are dropped.
  function validSelectors(selectors, check) {
    return [...new Set(selectors)].filter((selector) => {
      try {
        check(selector);
        return true;
      } catch {
        return false;
      }
    });
  }

  function hideSelectors(rules, check) {
    return validSelectors(
      rules.flatMap((rule) => rule.web.hide ?? []),
      check,
    );
  }

  // docs/rules.md "YouTube channels".
  const CHANNEL_ID = /^UC[A-Za-z0-9_-]{22}$/;
  // Blocks added in the browser that no rules file contains yet carry this rule on the blocked page and in counts.
  // Same id and text as the rule `grod channel add` writes, so the page reads the same before and after sync.
  const pendingRule = {
    id: 'youtube-channels',
    title: 'Blocked YouTube channels',
    why: 'You blocked this channel. Everything else on YouTube stays.',
  };
  // Every tile that shows one video, Short or channel, desktop and m.youtube.com. Containers of many tiles stay out.
  const channelTiles = [
    'ytd-rich-item-renderer',
    'ytd-video-renderer',
    'ytd-grid-video-renderer',
    'ytd-compact-video-renderer',
    'ytd-playlist-video-renderer',
    'ytd-channel-renderer',
    'ytd-grid-channel-renderer',
    'ytd-reel-item-renderer',
    'yt-lockup-view-model',
    'ytm-shorts-lockup-view-model',
    'ytm-shorts-lockup-view-model-v2',
    'ytm-rich-item-renderer',
    'ytm-video-with-context-renderer',
    'ytm-compact-video-renderer',
    'ytm-compact-channel-renderer',
    'ytm-reel-item-renderer',
    'ytm-media-item',
  ];

  function isYouTube(host) {
    return host === 'youtube.com' || host.endsWith('.youtube.com');
  }

  // YouTube links use NFC; a handle written on macOS can arrive decomposed (C + combining cedilla for Ç).
  function decode(text) {
    try {
      return decodeURIComponent(text).normalize('NFC');
    } catch {
      return text.normalize('NFC');
    }
  }

  // YouTube links handles percent-encoded (/@%C3%87in…) and in any case; one key covers every spelling.
  function handleKey(handle) {
    return decode(handle).toLowerCase();
  }

  function isChannel(channel) {
    return (
      typeof channel?.name === 'string' &&
      channel.name.length > 0 &&
      /^@[^\s/?#]+$/.test(channel.handle ?? '') &&
      (channel.id === undefined || CHANNEL_ID.test(channel.id))
    );
  }

  // Channels of every enabled rule plus pending ones, each with the rule that blocks it, one entry per handle.
  function youtubeChannels(file, pending = []) {
    const channels = new Map();
    const add = (channel, rule) => {
      const key = isChannel(channel) && handleKey(channel.handle);
      if (key && !channels.has(key)) channels.set(key, { ...channel, rule });
    };
    for (const rule of file?.rules ?? []) {
      if (rule?.enabled !== true || !Array.isArray(rule.youtubeChannels)) continue;
      for (const channel of rule.youtubeChannels) add(channel, rule);
    }
    for (const channel of pending) add(channel, pendingRule);
    return [...channels.values()];
  }

  function cssString(text) {
    return `"${text.replace(/["\\]/g, '\\$&')}"`;
  }

  // One selector per tile type with every channel link inside its :has(), so the stylesheet stays the same size
  // as channels are added. The `i` flag only folds ASCII case, so both the raw and the encoded handle are listed.
  function channelSelectors(channels) {
    if (!channels.length) return [];
    const links = [];
    for (const channel of channels) {
      const handle = decode(channel.handle);
      for (const form of new Set([handle, '@' + encodeURIComponent(handle.slice(1))])) {
        links.push(`a[href=${cssString('/' + form)} i]`, `a[href^=${cssString('/' + form + '/')} i]`);
      }
      if (channel.id) {
        links.push(`a[href=${cssString('/channel/' + channel.id)}]`, `a[href^=${cssString('/channel/' + channel.id + '/')}]`);
      }
    }
    const has = links.join(', ');
    return channelTiles.map((tile) => `${tile}:has(${has})`);
  }

  // The blocked channel whose page `path` is (/@handle, /channel/<id>, or anything below them), else null.
  function channelForPath(channels, path) {
    const [, first = '', second] = path.split('/');
    if (first.startsWith('@')) {
      const key = handleKey(first);
      return channels.find((c) => handleKey(c.handle) === key) ?? null;
    }
    if (first === 'channel' && second) return channels.find((c) => c.id === second) ?? null;
    return null;
  }

  // links: [{href, text}] from a tile, the clicked link first. The first handle found is the channel;
  // its shortest link text is the display name (avatar links have none, title links hold more than the name).
  function channelFromLinks(links) {
    let handle = null;
    let id;
    let name = '';
    for (const { href, text } of links) {
      const [, first = '', second] = new URL(href, 'https://www.youtube.com').pathname.split('/');
      const label = (text ?? '').replace(/\s+/g, ' ').trim();
      if (first === 'channel' && CHANNEL_ID.test(second)) id ??= second;
      if (!first.startsWith('@')) continue;
      const found = decode(first);
      handle ??= found;
      if (found === handle && label && (!name || label.length < name.length)) name = label;
    }
    if (!handle) return null;
    const channel = { name: name || handle.slice(1), handle };
    if (id) channel.id = id;
    return isChannel(channel) ? channel : null;
  }

  // One CSS rule per selector: a selector the browser rejects only drops its own rule.
  function css(selectors) {
    return selectors.map((s) => `${s} { display: none !important; }`).join('\n');
  }

  globalThis.grodCore = {
    isValidRulesFile,
    rulesForHost,
    decide,
    validSelectors,
    hideSelectors,
    css,
    pendingRule,
    channelTiles,
    isYouTube,
    handleKey,
    isChannel,
    youtubeChannels,
    channelSelectors,
    channelForPath,
    channelFromLinks,
  };
})();
