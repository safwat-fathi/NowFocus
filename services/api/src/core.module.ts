import { Global, Module } from '@nestjs/common';
import { Clock } from './clock.js';
import { Config, loadConfig } from './config.js';
import { Mailer } from './mail.js';

@Global()
@Module({
  providers: [Clock, Mailer, { provide: Config, useFactory: () => loadConfig() }],
  exports: [Clock, Config, Mailer],
})
export class CoreModule {}
