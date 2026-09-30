import { Module } from '@nestjs/common';
import { SyncModule } from '../sync/sync.module.js';
import { ShieldController } from './shield.controller.js';
import { ShieldService } from './shield.service.js';

@Module({ imports: [SyncModule], controllers: [ShieldController], providers: [ShieldService] })
export class ShieldModule {}
