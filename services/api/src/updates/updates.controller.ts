import { Controller, Get, Param, Redirect, Res } from '@nestjs/common';
import { ApiExcludeController } from '@nestjs/swagger';
import type { Response } from 'express';
import { isNewer, type UpdateInfo, UpdatesService } from './updates.service.js';

/** Release plumbing for the Windows app and the website. Public, not part of the client contract. */
@ApiExcludeController()
@Controller('v1')
export class UpdatesController {
  constructor(private updates: UpdatesService) {}

  /** The Tauri updater's endpoint: 204 when `current` is up to date, otherwise the newest release. */
  @Get('updates/windows/:current')
  async check(@Param('current') current: string, @Res({ passthrough: true }) res: Response): Promise<UpdateInfo | undefined> {
    const latest = await this.updates.latest();
    if (isNewer(latest.version, current)) return latest;
    res.status(204);
  }

  /** The public download link: always the newest installer. */
  @Get('downloads/windows') @Redirect()
  async download() {
    return { url: (await this.updates.latest()).url, statusCode: 302 };
  }
}
