import { emailShell, escapeHtml, heading, row } from '../waitlist/email-layout.js';

const button = (url: string, label: string) =>
  row(`<a href="${escapeHtml(url)}" style="display:inline-block;background:#ec3013;color:#ffffff;font-weight:700;text-decoration:none;padding:12px 24px;">${label}</a>`, '24px 32px 0');

const FOOT = 'If you did not ask for this, ignore this email. · إذا لم تطلب ذلك فتجاهل هذه الرسالة.';

/** Bilingual on purpose: the sign-up request carries no locale. */
export function verifyEmail(url: string) {
  const subject = 'Confirm your NowFocus email / أكّد بريدك في NowFocus';
  return {
    subject,
    html: emailShell({
      locale: 'en',
      subject,
      preheader: 'Confirm your email to finish creating your account.',
      rows:
        heading('Confirm your email') +
        row('Tap the button to finish creating your NowFocus account. The link works for 24 hours.') +
        row('اضغط الزر لإكمال إنشاء حسابك في NowFocus. الرابط صالح لمدة 24 ساعة.', '8px 32px 0') +
        button(url, 'Confirm / تأكيد') +
        row('', '16px 32px 0'),
      foot: FOOT,
    }),
  };
}

/** Sent instead of the verify mail when the address already has an account, so the sign-up response can stay identical. */
export function alreadyRegisteredEmail() {
  const subject = 'You already have a NowFocus account / لديك حساب في NowFocus';
  return {
    subject,
    html: emailShell({
      locale: 'en',
      subject,
      preheader: 'Someone tried to create an account with this email.',
      rows:
        heading('You already have an account') +
        row('Someone tried to create a NowFocus account with this email. You already have one: open the app and sign in with your password.') +
        row('حاول أحدهم إنشاء حساب في NowFocus بهذا البريد، ولديك حساب بالفعل. افتح التطبيق وسجّل الدخول بكلمة المرور.', '8px 32px 0') +
        row('', '16px 32px 0'),
      foot: FOOT,
    }),
  };
}
