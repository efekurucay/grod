import puppeteer from 'puppeteer-core';
// Real-browser check of the Web rules. Needs Chrome for Testing: branded Chrome ignores --load-extension.
// npx @puppeteer/browsers install chrome@stable, then CHROME=<binary path> npm run e2e
const EXT = new URL('..', import.meta.url).pathname;
const exe = process.env.CHROME;
if (!exe) throw new Error('Set CHROME to a Chrome for Testing binary.');
const browser = await puppeteer.launch({
  executablePath: exe, headless: true, pipe: true,
  enableExtensions: [EXT],
  args: ['--no-first-run', '--lang=en-US'],
});
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const page = await browser.newPage();
const results = [];
const check = (name, ok, detail) => results.push(`${ok ? 'PASS' : 'FAIL'} ${name} ${detail ?? ''}`);
await sleep(1500); // let background store bundled rules

// 1. hard load of a Short redirects to watch
await page.goto('https://www.youtube.com/shorts/aqz-KE-bpKQ?si=abc', { waitUntil: 'domcontentloaded' });
await sleep(2500);
check('youtube shorts load -> watch', page.url().includes('/watch?v=aqz-KE-bpKQ'), page.url());

// 2. hide style injected on youtube
const css = await page.evaluate(() => [...document.querySelectorAll('style')].map((s) => s.textContent).find((t) => t.includes('ytd-reel-shelf-renderer')) ?? '');
check('youtube hide css injected', css.split('\n').length >= 10, `${css.split('\n').length} rules`);

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

console.log(results.join('\n'));
await browser.close();
if (results.some((r) => r.startsWith('FAIL'))) process.exitCode = 1;
