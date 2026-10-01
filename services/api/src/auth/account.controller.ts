import { Body, Controller, Get, HttpCode, Post, UseGuards } from '@nestjs/common';
import { ApiBearerAuth, ApiDefaultResponse, ApiTags } from '@nestjs/swagger';
import { ThrottlerGuard } from '@nestjs/throttler';
import { ApiError, type MeView } from '../responses.dto.js';
import { AuthGuard, Me } from './auth.guard.js';
import { AuthService, type AuthContext } from './auth.service.js';
import { DeleteAccountDto } from './auth.dto.js';

@ApiDefaultResponse({ type: ApiError }) @ApiTags('account') @ApiBearerAuth()
@Controller('v1/me')
@UseGuards(AuthGuard)
export class AccountController {
  constructor(private auth: AuthService) {}

  @Get()
  me(@Me() me: AuthContext): Promise<MeView> {
    return this.auth.me(me.userId);
  }

  /**
   * Permanently delete the account, all its devices and everything synced. Needs the password again.
   * A POST (not DELETE) because some mobile HTTP clients can't send a body with DELETE.
   * Throttled like the auth routes: this endpoint verifies a password.
   */
  @Post('delete') @HttpCode(204) @UseGuards(ThrottlerGuard)
  async delete(@Me() me: AuthContext, @Body() dto: DeleteAccountDto) {
    await this.auth.deleteAccount(me.userId, dto.password);
  }
}
