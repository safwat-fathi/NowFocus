import { isDeepStrictEqual } from 'node:util';
import { Injectable } from '@nestjs/common';
import { Clock } from '../clock.js';
import type { AuthContext } from '../auth/auth.service.js';
import { RecordsService, type RecordRow, type Tx } from './records.service.js';
import { checkPolicyEdit } from './policy-rules.js';
import { checkSessionChange, type SessionData } from './session-rules.js';
import { validateChange } from './validate.js';

type Result =
  | { type: unknown; id: unknown; status: 'applied' | 'stale'; record: RecordRow }
  | { type: unknown; id: unknown; status: 'rejected'; code: string; message: string };

@Injectable()
export class SyncService {
  constructor(private records: RecordsService, private clock: Clock) {}

  async push(me: AuthContext, changes: unknown[]) {
    const { value, cursor } = await this.records.transact(me.userId, me.deviceId, async (tx) => {
      const results: Result[] = [];
      for (const raw of changes) results.push(await this.apply(tx, raw)); // sequential: one lock, ordered seqs
      return results;
    });
    return { results: value, cursor, serverTime: this.clock.now().toISOString() };
  }

  pull(userId: string, cursor: number, limit: number) {
    return this.records.pull(userId, cursor, limit);
  }

  private async apply(tx: Tx, raw: unknown): Promise<Result> {
    const v = validateChange(raw, tx.now);
    const echo = { type: (raw as any)?.type, id: (raw as any)?.id };
    if (!v.ok) return { ...echo, status: 'rejected', code: v.code, message: v.message };
    const c = v.change;
    const prev = await tx.get(c.type, c.id);

    if (c.type === 'session') {
      // Sessions follow the state machine, not timestamps. An identical re-push (network retry) is a no-op.
      if (prev && !prev.deleted && isDeepStrictEqual(prev.data, c.data)) return { ...echo, status: 'stale', record: prev };
      const verdict = checkSessionChange(prev && !prev.deleted ? (prev.data as SessionData) : null, c.data as SessionData, tx.now);
      if (!verdict.ok) return { ...echo, status: 'rejected', code: verdict.code, message: verdict.message };
    } else if (prev && c.updatedAt.getTime() <= Date.parse(prev.updatedAt)) {
      // Last-write-wins: the stored record is as new or newer; the client adopts it (also covers retries).
      return { ...echo, status: 'stale', record: prev };
    }

    if (c.type === 'policy' && prev && !prev.deleted && (await tx.hasRunningSession(c.id))) {
      // A session is enforcing this policy right now: it may grow but never shrink, and cannot be deleted.
      const why = c.deleted ? 'a policy cannot be deleted while a session is running on it' : checkPolicyEdit(prev.data, c.data);
      if (why) return { ...echo, status: 'rejected', code: 'policy_in_use', message: why };
    }

    return { ...echo, status: 'applied', record: await tx.put(c) };
  }
}
