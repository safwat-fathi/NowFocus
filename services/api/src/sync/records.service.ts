import { Injectable } from '@nestjs/common';
import { InjectDataSource } from '@nestjs/typeorm';
import { Subject } from 'rxjs';
import { DataSource, EntityManager } from 'typeorm';
import { Clock } from '../clock.js';
import { shieldStatus } from '../shield/status.js';

export interface RecordRow {
  type: string;
  id: string;
  revision: number;
  seq: number;
  data: Record<string, any>;
  deleted: boolean;
  originDeviceId: string;
  updatedAt: string; // the client-supplied (clamped) timestamp LWW compares against
}

export interface Committed {
  userId: string;
  originDeviceId: string;
  cursor: number;
}

const COLS = 'type, id, revision, seq, data, client_updated_at, deleted_at, origin_device_id';
const toRow = (r: any): RecordRow => ({
  type: r.type, id: r.id, revision: r.revision, seq: Number(r.seq), data: r.data,
  deleted: r.deleted_at !== null, originDeviceId: r.origin_device_id, updatedAt: r.client_updated_at.toISOString(),
});

/** Adds read-time derived fields (currently the shield status) to a stored row. */
export function present(row: RecordRow, now: Date): RecordRow {
  return row.type === 'shield_item' && !row.deleted ? { ...row, data: { ...row.data, status: shieldStatus(row.data as any, now) } } : row;
}

/** A unit of work under the user's write lock. Every put() gets the next per-user seq. */
export class Tx {
  wrote = false;
  constructor(private m: EntityManager, readonly userId: string, readonly deviceId: string, public seq: number, readonly now: Date) {}

  async get(type: string, id: string): Promise<RecordRow | null> {
    const rows = await this.m.query(`select ${COLS} from sync_records where user_id=$1 and type=$2 and id=$3`, [this.userId, type, id]);
    return rows[0] ? toRow(rows[0]) : null;
  }

  /** All live (non-tombstoned) records of one type. */
  async list(type: string): Promise<RecordRow[]> {
    const rows = await this.m.query(`select ${COLS} from sync_records where user_id=$1 and type=$2 and deleted_at is null order by seq`, [this.userId, type]);
    return rows.map(toRow);
  }

  /** True while a not-yet-finished session that has started (and not ended) runs on this policy. */
  async hasRunningSession(policyId: string): Promise<boolean> {
    const rows = await this.m.query(
      `select 1 from sync_records
       where user_id=$1 and type='session' and deleted_at is null and data->>'policyId'=$2
         and data->>'status' in ('scheduled','active')
         and (data->>'startAt')::timestamptz <= $3 and (data->>'endAt')::timestamptz > $3
       limit 1`,
      [this.userId, policyId, this.now],
    );
    return rows.length > 0;
  }

  async put(r: { type: string; id: string; data: Record<string, any>; updatedAt: Date; deleted: boolean }): Promise<RecordRow> {
    const rows = await this.m.query(
      `insert into sync_records (user_id, type, id, seq, revision, data, client_updated_at, updated_at, deleted_at, origin_device_id)
       values ($1,$2,$3,$4,1,$5::jsonb,$6,$7,$8,$9)
       on conflict (user_id, type, id) do update set
         seq = excluded.seq, revision = sync_records.revision + 1, data = excluded.data,
         client_updated_at = excluded.client_updated_at, updated_at = excluded.updated_at,
         deleted_at = excluded.deleted_at, origin_device_id = excluded.origin_device_id
       returning ${COLS}`,
      [this.userId, r.type, r.id, ++this.seq, JSON.stringify(r.data), r.updatedAt, this.now, r.deleted ? this.now : null, this.deviceId],
    );
    this.wrote = true;
    return toRow(rows[0]);
  }
}

@Injectable()
export class RecordsService {
  /** Emits after a transaction that wrote something has committed (the realtime gateway listens). */
  readonly committed = new Subject<Committed>();

  constructor(@InjectDataSource() private ds: DataSource, private clock: Clock) {}

  /**
   * Runs `fn` holding a row lock on the user. The lock is released at commit, so per-user seq numbers
   * become visible in order and a client's cursor can never skip a row.
   */
  // ponytail: serializes one user's writes (fine per user); shard/queue if a single account gets hot.
  async transact<T>(userId: string, deviceId: string, fn: (tx: Tx) => Promise<T>): Promise<{ value: T; cursor: number }> {
    const out = await this.ds.transaction(async (m) => {
      const [u] = await m.query('select sync_seq from users where id=$1 for update', [userId]);
      const tx = new Tx(m, userId, deviceId, Number(u.sync_seq), this.clock.now());
      const value = await fn(tx);
      if (tx.wrote) await m.query('update users set sync_seq=$2 where id=$1', [userId, tx.seq]);
      return { value, cursor: tx.seq, wrote: tx.wrote };
    });
    if (out.wrote) this.committed.next({ userId, originDeviceId: deviceId, cursor: out.cursor });
    return { value: out.value, cursor: out.cursor };
  }

  /** The user's latest seq (0 if nothing has been written). */
  async cursorOf(userId: string): Promise<number> {
    const rows = await this.ds.query('select sync_seq from users where id=$1', [userId]);
    return Number(rows[0]?.sync_seq ?? 0);
  }

  async pull(userId: string, cursor: number, limit: number) {
    const rows = await this.ds.query(`select ${COLS} from sync_records where user_id=$1 and seq>$2 order by seq limit $3`, [userId, cursor, limit + 1]);
    const now = this.clock.now();
    const page = rows.slice(0, limit).map(toRow);
    return {
      changes: page.map((r: RecordRow) => present(r, now)),
      cursor: page.length ? page[page.length - 1].seq : cursor,
      hasMore: rows.length > limit,
      serverTime: now.toISOString(), // trusted time anchor for clients' anti-tamper checks
    };
  }
}
