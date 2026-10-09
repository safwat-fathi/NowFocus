import { randomUUID } from 'node:crypto';
import { DataSource } from 'typeorm';
import { Config } from '../config.js';
import { sendMail } from '../mail.js';
import { Locale, unsubscribeUrl } from './email-layout.js';
import { versionEmail } from './version-email.js';

export interface NotifyOpts {
  platform: string;
  version: string;
  url: string;
  notes: string[];
  /** false = report who would get it and send nothing. */
  send: boolean;
  /** Send one preview to this address (not on the list, nothing recorded). */
  to?: string;
  /** Preview language for `to`. */
  locale?: Locale;
}

export interface NotifyResult { pending: number; sent: number; failed: number }

const MAX_CONSECUTIVE_FAILURES = 5; // a bad key or Brevo outage should stop the run, not fail every recipient

/**
 * Emails everyone who picked `platform`, hasn't unsubscribed and has not yet been sent `version`.
 * A waitlist_sends row is written only after Brevo accepts the message, so a re-run resumes where it stopped.
 */
export async function notifyVersion(ds: DataSource, config: Config, o: NotifyOpts, mail: typeof sendMail = sendMail, log: (s: string) => void = console.log): Promise<NotifyResult> {
  const build = (id: string, locale: Locale) => {
    const unsubUrl = unsubscribeUrl(config.jwtSecret, id);
    const { subject, html } = versionEmail({ locale, platform: o.platform, version: o.version, notes: o.notes, url: o.url, unsubUrl });
    return { subject, html, headers: { 'List-Unsubscribe': `<${unsubUrl}>`, 'List-Unsubscribe-Post': 'List-Unsubscribe=One-Click' } };
  };

  if (o.to) {
    const m = build(randomUUID(), o.locale ?? 'en');
    await mail(config, o.to, m.subject, m.html, m.headers);
    log(`preview sent to ${o.to}`);
    return { pending: 1, sent: 1, failed: 0 };
  }

  const rows: { id: string; email: string; locale: Locale }[] = await ds.query(
    `select w.id, w.email, w.locale from waitlist w
      where $1 = any(w.platforms) and w.unsubscribed_at is null
        and not exists (select 1 from waitlist_sends s where s.waitlist_id = w.id and s.platform = $1 and s.version = $2)
      order by w.created_at`,
    [o.platform, o.version],
  );
  const res: NotifyResult = { pending: rows.length, sent: 0, failed: 0 };
  if (!o.send) {
    log(`${rows.length} to email for ${o.platform} ${o.version} (dry run, add --send to send)`);
    for (const r of rows.slice(0, 5)) log(`  ${r.email} (${r.locale})`);
    return res;
  }

  let streak = 0;
  for (const r of rows) {
    try {
      const m = build(r.id, r.locale);
      await mail(config, r.email, m.subject, m.html, m.headers);
      await ds.query('insert into waitlist_sends (waitlist_id, platform, version) values ($1, $2, $3) on conflict do nothing', [r.id, o.platform, o.version]);
      res.sent++;
      streak = 0;
    } catch (err: unknown) {
      res.failed++;
      log(`failed ${r.email}: ${err instanceof Error ? err.message : err}`);
      if (++streak >= MAX_CONSECUTIVE_FAILURES) {
        log(`stopping after ${streak} failures in a row`);
        break;
      }
    }
  }
  log(`sent ${res.sent}, failed ${res.failed}, not attempted ${res.pending - res.sent - res.failed}`);
  return res;
}
