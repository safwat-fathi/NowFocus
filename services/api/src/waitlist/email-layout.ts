import { createHmac, timingSafeEqual } from 'node:crypto';

export type Locale = 'en' | 'ar';

export const FONT = 'Archivo, Arial, Helvetica, sans-serif';

export const escapeHtml = (s: string) => s.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);

/** A body row in the card. `pad` is the CSS padding, so callers control the vertical rhythm. */
export const row = (inner: string, pad = '16px 32px 0', style = 'font-size:16px;line-height:26px;') =>
  `<tr><td style="padding:${pad};font-family:${FONT};${style}color:#201e1d;">${inner}</td></tr>`;

export const heading = (text: string) => row(text, '16px 32px 0', 'font-size:30px;line-height:36px;font-weight:800;letter-spacing:-0.03em;');

/** Table layout with inline styles only: the one thing Gmail and Outlook render the same. */
export function emailShell(o: { locale: Locale; subject: string; preheader: string; rows: string; foot: string }): string {
  const rtl = o.locale === 'ar';
  const start = rtl ? 'right' : 'left';
  return `<!doctype html>
<html lang="${o.locale}" dir="${rtl ? 'rtl' : 'ltr'}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${escapeHtml(o.subject)}</title></head>
<body style="margin:0;padding:0;background:#f3f2f2;">
<div style="display:none;max-height:0;overflow:hidden;opacity:0;">${o.preheader}</div>
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#f3f2f2;"><tr><td align="center" style="padding:32px 16px;">
<table role="presentation" width="560" cellpadding="0" cellspacing="0" style="width:100%;max-width:560px;background:#ffffff;border:1px solid #e2e0df;font-family:${FONT};color:#201e1d;text-align:${start};">
<tr><td style="padding:28px 32px 0;"><table role="presentation" cellpadding="0" cellspacing="0" dir="ltr"><tr>
<td><img src="https://nowfocus.online/logo-512.png" width="40" height="40" alt="NowFocus" style="display:block;border:0;"></td>
<td style="padding-left:12px;font-family:${FONT};font-size:20px;font-weight:800;letter-spacing:-0.03em;color:#201e1d;">NowFocus</td>
</tr></table></td></tr>
<tr><td style="padding:32px 32px 0;"><div style="width:40px;height:6px;background:#ec3013;line-height:6px;font-size:0;">&nbsp;</div></td></tr>
${o.rows}
<tr><td style="padding:16px 32px;background:#f3f2f2;font-family:${FONT};font-size:12px;line-height:18px;color:#6b6866;">${o.foot}</td></tr>
</table></td></tr></table></body></html>`;
}

// Unsubscribe links: stateless, signed with JWT_SECRET so the id alone can't be used to unsubscribe someone else.
const API = 'https://api.nowfocus.online';
const sign = (secret: string, id: string) => createHmac('sha256', secret).update(`unsub:${id}`).digest('hex');

export const unsubscribeUrl = (secret: string, id: string) => `${API}/v1/waitlist/unsubscribe?id=${id}&t=${sign(secret, id)}`;

export const validUnsubscribe = (secret: string, id: string, t: string) => {
  const want = Buffer.from(sign(secret, id));
  const got = Buffer.from(String(t));
  return want.length === got.length && timingSafeEqual(want, got);
};

export const UNSUB = {
  en: (url: string) => `<a href="${url}" style="color:#6b6866;">Unsubscribe</a>`,
  ar: (url: string) => `<a href="${url}" style="color:#6b6866;">إلغاء الاشتراك</a>`,
} as const;
