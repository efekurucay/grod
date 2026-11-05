import puppeteer from 'puppeteer-core';
// Real-browser check of the Web rules. Needs Chrome for Testing: branded Chrome ignores --load-extension.
// npx @puppeteer/browsers install chrome@stable, then CHROME=<binary path> npm run e2e
const EXT = new URL('..', import.meta.url).pathname;
const exe = process.env.CHROME;
if (!exe) throw new Error('Set CHROME to a Chrome for Testing binary.');
const browser = await puppeteer.launch({
  executablePath: exe, headless: true, pipe: true,
  enableExtensions: [EXT],
  // Plain-http requests, loopback included, go to a dead proxy: the extension cannot reach a running grod macOS app,
  // so the channel test below cannot write into the real rules file. YouTube and the others are https and unaffected.
  args: ['--no-first-run', '--lang=en-US', '--proxy-server=http=127.0.0.1:9', '--proxy-bypass-list=<-loopback>'],
});
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const page = await browser.newPage();
const results = [];
const check = (name, ok, detail) => results.push(`${ok ? 'PASS' : 'FAIL'} ${name} ${detail ?? ''}`);
await sleep(1500); // let background store bundled rules
const extensionId = new URL((await browser.waitForTarget((t) => t.type() === 'service_worker')).url()).host;

// 1. hard load of a Short redirects to watch
await page.goto('https://www.youtube.com/shorts/aqz-KE-bpKQ?si=abc', { waitUntil: 'domcontentloaded' });
await sleep(2500);
check('youtube shorts load -> watch', page.url().includes('/watch?v=aqz-KE-bpKQ'), page.url());

// 2. hide style injected on youtube
const css = await page.evaluate(() => [...document.querySelectorAll('style')].map((s) => s.textContent).find((t) => t.includes('ytd-reel-shelf-renderer')) ?? '');
check('youtube hide css injected', css.split('\n').length >= 10, `${css.split('\n').length} rules`);

// 2b. Shorts shelves in search results are hidden, not just styled
await page.goto('https://www.youtube.com/results?search_query=cat+funny', { waitUntil: 'networkidle2' }).catch(() => {});
await sleep(2000);
await page.evaluate(() => window.scrollBy(0, 3000));
await sleep(2000);
const shortsLinks = await page.evaluate(() => [...document.querySelectorAll('a[href*="/shorts/"]')].filter((a) => a.getClientRects().length).length);
check('youtube search shows no Shorts', shortsLinks === 0, `${shortsLinks} visible`);

// 3. SPA navigation to a Short is caught
await page.goto('https://www.youtube.com/', { waitUntil: 'domcontentloaded' });
await sleep(1500);
await page.evaluate(() => history.pushState({}, '', '/shorts/aqz-KE-bpKQ'));
await sleep(2500);
check('youtube SPA shorts -> watch', page.url().includes('/watch?v=aqz-KE-bpKQ'), page.url());

// 4. Instagram SPA to /reels/ shows blocked page; /p/ stays
await page.goto('https://www.instagram.com/', { waitUntil: 'domcontentloaded' });
await sleep(1500);
await page.evaluate(() => history.pushState({}, '', '/p/ABC123/'));
await sleep(1200);
check('instagram /p/ allowed', page.url().includes('instagram.com/p/ABC123'), page.url());
await page.evaluate(() => history.pushState({}, '', '/reels/'));
await sleep(2500);
check('instagram SPA /reels/ blocked', page.url().startsWith('chrome-extension://') && page.url().includes('rule=instagram-reels'), page.url());
const text = await page.evaluate(() => document.body.innerText);
check('blocked page shows rule', text.includes('Instagram Reels'), JSON.stringify(text.slice(0, 120)));

// 5. TikTok blocked
await page.goto('https://www.tiktok.com/foryou', { waitUntil: 'domcontentloaded' }).catch(() => {});
await sleep(3000);
check('tiktok blocked', page.url().includes('rule=tiktok'), page.url());

// 6. Block a channel from a search result's ⋮ menu (logged out). The macOS app is unreachable here,
// so this exercises the pending path: the block must apply before any rules file lists the channel.
const CEM = '/@%C3%87inMal%C4%B1CemY%C4%B1lmaz';
const cemTiles = `:is(${['ytd-video-renderer', 'ytd-channel-renderer', 'yt-lockup-view-model', 'ytd-rich-item-renderer'].join(',')}):has(a[href="${CEM}"])`;
const visibleCount = (selector) => page.$$eval(selector, (els) => els.filter((el) => el.getClientRects().length).length);
// Start with no blocked channels, whatever the bundled rules list.
await page.goto(`chrome-extension://${extensionId}/popup.html`);
await page.evaluate(async () => {
  const { rules } = await chrome.storage.local.get('rules');
  for (const rule of rules.file.rules) delete rule.youtubeChannels;
  await chrome.storage.local.set({ rules, pendingChannels: [] });
});
await page.goto('https://www.youtube.com/results?search_query=cem+yilmaz', { waitUntil: 'networkidle2' }).catch(() => {});
await sleep(2000);
check('channel tiles shown before block', (await visibleCount(cemTiles)) > 0, `${await visibleCount(cemTiles)} tiles`);
const menuButton = await page.$(`ytd-video-renderer:has(a[href="${CEM}"]) button[aria-label="Action menu"]`);
await menuButton?.click();
const item = await page.waitForSelector('.grod-menu-item', { visible: true, timeout: 5000 }).catch(() => null);
check('Block channel item in ⋮ menu', Boolean(item), item ? await item.evaluate((el) => el.textContent) : 'missing');
await item?.click();
await sleep(1500);
check('blocked channel tiles hidden', (await visibleCount(cemTiles)) === 0, `${await visibleCount(cemTiles)} visible`);
await page.goto('https://www.youtube.com/@ÇinMalıCemYılmaz', { waitUntil: 'domcontentloaded' }).catch(() => {});
await sleep(2500);
check('blocked channel page', page.url().includes('rule=youtube-channels'), page.url());
const channelText = await page.evaluate(() => document.body.innerText);
check('blocked page shows channel rule', channelText.includes('Blocked YouTube channels'), JSON.stringify(channelText.slice(0, 120)));
const pending = await page.evaluate(() => chrome.storage.local.get('pendingChannels').then((s) => s.pendingChannels));
check('channel kept as pending', pending?.length === 1 && pending[0].handle === '@ÇinMalıCemYılmaz' && pending[0].name === 'Çin Malı Cem Yılmaz', JSON.stringify(pending));

console.log(results.join('\n'));
await browser.close();
if (results.some((r) => r.startsWith('FAIL'))) process.exitCode = 1;
