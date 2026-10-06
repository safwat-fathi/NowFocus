import { Controller, Delete, Get, Header, HttpCode, Param, ParseUUIDPipe, Post, UseGuards } from '@nestjs/common';
import { ApiExcludeController } from '@nestjs/swagger';
import { ThrottlerGuard } from '@nestjs/throttler';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { Config } from '../config.js';
import { WaitlistEntry } from '../db/entities.js';
import { fail } from '../errors.js';
import { AdminGuard, SESSION_MS, signSession } from './admin.guard.js';
import { ADMIN_PAGE } from './admin.page.js';

/** Internal dashboard, not part of the client contract. The throttle runs first so wrong tokens are rate-limited. */
@ApiExcludeController()
@Controller()
@UseGuards(ThrottlerGuard)
export class AdminController {
  constructor(@InjectRepository(WaitlistEntry) private repo: Repository<WaitlistEntry>, private config: Config) {}

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
  list() {
    return this.repo.find({ order: { createdAt: 'DESC' } });
  }

  @Delete('v1/admin/waitlist/:id')
  @UseGuards(AdminGuard)
  @HttpCode(204)
  async remove(@Param('id', ParseUUIDPipe) id: string) {
    const res = await this.repo.delete(id);
    if (!res.affected) throw fail(404, 'not_found', 'No such entry');
  }
}
