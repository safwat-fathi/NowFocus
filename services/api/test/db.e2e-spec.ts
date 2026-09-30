import { DataSource } from 'typeorm';
import { dataSourceOptions } from '../src/db/data-source.js';

describe('database schema', () => {
  let ds: DataSource;
  beforeAll(async () => {
    ds = await new DataSource(dataSourceOptions(process.env.DATABASE_URL!)).initialize();
  });
  afterAll(() => ds.destroy());

  it('has the three sync tables after migrations', async () => {
    const rows = await ds.query(
      `select table_name from information_schema.tables where table_schema='public' and table_name in ('users','devices','sync_records') order by 1`,
    );
    expect(rows.map((r: any) => r.table_name)).toEqual(['devices', 'sync_records', 'users']);
  });

  it('keys sync_records by (user_id, type, id) so one user cannot collide with another', async () => {
    const rows = await ds.query(
      `select a.attname from pg_index i join pg_attribute a on a.attrelid=i.indrelid and a.attnum=any(i.indkey)
       where i.indrelid='sync_records'::regclass and i.indisprimary order by array_position(i.indkey, a.attnum)`,
    );
    expect(rows.map((r: any) => r.attname)).toEqual(['user_id', 'type', 'id']);
  });

  it('enforces one email per account case-insensitively', async () => {
    await ds.query(`insert into users(id,email,password_hash) values (gen_random_uuid(),'a@x.io','h')`);
    await expect(
      ds.query(`insert into users(id,email,password_hash) values (gen_random_uuid(),'A@X.io','h')`),
    ).rejects.toThrow(/duplicate key/);
  });
});
