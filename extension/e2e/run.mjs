import puppeteer from 'puppeteer-core';
// Real-browser check of the Web rules and the scroll pause. Needs Chrome for Testing: branded Chrome ignores --load-extension.
// npx @puppeteer/browsers install chrome@stable, then CHROME=<binary path> npm run e2e
const EXT = new URL('..', import.meta.url).pathname;
const exe = process.env.CHROME;
if (!exe) throw new Error('Set CHROME to a Chrome for Testing binary.');
const browser = await puppeteer.launch({
  executablePath: exe, headless: true, pipe: true,
  enableExtensions: [EXT],
  // Plain-http requests, loopback included, go to a dead proxy: the extension cannot reach a running grod macOS app,
  // so it never reads the real rules over the test rules below. YouTube and the others are https and unaffected.
  args: ['--no-first-run', '--lang=en-US', '--proxy-server=http=127.0.0.1:9', '--proxy-bypass-list=<-loopback>'],
});
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const page = await browser.newPage();
const results = [];
const check = (name, ok, detail) => results.push(`${ok ? 'PASS' : 'FAIL'} ${name} ${detail ?? ''}`);
await sleep(1500); // let background store bundled rules
const extensionId = new URL((await browser.waitForTarget((t) => t.type() === 'service_worker')).url()).host;

// Runs fn in an extension page, where chrome.storage (session included) is reachable.
async function inExtension(fn, ...args) {
  const ext = await browser.newPage();
  await ext.goto(`chrome-extension://${extensionId}/popup.html`);
  const value = await ext.evaluate(fn, ...args);
  await ext.close();
  return value;
}

// 1. hard load of a Short redirects to watch
await page.goto('https://www.youtube.com/shorts/aqz-KE-bpKQ?si=abc', { waitUntil: 'domcontentloaded' });
await sleep(2500);
check('youtube shorts load -> watch', page.url().includes('/watch?v=aqz-KE-bpKQ'), page.url());

// 2. SPA navigation to a Short is caught
await page.goto('https://www.youtube.com/', { waitUntil: 'domcontentloaded' });
await sleep(1500);
await page.evaluate(() => history.pushState({}, '', '/shorts/aqz-KE-bpKQ'));
await sleep(2500);
check('youtube SPA shorts -> watch', page.url().includes('/watch?v=aqz-KE-bpKQ'), page.url());

// 3. Exercise Instagram routes and DOM with same-origin fixtures, without a login or live feed.
const instagram = await browser.newPage();
await instagram.setRequestInterception(true);
const instagramHtml = `<!doctype html><title>Instagram fixture</title>
<main>
  <form role="search"><input aria-label="Search"><a id="search-result" href="/p/FOUND/">Result</a></form>
  <div role="grid"><a href="/p/TILE/">Post tile</a></div>
  <a id="reel-tile" href="/example/reel/TILE/">Reel tile</a>
  <a id="dm" href="/direct/inbox/">Messages</a>
  <video controls></video>
</main>`;
instagram.on('request', (req) => {
  if (new URL(req.url()).hostname === 'www.instagram.com') {
    req.respond({ status: 200, contentType: 'text/html', body: instagramHtml });
  } else req.continue();
});
const go = (path) => instagram.evaluate((target) => history.pushState({}, '', target), path);
const waitForExplore = (enabled) => instagram.waitForFunction(
  (active) => document.documentElement.hasAttribute('data-grod-instagram-explore') === active, {}, enabled,
);
const visible = (selector) => instagram.$eval(selector, (element) => element.checkVisibility());
await instagram.goto('https://www.instagram.com/explore/');
await waitForExplore(true);
check('instagram Explore grid and media tile hidden', !(await visible('[role=grid]')) && !(await visible('#reel-tile')));
check('instagram Explore search and navigation stay visible', await visible('input') && await visible('#search-result') && await visible('#dm'));
await go('/explore/search/keyword/?q=test');
await waitForExplore(false);
check('instagram search results remain visible', await visible('[role=grid]'));
await go('/direct/t/123/');
await go('/reel/ABC123/');
check('instagram shared Reel stays playable', await instagram.$eval('video', async (video) => {
  const canvas = document.createElement('canvas');
  canvas.getContext('2d').fillRect(0, 0, 1, 1);
  video.muted = true;
  video.srcObject = canvas.captureStream();
  await video.play();
  return !video.paused && video.controls;
}));
await go('/reel/ABC123/?shared=1');
await instagram.evaluate(() => history.replaceState({}, '', '/reel/NEXT/'));
check('instagram automatic advance cannot change the Reel', instagram.url().endsWith('/reel/ABC123/?shared=1'), instagram.url());
await go('/reel/NEXT/');
check('instagram paging cannot change the Reel', instagram.url().endsWith('/reel/ABC123/?shared=1'));
await instagram.evaluate(() => {
  const next = document.createElement('a');
  next.href = '/example/reel/NEXT/';
  document.body.append(next);
  next.click();
});
await sleep(500);
check('instagram next Reel link stays on the shared Reel', new URL(instagram.url()).pathname === '/reel/ABC123/');
await instagram.evaluate(() => history.go(-2));
await instagram.waitForFunction(() => location.pathname === '/direct/t/123/');
await go('/reel/NEW/');
check('instagram opens a different Reel from a chat', instagram.url().endsWith('/reel/NEW/'));
await go('/explore/');
await waitForExplore(true);
await inExtension(async () => {
  const { rules } = await chrome.storage.local.get('rules');
  rules.file.rules.find(({ id }) => id === 'instagram').enabled = false;
  await chrome.storage.local.set({ rules });
});
await waitForExplore(false);
check('disabling Instagram restores the Explore grid', await visible('[role=grid]'));
await go('/reel/ONE/');
await go('/reel/TWO/');
check('disabling Instagram restores Reel navigation', instagram.url().endsWith('/reel/TWO/'));
await inExtension(async () => {
  const { rules } = await chrome.storage.local.get('rules');
  rules.file.rules.find(({ id }) => id === 'instagram').enabled = true;
  await chrome.storage.local.set({ rules });
});
await go('/explore/');
await waitForExplore(true);
await go('/p/ABC123/');
await waitForExplore(false);
check('instagram /p/ allowed', instagram.url().includes('instagram.com/p/ABC123'), instagram.url());
await go('/reels/');
await instagram.waitForFunction(() => location.protocol === 'chrome-extension:');
check('instagram SPA /reels/ blocked', instagram.url().includes('rule=instagram'), instagram.url());
const text = await instagram.evaluate(() => document.body.innerText);
check('blocked page shows rule', text.includes('Instagram'), JSON.stringify(text.slice(0, 120)));
await instagram.close();

// 4. TikTok blocked
await page.goto('https://www.tiktok.com/foryou', { waitUntil: 'domcontentloaded' }).catch(() => {});
await sleep(3000);
check('tiktok blocked', page.url().includes('rule=tiktok'), page.url());
await page.close();

// 5. Scroll pause on a test page. Requests to grod-e2e.test are answered here, so nothing leaves the machine.
// Only the test rule applies and the session starts empty, so earlier pages count nothing.
await inExtension(async () => {
  const { rules } = await chrome.storage.local.get('rules');
  rules.file.rules = [{ id: 'e2e-feed', title: 'E2E feed', why: 'Test feed for the scroll pause.', enabled: true, web: { hosts: ['grod-e2e.test'] } }];
  rules.file.scroll = { freeSeconds: 3, firstPauseSeconds: 2, growth: 2, maxPauseSeconds: 300, resetMinutes: 15 };
  await chrome.storage.local.set({ rules, pauses: {} });
  await chrome.storage.session.clear();
});
// An inner scroller (feeds often scroll an element, not the window), a playing video, and a tall window.
const FEED_HTML = `<!doctype html><title>feed</title><body style="margin:0">
<div id="feed" style="height:300px;overflow:auto"><div style="height:100000px"></div></div>
<video id="video" muted></video><div style="height:100000px"></div>
<script>const c = document.createElement('canvas'); const g = c.getContext('2d');
setInterval(() => { g.fillStyle = '#' + Math.floor(Math.random() * 4096).toString(16).padStart(3, '0'); g.fillRect(0, 0, 9, 9); }, 50);
const v = document.getElementById('video'); v.srcObject = c.captureStream(20); v.play();</script>`;
const feed = await browser.newPage();
await feed.setRequestInterception(true);
feed.on('request', (req) => {
  if (new URL(req.url()).hostname.endsWith('grod-e2e.test')) req.respond({ status: 200, contentType: 'text/html', body: FEED_HTML });
  else req.continue();
});
await feed.goto('https://www.grod-e2e.test/', { waitUntil: 'domcontentloaded' });
await feed.bringToFront();
await sleep(500);
// Scrolls `selector` (or the window) by 40 px every 50 ms for `ms`.
const scrollFor = (selector, ms) =>
  feed.evaluate(
    (sel, ms) =>
      new Promise((done) => {
        const el = sel && document.querySelector(sel);
        const timer = setInterval(() => (el ? el.scrollBy(0, 40) : scrollBy(0, 40)), 50);
        setTimeout(() => (clearInterval(timer), done()), ms);
      }),
    selector,
    ms,
  );
const pauseFrame = () => feed.waitForFrame((f) => f.url().includes('/pause.html'), { timeout: 5000 }).catch(() => null);
const overlays = () => feed.evaluate(() => document.querySelectorAll('[popover]').length);
const state = () => inExtension(async () => ({ ...(await chrome.storage.local.get('pauses')), ...(await chrome.storage.session.get('session')) }));

check('video plays before the pause', !(await feed.evaluate(() => document.getElementById('video').paused)));
await scrollFor('#feed', 1500);
check('no pause before freeSeconds', (await overlays()) === 0);
// Keeps scrolling well past the pause: those events must not count.
await scrollFor('#feed', 3000);
let frame = await pauseFrame();
check('pause screen after freeSeconds of element scrolling', Boolean(frame));
const pauseText = (await frame?.evaluate(() => document.body.innerText)) ?? '';
check('pause shows rule, why and session time', ['E2E feed', 'Test feed for the scroll pause.', 'under a minute', 'Leave'].every((t) => pauseText.includes(t)), JSON.stringify(pauseText));
check('Continue hidden during countdown', await frame?.$eval('#continue', (b) => !b.checkVisibility()));
check('overlay covers the page', await feed.evaluate(() => document.elementFromPoint(innerWidth / 2, innerHeight / 2)?.hasAttribute('popover')));
check('video paused by the pause', await feed.evaluate(() => document.getElementById('video').paused));
await feed.evaluate(() => document.getElementById('video').play().catch(() => {}));
await sleep(300);
check('video stays paused', await feed.evaluate(() => document.getElementById('video').paused));
let stored = await state();
check('pause counted for the rule', stored.pauses?.['e2e-feed'] === 1, JSON.stringify(stored.pauses));
check('scrolling during the pause is ignored', stored.session?.scrolled === 0, JSON.stringify(stored.session));
await feed.bringToFront();
const cont = await frame?.waitForSelector('#continue', { visible: true, timeout: 4000 }).catch(() => null);
check('Continue appears when the countdown ends', Boolean(cont));
await cont?.click();
await sleep(500);
check('Continue removes the pause screen', (await overlays()) === 0);

// Second pause: window scrolling counts too, and the wait doubles. The first scroll after the pause adds the 5 s cap,
// more than freeSeconds here, so the pause comes at once and the countdown is read while scrolling goes on.
const scrolling = scrollFor(null, 4000);
frame = await pauseFrame();
check('second pause after window scrolling', Boolean(frame));
const countdown = (await frame?.$eval('#countdown', (el) => el.textContent)) ?? '';
await scrolling;
check('second pause waits twice as long', ['0:04', '0:03'].includes(countdown), countdown);
const closed = new Promise((resolve) => feed.once('close', () => resolve(true)));
await frame?.click('#leave');
check('Leave closes the tab', await Promise.race([closed, sleep(3000).then(() => false)]));
stored = await state();
check('second pause counted', stored.pauses?.['e2e-feed'] === 2, JSON.stringify(stored.pauses));

console.log(results.join('\n'));
await browser.close();
if (results.some((r) => r.startsWith('FAIL'))) process.exitCode = 1;
