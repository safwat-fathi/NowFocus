import { Module } from '@nestjs/common';
import { ThrottlerModule } from '@nestjs/throttler';
import { TypeOrmModule } from '@nestjs/typeorm';
import { User, WaitlistEntry, WaitlistSend } from '../db/entities.js';
import { AdminController } from './admin.controller.js';

@Module({
  imports: [TypeOrmModule.forFeature([User, WaitlistEntry, WaitlistSend]), ThrottlerModule.forRoot([{ ttl: 60_000, limit: 30 }])],
  controllers: [AdminController],
})
export class AdminModule {}
