import { Controller, Get, Header, HttpCode, ParseUUIDPipe, Post, Query, UseGuards } from '@nestjs/common';
import { ApiExcludeController } from '@nestjs/swagger';
import { ThrottlerGuard } from '@nestjs/throttler';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { Config } from '../config.js';
import { WaitlistEntry } from '../db/entities.js';
import { fail } from '../errors.js';
import { validUnsubscribe } from './email-layout.js';

const page = (body: string) => `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="robots" content="noindex"><title>NowFocus</title>
<style>body{margin:0;padding:48px 16px;background:#f3f2f2;color:#201e1d;font:16px/26px Archivo,Arial,sans-serif;text-align:center}main{max-width:420px;margin:0 auto;background:#fff;border:1px solid #e2e0df;padding:32px}
button{font:inherit;font-weight:700;color:#fff;background:#ec3013;border:0;padding:12px 24px;cursor:pointer}</style></head><body><main>${body}</main></body></html>`;

/**
 * Link target for the unsubscribe footer. GET only shows a confirm button (mail scanners prefetch links, so a GET
 * that unsubscribed would drop people who never clicked); POST does it and also serves RFC 8058 one-click.
 */
@ApiExcludeController()
@Controller('v1/waitlist/unsubscribe')
@UseGuards(ThrottlerGuard)
export class UnsubscribeController {
  constructor(@InjectRepository(WaitlistEntry) private repo: Repository<WaitlistEntry>, private config: Config) {}

  private check(id: string, t: string) {
    if (!validUnsubscribe(this.config.jwtSecret, id, t)) throw fail(404, 'not_found', 'Invalid unsubscribe link');
  }

  @Get()
  @Header('Content-Type', 'text/html; charset=utf-8')
  @Header('Cache-Control', 'no-store')
  confirm(@Query('id', ParseUUIDPipe) id: string, @Query('t') t = '') {
    this.check(id, t);
    return page(`<p>Stop NowFocus emails to your address?<br><span dir="rtl">هل تريد إيقاف رسائل NowFocus عن بريدك؟</span></p>
<form method="post" action="/v1/waitlist/unsubscribe?id=${id}&amp;t=${t}"><button>Unsubscribe / إلغاء الاشتراك</button></form>`);
  }

  @Post()
  @HttpCode(200)
  @Header('Content-Type', 'text/html; charset=utf-8')
  @Header('Cache-Control', 'no-store')
  async unsubscribe(@Query('id', ParseUUIDPipe) id: string, @Query('t') t = '') {
    this.check(id, t);
    await this.repo.update({ id }, { unsubscribedAt: () => 'coalesce(unsubscribed_at, now())' });
    return page('<p>You are unsubscribed. We will not email you again.<br><span dir="rtl">تم إلغاء اشتراكك، لن نراسلك مجددًا.</span></p>');
  }
}
