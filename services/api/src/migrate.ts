// `npm run migrate` — applies pending migrations to $DATABASE_URL (run before starting a new release).
import { DataSource } from 'typeorm';
import { dataSourceOptions } from './db/data-source.js';

try { process.loadEnvFile(); } catch { /* no .env: variables come from the environment */ }
const url = process.env.DATABASE_URL;
if (!url) throw new Error('DATABASE_URL is required');
const ds = await new DataSource(dataSourceOptions(url)).initialize();
const ran = await ds.runMigrations();
console.log(ran.length ? `applied: ${ran.map((m) => m.name).join(', ')}` : 'database is up to date');
await ds.destroy();
