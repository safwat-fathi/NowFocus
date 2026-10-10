import { Controller, Get, Header, HttpCode, Post, Query, UseGuards } from '@nestjs/common';
import { ApiExcludeController } from '@nestjs/swagger';
import { ThrottlerGuard } from '@nestjs/throttler';
import { fail } from '../errors.js';
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

  @Get()
  @Header('Content-Type', 'text/html; charset=utf-8')
  @Header('Cache-Control', 'no-store')
  confirm(@Query('token') token = '') {
    if (!TOKEN.test(token)) throw fail(404, 'not_found', 'This link is invalid or has expired');
    return page(`<p>Confirm your email to finish creating your NowFocus account.<br><span dir="rtl">أكّد بريدك لإكمال إنشاء حسابك في NowFocus.</span></p>
<form method="post" action="/v1/auth/verify?token=${token}"><button>Confirm / تأكيد</button></form>`);
  }

  @Post() @HttpCode(200)
  @Header('Content-Type', 'text/html; charset=utf-8')
  @Header('Cache-Control', 'no-store')
  async verify(@Query('token') token = '') {
    if (!TOKEN.test(token)) throw fail(404, 'not_found', 'This link is invalid or has expired');
    await this.auth.verify(token);
    return page('<p>Email confirmed. Open NowFocus and sign in.<br><span dir="rtl">تم تأكيد بريدك. افتح NowFocus وسجّل الدخول.</span></p>');
  }
}
