import { Body, Controller, Delete, Get, HttpCode, Param, Post, UseGuards } from '@nestjs/common';
import { ApiBearerAuth, ApiDefaultResponse, ApiTags } from '@nestjs/swagger';
import { ThrottlerGuard } from '@nestjs/throttler';
import { ApiError, type AuthSession, type DeviceView, type TokenPair } from '../responses.dto.js';
import { AuthGuard, Me } from './auth.guard.js';
import { AuthService, type AuthContext } from './auth.service.js';
import { CredentialsDto, RefreshDto } from './auth.dto.js';

@ApiDefaultResponse({ type: ApiError }) @ApiTags('auth')
@Controller('v1/auth')
@UseGuards(ThrottlerGuard)
export class AuthController {
  constructor(private auth: AuthService) {}

  /** Create an account and register the calling device. 10 requests/minute per client on the auth routes. */
  @Post('register')
  register(@Body() dto: CredentialsDto): Promise<AuthSession> {
    return this.auth.register(dto);
  }

  /** Log in from a (new) device. Every login registers a device. */
  @Post('login') @HttpCode(200)
  login(@Body() dto: CredentialsDto): Promise<AuthSession> {
    return this.auth.login(dto);
  }

  /** Trade a refresh token for a new pair. The old refresh token stops working. */
  @Post('refresh') @HttpCode(200)
  refresh(@Body() dto: RefreshDto): Promise<TokenPair> {
    return this.auth.refresh(dto.refreshToken);
  }

  /** Revoke the calling device. */
  @Post('logout') @HttpCode(204) @UseGuards(AuthGuard) @ApiBearerAuth()
  async logout(@Me() me: AuthContext) {
    await this.auth.revoke(me.userId, me.deviceId);
  }
}

@ApiDefaultResponse({ type: ApiError }) @ApiTags('devices') @ApiBearerAuth()
@Controller('v1/devices')
@UseGuards(AuthGuard)
export class DevicesController {
  constructor(private auth: AuthService) {}

  @Get()
  list(@Me() me: AuthContext): Promise<DeviceView[]> {
    return this.auth.listDevices(me.userId, me.deviceId);
  }

  /** Revoke a device: its tokens die immediately and its socket is closed. */
  @Delete(':id') @HttpCode(204)
  async revoke(@Me() me: AuthContext, @Param('id') id: string) {
    await this.auth.revoke(me.userId, id);
  }
}
