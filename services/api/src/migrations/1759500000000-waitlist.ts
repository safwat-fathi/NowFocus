import type { MigrationInterface, QueryRunner } from 'typeorm';

export class Waitlist1759500000000 implements MigrationInterface {
  name = 'Waitlist1759500000000';

  async up(q: QueryRunner) {
    await q.query(`
      create table waitlist (
        id uuid primary key,
        email text not null,
        platforms text[] not null default '{}',
        feature_request text,
        github_issue_url text,
        created_at timestamptz not null default now()
      );
      create unique index waitlist_email_idx on waitlist (lower(email));
    `);
  }

  async down(q: QueryRunner) {
    await q.query('drop table waitlist;');
  }
}
