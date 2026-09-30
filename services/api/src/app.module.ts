import { Module } from '@nestjs/common';
import { TypeOrmModule } from '@nestjs/typeorm';
import { AuthModule } from './auth/auth.module.js';
import { RealtimeModule } from './realtime/realtime.module.js';
import { ShieldModule } from './shield/shield.module.js';
import { SyncModule } from './sync/sync.module.js';
import { Config } from './config.js';
import { CoreModule } from './core.module.js';
import { dataSourceOptions } from './db/data-source.js';

@Module({
  imports: [
    CoreModule,
    TypeOrmModule.forRootAsync({ inject: [Config], useFactory: (c: Config) => dataSourceOptions(c.databaseUrl) }),
    AuthModule,
    SyncModule,
    ShieldModule,
    RealtimeModule,
  ],
})
export class AppModule {}
