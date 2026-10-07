import { Body, Controller, Post, UseGuards } from '@nestjs/common';
import { ApiOperation, ApiResponse, ApiTags } from '@nestjs/swagger';
import { ThrottlerGuard } from '@nestjs/throttler';
import { ReportDto } from './reports.dto.js';
import { ReportsService } from './reports.service.js';

@ApiTags('reports')
@Controller('v1/reports')
@UseGuards(ThrottlerGuard) // public and unauthenticated: 5 requests/minute per client
export class ReportsController {
  constructor(private readonly service: ReportsService) {}

  @Post()
  @ApiOperation({ summary: 'Send an issue report from an app' })
  @ApiResponse({ status: 201, description: 'Report stored' })
  async submit(@Body() dto: ReportDto) {
    return this.service.submit(dto);
  }
}
