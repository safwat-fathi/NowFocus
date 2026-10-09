import type { MigrationInterface, QueryRunner } from 'typeorm';

export class WaitlistSends1760500000000 implements MigrationInterface {
  name = 'WaitlistSends1760500000000';

  async up(q: QueryRunner) {
    await q.query(`
      alter table waitlist add column locale text not null default 'en', add column unsubscribed_at timestamptz;
      create table waitlist_sends (
        waitlist_id uuid not null references waitlist(id) on delete cascade,
        platform text not null,
        version text not null,
        sent_at timestamptz not null default now(),
        primary key (waitlist_id, platform, version)
      );
    `);
  }

  async down(q: QueryRunner) {
    await q.query('drop table waitlist_sends; alter table waitlist drop column locale, drop column unsubscribed_at;');
  }
}
