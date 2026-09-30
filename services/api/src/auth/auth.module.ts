import { Global, Module } from '@nestjs/common';
import { JwtModule } from '@nestjs/jwt';
import { ThrottlerModule } from '@nestjs/throttler';
import { TypeOrmModule } from '@nestjs/typeorm';
import { Config } from '../config.js';
import { Device, User } from '../db/entities.js';
import { AuthController, DevicesController } from './auth.controller.js';
import { AuthGuard } from './auth.guard.js';
import { AuthService } from './auth.service.js';

@Global() // AuthService/AuthGuard are reused by the sync, shield and realtime modules
@Module({
  imports: [
    TypeOrmModule.forFeature([User, Device]),
    JwtModule.registerAsync({ inject: [Config], useFactory: (c: Config) => ({ secret: c.jwtSecret }) }),
    ThrottlerModule.forRoot([{ ttl: 60_000, limit: 10 }]), // auth routes only (guard is applied per-controller)
  ],
  controllers: [AuthController, DevicesController],
  providers: [AuthService, AuthGuard],
  exports: [AuthService, AuthGuard],
})
export class AuthModule {}
