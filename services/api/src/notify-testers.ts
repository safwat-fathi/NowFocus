// `pnpm run notify --platform android --version 0.9 --url <link> --notes "Fix A|Add B" [--to me@x.com] [--send]`
// Emails the waitlist about a new test build. Dry run unless --send; each (person, platform, version) is sent once.
import { parseArgs } from 'node:util';
import { DataSource } from 'typeorm';
import { loadConfig } from './config.js';
import { dataSourceOptions } from './db/data-source.js';
import { PLATFORMS } from './db/entities.js';
import { notifyVersion } from './waitlist/notify.js';

try { process.loadEnvFile(); } catch { /* no .env: variables come from the environment */ }

const DEFAULT_URL: Record<string, string> = { windows: 'https://api.nowfocus.online/v1/downloads/windows' };
const { values: v } = parseArgs({
  args: process.argv.slice(2).filter((a) => a !== '--'), // pnpm forwards the `--` of `pnpm run notify -- ...`
  options: {
    platform: { type: 'string' }, version: { type: 'string' }, url: { type: 'string' }, notes: { type: 'string' },
    to: { type: 'string' }, locale: { type: 'string' }, send: { type: 'boolean', default: false },
  },
});

const die = (msg: string): never => { console.error(msg); process.exit(1); };
const platform = v.platform ?? '';
if (!(PLATFORMS as readonly string[]).includes(platform)) die(`--platform must be one of ${PLATFORMS.join(', ')}`);
if (!v.version) die('--version is required');
const url = v.url ?? DEFAULT_URL[platform];
if (!url?.startsWith('https://')) die('--url is required and must be https:// (Android: your Play testing opt-in link)');
if (v.locale && v.locale !== 'en' && v.locale !== 'ar') die('--locale must be en or ar');

const config = loadConfig();
if (!config.brevoApiKey && (v.send || v.to)) die('BREVO_API_KEY is not set');
const ds = await new DataSource(dataSourceOptions(config.databaseUrl)).initialize();
try {
  const notes = (v.notes ?? '').split('|').map((s) => s.trim()).filter(Boolean);
  await notifyVersion(ds, config, { platform, version: v.version!, url: url!, notes, send: v.send!, to: v.to, locale: v.locale as 'en' | 'ar' | undefined });
} finally {
  await ds.destroy();
}
