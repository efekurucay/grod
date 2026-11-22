(() => {
  const instagramHost = /^(?:www\.|m\.)?instagram\.com$/;
  const reelPath = /^\/(?:[\w.]+\/)?reels?\/([\w-]+)\/?$/;
  const explorePath = /^\/explore\/?$/;
  const onInstagram = instagramHost.test(location.hostname);
  const navigation = globalThis.navigation;
  let enabled = false;
  let reel = null;

  function wouldAdvance(from, to) {
    const current = reelPath.exec(from.pathname);
    const next = reelPath.exec(to.pathname);
    return from.origin === to.origin && current && next && current[1] !== next[1];
  }

  function stopAdvance(event) {
    if (!enabled || event.navigationType === 'traverse' || event.navigationType === 'reload') return;
    if (wouldAdvance(location, new URL(event.destination.url))) event.preventDefault();
  }

  function resetReel() {
    reel = null;
  }

  function setEnabled(value) {
    if (!onInstagram || enabled === value) return;
    enabled = value;
    if (enabled) navigation?.addEventListener('navigate', stopAdvance);
    else {
      navigation?.removeEventListener('navigate', stopAdvance);
      document.documentElement?.removeAttribute('data-grod-instagram-explore');
      resetReel();
    }
  }

  function update() {
    if (!enabled) return false;
    document.documentElement.toggleAttribute('data-grod-instagram-explore', explorePath.test(location.pathname));
    if (navigation) return false;
    // Firefox versions without the Navigation API can only detect an in-page advance after the URL changes.
    const current = new URL(location.href);
    if (reel && wouldAdvance(reel, current)) {
      location.replace(reel.href);
      return true;
    }
    reel = reelPath.test(current.pathname) ? current : null;
    return false;
  }

  globalThis.grodInstagram = { setEnabled, resetReel, update };
})();
