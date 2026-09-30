import { Body, Controller, Delete, HttpCode, Param, Post, UseGuards } from '@nestjs/common';
import { ApiBearerAuth, ApiDefaultResponse, ApiTags } from '@nestjs/swagger';
import { AuthGuard, Me } from '../auth/auth.guard.js';
import type { AuthContext } from '../auth/auth.service.js';
import { ApiError, type AddItemsResponse, type SyncRecord } from '../responses.dto.js';
import { AddItemsDto } from './shield.dto.js';
import { ShieldService } from './shield.service.js';

// Reads go through GET /v1/sync/pull (type "shield_item"); there is deliberately no PATCH.
@ApiDefaultResponse({ type: ApiError }) @ApiTags('shield') @ApiBearerAuth()
@Controller('v1/always-blocked/items')
@UseGuards(AuthGuard)
export class ShieldController {
  constructor(private shield: ShieldService) {}

  /** Commit targets to the Commitment Shield. The server stamps lockedAt, graceExpiresAt (+60 s) and lockedUntil (+14 days); client-sent values are ignored. Re-adding an active target is a no-op. */
  @Post()
  add(@Me() me: AuthContext, @Body() dto: AddItemsDto): Promise<AddItemsResponse> {
    return this.shield.add(me, dto);
  }

  /** Undo a mistaken add. 409 `grace_expired` once the 60-second window has passed. */
  @Post(':id/cancel-grace') @HttpCode(200)
  cancelGrace(@Me() me: AuthContext, @Param('id') id: string): Promise<SyncRecord> {
    return this.shield.cancelGrace(me, id);
  }

  /** After the lock has elapsed, start another 14-day lock. 409 `still_locked` before that. */
  @Post(':id/recommit') @HttpCode(200)
  recommit(@Me() me: AuthContext, @Param('id') id: string): Promise<SyncRecord> {
    return this.shield.recommit(me, id);
  }

  /** Remove an item. 403 `locked` until lockedUntil has passed. */
  @Delete(':id') @HttpCode(204)
  async remove(@Me() me: AuthContext, @Param('id') id: string) {
    await this.shield.remove(me, id);
  }
}
