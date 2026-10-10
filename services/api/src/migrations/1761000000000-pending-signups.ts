import type { MigrationInterface, QueryRunner } from 'typeorm';

export class PendingSignupsMigration1761000000000 implements MigrationInterface {
  name = 'PendingSignups1761000000000';

  async up(q: QueryRunner) {
    await q.query(`
      create table pending_signups (
        email text primary key,
        password_hash text not null,
        token_hash text not null unique,
        expires_at timestamptz not null,
        created_at timestamptz not null
      );
    `);
  }

  async down(q: QueryRunner) {
    await q.query('drop table pending_signups;');
  }
}
