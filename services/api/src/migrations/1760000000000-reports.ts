import type { MigrationInterface, QueryRunner } from 'typeorm';

export class Reports1760000000000 implements MigrationInterface {
  name = 'Reports1760000000000';

  async up(q: QueryRunner) {
    await q.query(`
      create table issue_reports (
        id uuid primary key,
        message text not null,
        contact text,
        platform text not null,
        app_version text not null,
        os_version text not null,
        github_issue_url text,
        created_at timestamptz not null default now()
      );
    `);
  }

  async down(q: QueryRunner) {
    await q.query('drop table issue_reports;');
  }
}
