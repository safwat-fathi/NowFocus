import { type CanActivate, type ExecutionContext, Injectable, createParamDecorator } from '@nestjs/common';
import { AuthService, type AuthContext } from './auth.service.js';

@Injectable()
export class AuthGuard implements CanActivate {
  constructor(private auth: AuthService) {}

  async canActivate(ctx: ExecutionContext) {
    const req = ctx.switchToHttp().getRequest();
    req.auth = await this.auth.authenticate(req.headers.authorization);
    return true;
  }
}

/** Injects the authenticated { userId, deviceId } (set by AuthGuard). */
export const Me = createParamDecorator((_: unknown, ctx: ExecutionContext): AuthContext => ctx.switchToHttp().getRequest().auth);
