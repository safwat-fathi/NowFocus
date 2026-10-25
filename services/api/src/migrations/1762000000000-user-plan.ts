import type { MigrationInterface, QueryRunner } from 'typeorm';

export class UserPlan1762000000000 implements MigrationInterface {
  name = 'UserPlan1762000000000';

  async up(q: QueryRunner) {
    await q.query("alter table users add column plan text not null default 'free' check (plan in ('free','pro')), add column plan_expires_at timestamptz;");
  }

  async down(q: QueryRunner) {
    await q.query('alter table users drop column plan_expires_at, drop column plan;');
  }
}
