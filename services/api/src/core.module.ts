import { Global, Module } from '@nestjs/common';
import { Clock } from './clock.js';
import { Config, loadConfig } from './config.js';

@Global()
@Module({
  providers: [Clock, { provide: Config, useFactory: () => loadConfig() }],
  exports: [Clock, Config],
})
export class CoreModule {}
