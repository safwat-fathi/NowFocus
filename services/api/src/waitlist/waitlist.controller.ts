import { Body, Controller, Post, UseGuards } from '@nestjs/common';
import { ApiOperation, ApiResponse, ApiTags } from '@nestjs/swagger';
import { ThrottlerGuard } from '@nestjs/throttler';
import { WaitlistDto } from './waitlist.dto.js';
import { WaitlistService } from './waitlist.service.js';

@ApiTags('waitlist')
@Controller('v1/waitlist')
@UseGuards(ThrottlerGuard) // public and unauthenticated: 5 requests/minute per client
export class WaitlistController {
  constructor(private readonly service: WaitlistService) {}

  @Post()
  @ApiOperation({ summary: 'Join the product waitlist and optionally submit a feature request' })
  @ApiResponse({ status: 201, description: 'Successfully added to waitlist' })
  async submit(@Body() dto: WaitlistDto) {
    return this.service.submit(dto);
  }
}
