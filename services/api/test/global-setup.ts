import { DataSource } from 'typeorm';
import { dataSourceOptions } from '../src/db/data-source.js';

// Rebuild the schema once per e2e run. Refuses to touch anything but a *_test database.
export default async function setup() {
  const url = process.env.DATABASE_URL ?? 'postgres://localhost:5432/nowfocus_test';
  if (!/_test(\?|$)/.test(url)) throw new Error(`refusing to reset non-test database: ${url}`);
  const ds = await new DataSource(dataSourceOptions(url)).initialize();
  await ds.query('drop schema public cascade');
  await ds.query('create schema public');
  await ds.runMigrations();
  await ds.destroy();
}
