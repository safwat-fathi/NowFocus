import { type CanActivate, type ExecutionContext, Injectable } from '@nestjs/common';
import { createHash, timingSafeEqual } from 'node:crypto';
import { Config } from '../config.js';
import { fail } from '../errors.js';

const digest = (s: string) => createHash('sha256').update(s).digest();

/** Shared-secret bearer check. Hashing first makes the compare constant-time regardless of length. */
@Injectable()
export class AdminGuard implements CanActivate {
  constructor(private config: Config) {}

  canActivate(ctx: ExecutionContext) {
    const token = this.config.adminToken;
    if (!token) throw fail(404, 'not_found', 'Not found');
    const given = String(ctx.switchToHttp().getRequest().headers.authorization ?? '').replace(/^Bearer /, '');
    if (!timingSafeEqual(digest(given), digest(token))) throw fail(401, 'unauthorized', 'Invalid admin token');
    return true;
  }
}
