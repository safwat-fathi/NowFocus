export type Locale = 'en' | 'ar';

const COPY = {
  en: {
    dir: 'ltr',
    subject: "You're on the NowFocus waitlist",
    preheader: "We'll email you once, when your platform is ready.",
    title: "You're on the list.",
    body: "Thanks for joining the NowFocus waitlist. We'll email you once, when your platform is ready. No marketing in between.",
    next: 'Questions, or a feature you want? Just reply to this email. It goes straight to a person.',
    foot: 'You received this because you joined the waitlist at nowfocus.online.',
  },
  ar: {
    dir: 'rtl',
    subject: 'أنت على قائمة انتظار NowFocus',
    preheader: 'سنراسلك مرة واحدة، عندما تصبح منصتك جاهزة.',
    title: 'أنت على القائمة.',
    body: 'شكرًا لانضمامك إلى قائمة انتظار NowFocus. سنراسلك مرة واحدة، عندما تصبح منصتك جاهزة، دون رسائل تسويقية بينهما.',
    next: 'عندك سؤال أو ميزة تريدها؟ ردّ على هذا البريد وسيصلنا مباشرة.',
    foot: 'وصلتك هذه الرسالة لأنك انضممت إلى قائمة الانتظار في nowfocus.online.',
  },
} as const;

const FONT = "Archivo, Arial, Helvetica, sans-serif";

/** Table layout with inline styles only: the one thing Gmail and Outlook render the same. */
export function welcomeEmail(locale: Locale = 'en'): { subject: string; html: string } {
  const c = COPY[locale];
  const start = c.dir === 'rtl' ? 'right' : 'left';
  const html = `<!doctype html>
<html lang="${locale}" dir="${c.dir}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${c.subject}</title></head>
<body style="margin:0;padding:0;background:#f3f2f2;">
<div style="display:none;max-height:0;overflow:hidden;opacity:0;">${c.preheader}</div>
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#f3f2f2;"><tr><td align="center" style="padding:32px 16px;">
<table role="presentation" width="560" cellpadding="0" cellspacing="0" style="width:100%;max-width:560px;background:#ffffff;border:1px solid #e2e0df;font-family:${FONT};color:#201e1d;text-align:${start};">
<tr><td style="padding:28px 32px 0;"><table role="presentation" cellpadding="0" cellspacing="0" dir="ltr"><tr>
<td><img src="https://nowfocus.online/logo-512.png" width="40" height="40" alt="NowFocus" style="display:block;border:0;"></td>
<td style="padding-left:12px;font-family:${FONT};font-size:20px;font-weight:800;letter-spacing:-0.03em;color:#201e1d;">NowFocus</td>
</tr></table></td></tr>
<tr><td style="padding:32px 32px 0;"><div style="width:40px;height:6px;background:#ec3013;line-height:6px;font-size:0;">&nbsp;</div></td></tr>
<tr><td style="padding:16px 32px 0;font-family:${FONT};font-size:30px;line-height:36px;font-weight:800;letter-spacing:-0.03em;color:#201e1d;">${c.title}</td></tr>
<tr><td style="padding:16px 32px 0;font-family:${FONT};font-size:16px;line-height:26px;color:#201e1d;">${c.body}</td></tr>
<tr><td style="padding:16px 32px 32px;font-family:${FONT};font-size:16px;line-height:26px;color:#201e1d;">${c.next}</td></tr>
<tr><td style="padding:16px 32px;background:#f3f2f2;font-family:${FONT};font-size:12px;line-height:18px;color:#6b6866;">${c.foot}</td></tr>
</table></td></tr></table></body></html>`;
  return { subject: c.subject, html };
}
