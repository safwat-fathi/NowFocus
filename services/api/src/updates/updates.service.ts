import { Injectable } from '@nestjs/common';
import { Clock } from '../clock.js';
import { Config } from '../config.js';
import { fail } from '../errors.js';

/** The shape the Tauri updater plugin accepts from a dynamic endpoint. */
export interface UpdateInfo {
  version: string;
  notes: string;
  pub_date: string;
  url: string;
  signature: string;
}

const TTL_MS = 5 * 60_000;

/** True when `a` is a higher dotted version than `b`. Ignores a leading "v" and any "-pre" suffix; unparsable `b` counts as older. */
export const isNewer = (a: string, b: string) => {
  const parts = (v: string) => v.replace(/^v/, '').split('-')[0].split('.').map(Number);
  const [x, y] = [parts(a), parts(b)];
  for (let i = 0; i < Math.max(x.length, y.length); i++) {
    const [p, q] = [x[i] ?? 0, y[i] ?? 0];
    if (Number.isNaN(q)) return true;
    if (p !== q) return p > q;
  }
  return false;
};

/**
 * The newest Windows release, read from the `latest.json` that tauri-action attaches to each GitHub Release.
 * The installers live on GitHub; this API is the stable front door for the app (updates) and the website (download).
 */
@Injectable()
export class UpdatesService {
  private cache?: { at: number; info: UpdateInfo };

  constructor(private config: Config, private clock: Clock) {}

  async latest(): Promise<UpdateInfo> {
    const now = this.clock.now().getTime();
    if (this.cache && now - this.cache.at < TTL_MS) return this.cache.info;
    try {
      // ponytail: "latest" is repo-wide, so a future non-Windows GitHub Release would hide this one. Filter tags (windows-v*) via the releases API then.
      const res = await fetch(`https://github.com/${this.config.githubRepo}/releases/latest/download/latest.json`, {
        headers: { 'User-Agent': 'NowFocus-API' },
      });
      if (!res.ok) throw new Error(`GitHub returned ${res.status}`);
      const j = (await res.json()) as { version?: string; notes?: string; pub_date?: string; platforms?: Record<string, { url?: string; signature?: string }> };
      const p = j.platforms?.['windows-x86_64-nsis'] ?? j.platforms?.['windows-x86_64'];
      if (!j.version || !p?.url || !p.signature) throw new Error('latest.json has no windows-x86_64 entry');
      this.cache = { at: now, info: { version: j.version.replace(/^v/, ''), notes: j.notes ?? '', pub_date: j.pub_date ?? '', url: p.url, signature: p.signature } };
    } catch {
      if (!this.cache) throw fail(503, 'release_unavailable', 'No Windows release is available right now');
      // Serve the stale answer rather than break updates over a GitHub blip; retry on the next request.
    }
    return this.cache.info;
  }
}
