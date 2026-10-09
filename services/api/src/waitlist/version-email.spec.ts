import { unsubscribeUrl, validUnsubscribe } from './email-layout.js';
import { versionEmail } from './version-email.js';
import { welcomeEmail } from './welcome-email.js';

const base = { platform: 'android', version: '0.9', url: 'https://play.google.com/apps/testing/x', unsubUrl: 'https://api.nowfocus.online/v1/waitlist/unsubscribe?id=1&t=2' };

describe('versionEmail', () => {
  it('renders English with version, platform, link, notes and unsubscribe', () => {
    const { subject, html } = versionEmail({ ...base, notes: ['Fixes the block screen'] });
    expect(subject).toBe('NowFocus 0.9 is ready to test on Android');
    expect(html).toContain('dir="ltr"');
    expect(html).toContain(`href="${base.url}"`);
    expect(html).toContain('Fixes the block screen');
    expect(html).toContain(`href="${base.unsubUrl}"`);
    expect(html).toContain('logo-512.png');
  });

  it('renders Arabic right-to-left', () => {
    const { subject, html } = versionEmail({ ...base, locale: 'ar' });
    expect(subject).toContain('أندرويد');
    expect(html).toContain('dir="rtl"');
    expect(html).toContain('إلغاء الاشتراك');
  });

  it('escapes notes and version because they are injected into HTML', () => {
    const { html } = versionEmail({ ...base, version: '1<b>', notes: ['<script>alert(1)</script>'] });
    expect(html).not.toContain('<script>');
    expect(html).not.toContain('1<b>');
    expect(html).toContain('&#60;script&#62;');
  });

  it('omits the notes block when there are none', () => {
    expect(versionEmail(base).html).not.toContain("What's new");
  });
});

describe('unsubscribe links', () => {
  const secret = 'x'.repeat(32);
  const id = '11111111-1111-4111-8111-111111111111';

  it('only validates for the id they were signed for', () => {
    const t = new URL(unsubscribeUrl(secret, id)).searchParams.get('t')!;
    expect(validUnsubscribe(secret, id, t)).toBe(true);
    expect(validUnsubscribe(secret, '22222222-2222-4222-8222-222222222222', t)).toBe(false);
    expect(validUnsubscribe('y'.repeat(32), id, t)).toBe(false);
    expect(validUnsubscribe(secret, id, 'short')).toBe(false);
  });

  it('welcome email carries the link when given one', () => {
    expect(welcomeEmail('en', base.unsubUrl).html).toContain(base.unsubUrl);
    expect(welcomeEmail('en').html).not.toContain('Unsubscribe');
  });
});
