import { emailShell, escapeHtml, FONT, heading, Locale, row, UNSUB } from './email-layout.js';

const PLATFORM = {
  android: { en: 'Android', ar: 'أندرويد' },
  windows: { en: 'Windows', ar: 'ويندوز' },
  macos: { en: 'macOS', ar: 'macOS' },
  ios: { en: 'iOS', ar: 'iOS' },
} as const;

const HINT = {
  en: {
    android: 'Open the link on your Android phone, accept the tester invite, then install or update NowFocus from Google Play.',
    windows: 'Download the installer and run it. If you already have NowFocus, it updates itself.',
  },
  ar: {
    android: 'افتح الرابط على هاتفك، واقبل دعوة المجرّبين، ثم ثبّت NowFocus أو حدّثه من Google Play.',
    windows: 'نزّل برنامج التثبيت وشغّله. وإن كان NowFocus عندك فسيُحدّث نفسه.',
  },
} as const;

const COPY = {
  en: {
    subject: (v: string, p: string) => `NowFocus ${v} is ready to test on ${p}`,
    title: (v: string) => `NowFocus ${v} is ready.`,
    intro: (p: string) => `A new test build for ${p} is out. Thanks for being early.`,
    news: "What's new",
    cta: (v: string) => `Get NowFocus ${v}`,
    reply: 'Found a bug, or have an idea? Just reply to this email. It goes straight to a person.',
    foot: (p: string) => `You received this because you joined the NowFocus waitlist for ${p}.`,
  },
  ar: {
    subject: (v: string, p: string) => `NowFocus ${v} جاهز للتجربة على ${p}`,
    title: (v: string) => `NowFocus ${v} جاهز.`,
    intro: (p: string) => `صدر إصدار تجريبي جديد لمنصة ${p}. شكرًا لأنك من أوائل المجرّبين.`,
    news: 'الجديد',
    cta: (v: string) => `احصل على NowFocus ${v}`,
    reply: 'وجدت خطأً أو عندك فكرة؟ ردّ على هذا البريد وسيصلنا مباشرة.',
    foot: (p: string) => `وصلتك هذه الرسالة لأنك انضممت إلى قائمة انتظار NowFocus لمنصة ${p}.`,
  },
} as const;

export interface VersionEmailInput {
  locale?: Locale;
  platform: string;
  version: string;
  /** Short plain-text lines; escaped here because they end up inside HTML. */
  notes?: string[];
  /** Download / opt-in link. The caller guarantees it is https. */
  url: string;
  unsubUrl: string;
}

export function versionEmail(i: VersionEmailInput): { subject: string; html: string } {
  const locale = i.locale ?? 'en';
  const c = COPY[locale];
  const p = (PLATFORM as Record<string, { en: string; ar: string }>)[i.platform]?.[locale] ?? escapeHtml(i.platform);
  const v = escapeHtml(i.version);
  const hint = (HINT[locale] as Record<string, string>)[i.platform];
  const pad = locale === 'ar' ? 'padding:0 20px 0 0' : 'padding:0 0 0 20px';
  const notes = (i.notes ?? []).filter(Boolean);

  const rows =
    heading(c.title(v)) +
    row(c.intro(p)) +
    (notes.length
      ? row(`<strong>${c.news}</strong><ul style="margin:8px 0 0;${pad};">${notes.map((n) => `<li style="margin:0 0 4px;">${escapeHtml(n)}</li>`).join('')}</ul>`)
      : '') +
    row(
      `<table role="presentation" cellpadding="0" cellspacing="0"><tr><td bgcolor="#ec3013" style="background:#ec3013;"><a href="${escapeHtml(i.url)}" style="display:inline-block;padding:14px 28px;font-family:${FONT};font-size:16px;font-weight:700;color:#ffffff;text-decoration:none;">${c.cta(v)}</a></td></tr></table>`,
      '24px 32px 0',
    ) +
    (hint ? row(hint, '16px 32px 0', 'font-size:14px;line-height:22px;') : '') +
    row(c.reply, '24px 32px 32px');

  const subject = c.subject(i.version, p);
  const foot = `${c.foot(p)} ${UNSUB[locale](i.unsubUrl)}`;
  return { subject, html: emailShell({ locale, subject, preheader: c.intro(p), rows, foot }) };
}
