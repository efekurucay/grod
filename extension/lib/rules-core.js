// Pure rule logic (docs/rules.md "Scroll pause" and "Web links"), shared by the content script, background, extension pages and Node tests.
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

  // docs/rules.md "Scroll pause". Session times are epoch milliseconds.
  const scrollDefaults = { freeSeconds: 60, firstPauseSeconds: 10, growth: 2, maxPauseSeconds: 300, resetMinutes: 15 };
  const MAX_STEP_MS = 5000;

  function scrollSettings(file) {
    const given = file?.scroll ?? {};
    const settings = {};
    for (const [key, fallback] of Object.entries(scrollDefaults)) {
      settings[key] = Number.isFinite(given[key]) && given[key] > 0 ? given[key] : fallback;
    }
    return settings;
  }

  // n = pauses already shown in this session.
  function pauseSeconds(settings, n) {
    return Math.min(settings.firstPauseSeconds * settings.growth ** n, settings.maxPauseSeconds);
  }

  // One scroll event on a host of `rule` at `now`. session: { scrolled, total, pauses, last, pause } or null for none yet.
  // `scrolled` restarts after each pause, `total` is the whole session's scroll time shown on the pause screen.
  // Returns the next session and the pause the scrolling tab must show (null if none); `started` marks a new pause.
  function scrollEvent(session, settings, now, rule) {
    // Every tab that scrolls during a pause shows it too, so a new tab or a reload is no way around it.
    if (session?.pause && now < session.pause.until) return { session, pause: session.pause, started: false };
    const fresh = !session || now - session.last >= settings.resetMinutes * 60000;
    const step = fresh ? 0 : Math.min(now - session.last, MAX_STEP_MS);
    const pauses = fresh ? 0 : session.pauses;
    const scrolled = (fresh ? 0 : session.scrolled) + step;
    const total = (fresh ? 0 : session.total) + step;
    if (scrolled < settings.freeSeconds * 1000) {
      return { session: { scrolled, total, pauses, last: now, pause: null }, pause: null, started: false };
    }
    const pause = { rule, until: now + pauseSeconds(settings, pauses) * 1000, total };
    return { session: { scrolled: 0, total, pauses: pauses + 1, last: now, pause }, pause, started: true };
  }

  globalThis.grodCore = {
    isValidRulesFile,
    rulesForHost,
    decide,
    scrollDefaults,
    scrollSettings,
    pauseSeconds,
    scrollEvent,
  };
})();
