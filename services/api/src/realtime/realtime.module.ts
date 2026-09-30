import { Module } from '@nestjs/common';
import { SyncModule } from '../sync/sync.module.js';
import { DeviceGateway } from './device.gateway.js';
import { Hub } from './hub.js';

@Module({ imports: [SyncModule], providers: [Hub, DeviceGateway] })
export class RealtimeModule {}
