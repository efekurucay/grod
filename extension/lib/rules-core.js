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
  function hideSelectors(rules, check) {
    const selectors = [];
    for (const rule of rules) {
      for (const selector of rule.web.hide ?? []) {
        if (selectors.includes(selector)) continue;
        try {
          check(selector);
          selectors.push(selector);
        } catch {
          continue;
        }
      }
    }
    return selectors;
  }

  // One CSS rule per selector: a selector the browser rejects only drops its own rule.
  function css(selectors) {
    return selectors.map((s) => `${s} { display: none !important; }`).join('\n');
  }

  globalThis.grodCore = { isValidRulesFile, rulesForHost, decide, hideSelectors, css };
})();
