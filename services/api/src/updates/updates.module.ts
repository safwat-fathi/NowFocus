import { Module } from '@nestjs/common';
import { UpdatesController } from './updates.controller.js';
import { UpdatesService } from './updates.service.js';

@Module({ controllers: [UpdatesController], providers: [UpdatesService] })
export class UpdatesModule {}
