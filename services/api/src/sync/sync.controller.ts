import { Body, Controller, Get, HttpCode, Post, Query, UseGuards } from '@nestjs/common';
import { ApiBearerAuth, ApiDefaultResponse, ApiTags } from '@nestjs/swagger';
import { AuthGuard, Me } from '../auth/auth.guard.js';
import type { AuthContext } from '../auth/auth.service.js';
import { ApiError, type PullResponse, type PushResponse } from '../responses.dto.js';
import { PullQuery, PushDto } from './sync.dto.js';
import { SyncService } from './sync.service.js';

@ApiDefaultResponse({ type: ApiError }) @ApiTags('sync') @ApiBearerAuth()
@Controller('v1/sync')
@UseGuards(AuthGuard)
export class SyncController {
  constructor(private sync: SyncService) {}

  /** Push local changes. Each change is applied or rejected independently; the batch itself only fails on a malformed envelope. */
  @Post('push') @HttpCode(200)
  push(@Me() me: AuthContext, @Body() dto: PushDto): Promise<PushResponse> {
    return this.sync.push(me, dto.changes) as Promise<PushResponse>;
  }

  /** Everything that changed after `cursor`, in seq order (tombstones included). */
  @Get('pull')
  pull(@Me() me: AuthContext, @Query() q: PullQuery): Promise<PullResponse> {
    return this.sync.pull(me.userId, q.cursor ?? 0, q.limit ?? 500);
  }
}
