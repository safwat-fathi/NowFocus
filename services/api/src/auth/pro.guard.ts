import { type CanActivate, type ExecutionContext, Injectable } from '@nestjs/common';
import { AuthService } from './auth.service.js';

/** Runs after AuthGuard (which sets `req.auth`). */
@Injectable()
export class ProGuard implements CanActivate {
  constructor(private auth: AuthService) {}

  async canActivate(ctx: ExecutionContext) {
    await this.auth.requirePro(ctx.switchToHttp().getRequest().auth.userId);
    return true;
  }
}
