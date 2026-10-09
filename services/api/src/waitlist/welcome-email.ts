import { emailShell, heading, Locale, row, UNSUB } from './email-layout.js';

export type { Locale };

const COPY = {
  en: {
    subject: "You're on the NowFocus waitlist",
    preheader: "We'll email you when a build for your platform is ready to test.",
    title: "You're on the list.",
    body: "Thanks for joining the NowFocus waitlist. We'll email you when a build for your platform is ready to test, and when there's a new version. No marketing in between.",
    next: 'Questions, or a feature you want? Just reply to this email. It goes straight to a person.',
    foot: 'You received this because you joined the waitlist at nowfocus.online.',
  },
  ar: {
    subject: 'أنت على قائمة انتظار NowFocus',
    preheader: 'سنراسلك عندما يصبح إصدار منصتك جاهزًا للتجربة.',
    title: 'أنت على القائمة.',
    body: 'شكرًا لانضمامك إلى قائمة انتظار NowFocus. سنراسلك عندما يصبح إصدار منصتك جاهزًا للتجربة، وعند صدور كل إصدار جديد، دون رسائل تسويقية بينهما.',
    next: 'عندك سؤال أو ميزة تريدها؟ ردّ على هذا البريد وسيصلنا مباشرة.',
    foot: 'وصلتك هذه الرسالة لأنك انضممت إلى قائمة الانتظار في nowfocus.online.',
  },
} as const;

/** `unsubUrl` is optional so the template stays a pure function of its inputs; callers with an entry id pass it. */
export function welcomeEmail(locale: Locale = 'en', unsubUrl?: string): { subject: string; html: string } {
  const c = COPY[locale];
  const rows = heading(c.title) + row(c.body) + row(c.next, '16px 32px 32px');
  const foot = unsubUrl ? `${c.foot} ${UNSUB[locale](unsubUrl)}` : c.foot;
  return { subject: c.subject, html: emailShell({ locale, subject: c.subject, preheader: c.preheader, rows, foot }) };
}
