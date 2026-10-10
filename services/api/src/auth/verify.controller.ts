import { Body, Controller, Get, Header, HttpCode, Post, Query, UseGuards } from '@nestjs/common';
import { ApiExcludeController } from '@nestjs/swagger';
import { ThrottlerGuard } from '@nestjs/throttler';
import { fail } from '../errors.js';
import { escapeHtml } from '../waitlist/email-layout.js';
import { page } from '../waitlist/unsubscribe.controller.js';
import { AuthService } from './auth.service.js';

const TOKEN = /^[\w-]{20,100}$/;

/**
 * Target of the link in the sign-up email. GET only shows a confirm button (mail scanners prefetch links, so a GET
 * that created the account would also work for people who never clicked); POST creates it.
 */
@ApiExcludeController()
@Controller('v1/auth/verify')
@UseGuards(ThrottlerGuard)
export class VerifyController {
  constructor(private auth: AuthService) {}

  private form(token: string, error = '') {
    return page(`<p>Confirm your email to finish creating your NowFocus account. Enter the password you chose in the app.<br><span dir="rtl">أكّد بريدك لإكمال إنشاء حسابك في NowFocus. أدخل كلمة المرور التي اخترتها في التطبيق.</span></p>
${error}<form method="post" action="/v1/auth/verify?token=${token}"><p><input type="password" name="password" required autocomplete="current-password" style="font:inherit;padding:8px;width:90%"></p><button>Confirm / تأكيد</button></form>`);
  }

  @Get()
  @Header('Content-Type', 'text/html; charset=utf-8')
  @Header('Cache-Control', 'no-store')
  confirm(@Query('token') token = '') {
    if (!TOKEN.test(token)) throw fail(404, 'not_found', 'This link is invalid or has expired');
    return this.form(token);
  }

  @Post() @HttpCode(200)
  @Header('Content-Type', 'text/html; charset=utf-8')
  @Header('Cache-Control', 'no-store')
  async verify(@Query('token') token = '', @Body('password') password = '') {
    if (!TOKEN.test(token)) throw fail(404, 'not_found', 'This link is invalid or has expired');
    try {
      await this.auth.verify(token, String(password));
    } catch (e: any) {
      if (e?.getStatus?.() !== 403) throw e;
      return this.form(token, `<p style="color:#ec3013">${escapeHtml('Wrong password. / كلمة المرور غير صحيحة.')}</p>`);
    }
    return page('<p>Email confirmed. Open NowFocus and sign in.<br><span dir="rtl">تم تأكيد بريدك. افتح NowFocus وسجّل الدخول.</span></p>');
  }
}
