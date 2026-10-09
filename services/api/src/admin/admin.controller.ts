import { Controller, Delete, Get, Header, HttpCode, Param, ParseUUIDPipe, Post, UseGuards } from '@nestjs/common';
import { ApiExcludeController } from '@nestjs/swagger';
import { ThrottlerGuard } from '@nestjs/throttler';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { Config } from '../config.js';
import { WaitlistEntry, WaitlistSend } from '../db/entities.js';
import { fail } from '../errors.js';
import { AdminGuard, SESSION_MS, signSession } from './admin.guard.js';
import { ADMIN_PAGE } from './admin.page.js';

/** Internal dashboard, not part of the client contract. The throttle runs first so wrong tokens are rate-limited. */
@ApiExcludeController()
@Controller()
@UseGuards(ThrottlerGuard)
export class AdminController {
  constructor(
    @InjectRepository(WaitlistEntry) private repo: Repository<WaitlistEntry>,
    @InjectRepository(WaitlistSend) private sends: Repository<WaitlistSend>,
    private config: Config,
  ) {}

  // The page itself holds no data; the API calls it makes are guarded.
  @Get('admin')
  @Header('Content-Type', 'text/html; charset=utf-8')
  @Header('Cache-Control', 'no-store')
  @Header('X-Robots-Tag', 'noindex')
  @Header('X-Frame-Options', 'DENY')
  page() {
    return ADMIN_PAGE;
  }

  @Post('v1/admin/session')
  @UseGuards(AdminGuard)
  @HttpCode(200)
  session() {
    const expiresAt = Date.now() + SESSION_MS;
    return { token: signSession(this.config.adminToken!, expiresAt), expiresAt };
  }

  @Get('v1/admin/waitlist')
  @UseGuards(AdminGuard)
  async list() {
    const [entries, sends] = await Promise.all([this.repo.find({ order: { createdAt: 'DESC' } }), this.sends.find({ order: { sentAt: 'ASC' } })]);
    const byId = new Map<string, { platform: string; version: string; sentAt: Date }[]>();
    for (const { waitlistId, platform, version, sentAt } of sends) byId.set(waitlistId, [...(byId.get(waitlistId) ?? []), { platform, version, sentAt }]);
    return entries.map((e) => ({ ...e, sends: byId.get(e.id) ?? [] }));
  }

  @Delete('v1/admin/waitlist/:id')
  @UseGuards(AdminGuard)
  @HttpCode(204)
  async remove(@Param('id', ParseUUIDPipe) id: string) {
    const res = await this.repo.delete(id);
    if (!res.affected) throw fail(404, 'not_found', 'No such entry');
  }
}
