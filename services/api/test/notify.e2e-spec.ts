import { DataSource } from 'typeorm';
import { Config } from '../src/config.js';
import { notifyVersion, type NotifyOpts } from '../src/waitlist/notify.js';
import { unsubscribeUrl } from '../src/waitlist/email-layout.js';
import { createTestApp } from './helpers/app.js';

const opts = (o: Partial<NotifyOpts> = {}): NotifyOpts => ({ platform: 'android', version: '0.9', url: 'https://example.com/get', notes: ['A'], send: true, ...o });

describe('notify testers + unsubscribe', () => {
  it('sends each person once per platform+version, skips unsubscribed, supports dry run and resume', async () => {
    const t = await createTestApp();
    const ds = t.app.get(DataSource);
    const config = t.app.get(Config);
    const sent: string[] = [];
    const mail = async (_c: unknown, to: string, subject: string, _html: string, headers?: Record<string, string>) => {
      expect(headers?.['List-Unsubscribe-Post']).toBe('List-Unsubscribe=One-Click');
      sent.push(`${to}|${subject}`);
    };
    const log = () => {};
    try {
      await ds.query("delete from waitlist where email like 'notify-test-%'");
      const add = (n: string, platforms: string[], locale = 'en', unsub = false) =>
        ds.query('insert into waitlist (id, email, platforms, locale, unsubscribed_at) values (gen_random_uuid(), $1, $2, $3, $4)', [`notify-test-${n}@example.com`, platforms, locale, unsub ? new Date() : null]);
      await add('a', ['android']);
      await add('b', ['android', 'windows'], 'ar');
      await add('c', ['windows']);
      await add('d', ['android'], 'en', true);

      const dry = await notifyVersion(ds, config, opts({ send: false }), mail as never, log);
      expect(dry).toEqual({ pending: 2, sent: 0, failed: 0 });
      expect(sent).toEqual([]);

      const first = await notifyVersion(ds, config, opts(), mail as never, log);
      expect(first).toEqual({ pending: 2, sent: 2, failed: 0 });
      expect(sent.sort()).toEqual(['notify-test-a@example.com|NowFocus 0.9 is ready to test on Android', 'notify-test-b@example.com|NowFocus 0.9 جاهز للتجربة على أندرويد']);

      sent.length = 0;
      expect((await notifyVersion(ds, config, opts(), mail as never, log)).pending).toBe(0);
      expect(sent).toEqual([]);

      expect((await notifyVersion(ds, config, opts({ version: '0.10' }), mail as never, log)).sent).toBe(2); // new version goes out again
      expect((await notifyVersion(ds, config, opts({ platform: 'windows' }), mail as never, log)).sent).toBe(2); // same version, other platform

      // a failure is not recorded, so the next run retries just that person
      const flaky = async (_c: unknown, to: string) => { if (to.includes('-a@')) throw new Error('boom'); };
      const r = await notifyVersion(ds, config, opts({ version: '1.0' }), flaky as never, log);
      expect(r).toEqual({ pending: 2, sent: 1, failed: 1 });
      sent.length = 0;
      expect((await notifyVersion(ds, config, opts({ version: '1.0' }), mail as never, log)).sent).toBe(1);
      expect(sent[0]).toContain('notify-test-a@');

    } finally {
      await ds.query("delete from waitlist where email like 'notify-test-%'");
      await t.close();
    }
  });

  it('unsubscribe: GET is read-only, POST needs a valid token and records it', async () => {
    const t = await createTestApp();
    const ds = t.app.get(DataSource);
    const secret = t.app.get(Config).jwtSecret;
    try {
      const [{ id }] = await ds.query("insert into waitlist (id, email) values (gen_random_uuid(), 'notify-test-u@example.com') returning id");
      const link = new URL(unsubscribeUrl(secret, id));
      const path = `${link.pathname}${link.search}`;

      expect((await t.http.get(path)).status).toBe(200);
      expect((await ds.query('select unsubscribed_at from waitlist where id = $1', [id]))[0].unsubscribed_at).toBeNull();

      expect((await t.http.post(`${link.pathname}?id=${id}&t=bad`)).status).toBe(404);
      expect((await t.http.post(path)).status).toBe(200);
      expect((await ds.query('select unsubscribed_at from waitlist where id = $1', [id]))[0].unsubscribed_at).not.toBeNull();
    } finally {
      await ds.query("delete from waitlist where email like 'notify-test-%'");
      await t.close();
    }
  });
});
