import { Module } from '@nestjs/common';
import { ThrottlerModule } from '@nestjs/throttler';
import { TypeOrmModule } from '@nestjs/typeorm';
import { IssueReport } from '../db/entities.js';
import { ReportsController } from './reports.controller.js';
import { ReportsService } from './reports.service.js';

@Module({
  imports: [TypeOrmModule.forFeature([IssueReport]), ThrottlerModule.forRoot([{ ttl: 60_000, limit: 5 }])],
  controllers: [ReportsController],
  providers: [ReportsService],
})
export class ReportsModule {}
