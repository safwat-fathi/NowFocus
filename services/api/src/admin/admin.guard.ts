import { type CanActivate, type ExecutionContext, Injectable } from '@nestjs/common';
import { createHash, createHmac, timingSafeEqual } from 'node:crypto';
import { Config } from '../config.js';
import { fail } from '../errors.js';

const digest = (s: string) => createHash('sha256').update(s).digest();

export const SESSION_MS = 7 * 24 * 3600 * 1000;

/** Stateless session token "<expiry>.<hmac>"; rotating ADMIN_TOKEN invalidates all of them. */
export const signSession = (secret: string, exp: number) => `${exp}.${createHmac('sha256', secret).update(String(exp)).digest('hex')}`;

const validSession = (secret: string, given: string) => {
  const exp = Number(given.split('.')[0]);
  return exp > Date.now() && timingSafeEqual(digest(given), digest(signSession(secret, exp)));
};

/** Shared-secret bearer check. Hashing first makes the compare constant-time regardless of length. */
@Injectable()
export class AdminGuard implements CanActivate {
  constructor(private config: Config) {}

  canActivate(ctx: ExecutionContext) {
    const token = this.config.adminToken;
    if (!token) throw fail(404, 'not_found', 'Not found');
    const given = String(ctx.switchToHttp().getRequest().headers.authorization ?? '').replace(/^Bearer /, '');
    if (!timingSafeEqual(digest(given), digest(token)) && !validSession(token, given)) throw fail(401, 'unauthorized', 'Invalid admin token');
    return true;
  }
}
