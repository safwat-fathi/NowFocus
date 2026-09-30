import type { MigrationInterface, QueryRunner } from 'typeorm';

export class Init1759000000000 implements MigrationInterface {
  name = 'Init1759000000000';

  async up(q: QueryRunner) {
    await q.query(`
      create table users (
        id uuid primary key,
        email text not null,
        password_hash text not null,
        sync_seq bigint not null default 0,
        created_at timestamptz not null default now()
      );
      create unique index users_email_key on users (lower(email));

      create table devices (
        id uuid primary key,
        user_id uuid not null references users(id) on delete cascade,
        name text not null,
        platform text not null check (platform in ('macos','windows','android','ios')),
        refresh_hash text,
        refresh_expires_at timestamptz,
        created_at timestamptz not null default now(),
        last_seen_at timestamptz,
        revoked_at timestamptz
      );
      create index devices_user_idx on devices (user_id);

      -- Every synced entity (incl. server-written shield items) lives here.
      -- seq is a per-user monotonic counter (users.sync_seq), the pull cursor.
      create table sync_records (
        user_id uuid not null references users(id) on delete cascade,
        type text not null,
        id text not null,
        seq bigint not null,
        revision int not null,
        data jsonb not null,
        client_updated_at timestamptz not null,
        updated_at timestamptz not null default now(),
        deleted_at timestamptz,
        origin_device_id uuid not null,
        primary key (user_id, type, id)
      );
      create index sync_records_seq_idx on sync_records (user_id, seq);
    `);
  }

  async down(q: QueryRunner) {
    await q.query('drop table sync_records; drop table devices; drop table users;');
  }
}
