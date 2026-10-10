import type { MigrationInterface, QueryRunner } from 'typeorm';

export class SignupMailLimits1761500000000 implements MigrationInterface {
  name = 'SignupMailLimits1761500000000';

  async up(q: QueryRunner) {
    await q.query('create table signup_mail_limits (email text primary key, window_start timestamptz not null, sent int not null);');
  }

  async down(q: QueryRunner) {
    await q.query('drop table signup_mail_limits;');
  }
}
