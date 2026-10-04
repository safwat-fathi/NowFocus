import { Controller, Delete, Get, Header, HttpCode, Param, ParseUUIDPipe, UseGuards } from '@nestjs/common';
import { ApiExcludeController } from '@nestjs/swagger';
import { ThrottlerGuard } from '@nestjs/throttler';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { WaitlistEntry } from '../db/entities.js';
import { fail } from '../errors.js';
import { AdminGuard } from './admin.guard.js';
import { ADMIN_PAGE } from './admin.page.js';

/** Internal dashboard, not part of the client contract. The throttle runs first so wrong tokens are rate-limited. */
@ApiExcludeController()
@Controller()
@UseGuards(ThrottlerGuard)
export class AdminController {
  constructor(@InjectRepository(WaitlistEntry) private repo: Repository<WaitlistEntry>) {}

  // The page itself holds no data; the API calls it makes are guarded.
  @Get('admin')
  @Header('Content-Type', 'text/html; charset=utf-8')
  @Header('Cache-Control', 'no-store')
  @Header('X-Robots-Tag', 'noindex')
  @Header('X-Frame-Options', 'DENY')
  page() {
    return ADMIN_PAGE;
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
