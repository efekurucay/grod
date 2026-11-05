import type { Channel, RulesFile } from './rules.ts';

export const CHANNELS_RULE_ID = 'youtube-channels';

// A full browser UA makes YouTube send response headers larger than Node's fetch accepts.
const UA = 'Mozilla/5.0 (compatible; grod)';

/**
 * "@ÇinMalı…" from "@%C3%87inMal…", so encoded and plain handles compare equal.
 * NFC because YouTube links use it, while macOS hands process arguments over decomposed (NFD).
 */
export function normalizeHandle(handle: string): string {
  let h = handle.trim();
  try {
    h = decodeURIComponent(h);
  } catch {
    // Not percent-encoded: keep as typed.
  }
  h = h.normalize('NFC');
  return h.startsWith('@') ? h : `@${h}`;
}

export function sameHandle(a: string, b: string): boolean {
  return normalizeHandle(a).toLowerCase() === normalizeHandle(b).toLowerCase();
}

type Input = { kind: 'video'; url: string } | { kind: 'channel'; url: string };

/** Accepts what people paste: a handle, a channel URL, or any video/Shorts/youtu.be link. */
export function classify(input: string): Input {
  const text = input.trim();
  if (/^@[^\s/?#]+$/.test(text)) return { kind: 'channel', url: `https://www.youtube.com/${text}` };
  const url = new URL(text.startsWith('http') ? text : `https://${text}`);
  const host = url.hostname.replace(/^(www|m)\./, '');
  if (host === 'youtu.be') return { kind: 'video', url: `https://www.youtube.com/watch?v=${url.pathname.slice(1)}` };
  if (host !== 'youtube.com') throw new Error(`not a YouTube link: ${input}`);
  if (url.pathname === '/watch' || url.pathname.startsWith('/shorts/') || url.pathname.startsWith('/live/')) {
    return { kind: 'video', url: `https://www.youtube.com${url.pathname}${url.pathname === '/watch' ? `?v=${url.searchParams.get('v')}` : ''}` };
  }
  const channel = url.pathname.match(/^\/(@[^/]+|channel\/UC[A-Za-z0-9_-]{22})/);
  if (channel) return { kind: 'channel', url: `https://www.youtube.com/${channel[1]}` };
  throw new Error(`cannot tell which channel ${input} belongs to`);
}

async function get(url: string): Promise<string> {
  const res = await fetch(url, { headers: { 'user-agent': UA, 'accept-language': 'en' } });
  if (!res.ok) throw new Error(`${url}: HTTP ${res.status}`);
  return res.text();
}

const decodeEntities = (s: string) =>
  s.replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>');

/** Name, handle and id from a channel page's HTML. */
export function parseChannelPage(html: string): Channel {
  const name = html.match(/<meta property="og:title" content="([^"]*)"/)?.[1];
  const handle = html.match(/"vanityChannelUrl":"https?:\/\/www\.youtube\.com\/(@[^"]+)"/)?.[1]
    ?? html.match(/<link itemprop="url" href="https?:\/\/www\.youtube\.com\/(@[^"]+)"/)?.[1];
  const id = html.match(/<link rel="canonical" href="https:\/\/www\.youtube\.com\/channel\/(UC[A-Za-z0-9_-]{22})"/)?.[1]
    ?? html.match(/"externalId":"(UC[A-Za-z0-9_-]{22})"/)?.[1];
  if (!name || !handle) throw new Error('channel page did not show a name and handle');
  return { name: decodeEntities(name), handle: normalizeHandle(handle), ...(id ? { id } : {}) };
}

export async function resolveChannel(input: string): Promise<Channel> {
  const target = classify(input);
  if (target.kind === 'channel') return parseChannelPage(await get(target.url));
  // oEmbed names the uploader without an API key; its author_url leads to the channel page for the id.
  const embed = JSON.parse(await get(`https://www.youtube.com/oembed?format=json&url=${encodeURIComponent(target.url)}`));
  return parseChannelPage(await get(String(embed.author_url)));
}

/** Adds to the youtube-channels rule, creating it on first use. Returns false when the handle is already there. */
export function addChannel(file: RulesFile, channel: Channel): boolean {
  let rule = file.rules.find((r) => r.id === CHANNELS_RULE_ID);
  if (!rule) {
    rule = {
      id: CHANNELS_RULE_ID,
      title: 'Blocked YouTube channels',
      why: 'You blocked this channel. Everything else on YouTube stays.',
      enabled: true,
      youtubeChannels: [],
    };
    file.rules.push(rule);
  }
  rule.youtubeChannels ??= [];
  if (rule.youtubeChannels.some((c) => sameHandle(c.handle, channel.handle))) return false;
  rule.youtubeChannels.push({ ...channel, name: channel.name.normalize('NFC').trim(), handle: normalizeHandle(channel.handle) });
  return true;
}

/** Removes by handle or exact name from every rule. Returns the removed channels. */
export function removeChannel(file: RulesFile, handleOrName: string): Channel[] {
  const removed: Channel[] = [];
  for (const rule of file.rules) {
    if (!rule.youtubeChannels) continue;
    rule.youtubeChannels = rule.youtubeChannels.filter((c) => {
      const hit = sameHandle(c.handle, handleOrName) || c.name.normalize('NFC').toLowerCase() === handleOrName.normalize('NFC').trim().toLowerCase();
      if (hit) removed.push(c);
      return !hit;
    });
  }
  return removed;
}

export function listChannels(file: RulesFile): (Channel & { rule: string; enabled: boolean })[] {
  return file.rules.flatMap((r) => (r.youtubeChannels ?? []).map((c) => ({ ...c, rule: r.id, enabled: r.enabled })));
}
