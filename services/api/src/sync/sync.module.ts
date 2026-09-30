import { Module } from '@nestjs/common';
import { RecordsService } from './records.service.js';
import { SyncController } from './sync.controller.js';
import { SyncService } from './sync.service.js';

@Module({
  controllers: [SyncController],
  providers: [RecordsService, SyncService],
  exports: [RecordsService],
})
export class SyncModule {}
