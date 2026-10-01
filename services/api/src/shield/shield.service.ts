import { randomUUID } from 'node:crypto';
import { Injectable } from '@nestjs/common';
import type { AuthContext } from '../auth/auth.service.js';
import { fail } from '../errors.js';
import { normalizeHostname } from '../sync/hostname.js';
import { present, RecordsService, type RecordRow, type Tx } from '../sync/records.service.js';
import type { AddItemsDto } from './shield.dto.js';
import { GRACE_MS, LOCK_MS } from './status.js';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const notFound = () => fail(404, 'item_not_found', 'Shield item not found');

/**
 * The Commitment Shield lives in sync_records as type "shield_item" and is written ONLY here, from
 * server time — clients can never supply or shorten a lock. All mutations run under the user's write lock.
 */
@Injectable()
export class ShieldService {
  constructor(private records: RecordsService) {}

  async add(me: AuthContext, dto: AddItemsDto) {
    const { value } = await this.records.transact(me.userId, me.deviceId, async (tx) => {
      const live = await tx.list('shield_item');
      const batchId = randomUUID();
      const items: RecordRow[] = [];
      let created = 0;
      for (const it of dto.items) {
        // Domains are normalized like policy domains (the same string reaches a root daemon); app identifiers
        // (Android packages, iOS tokens) and category keys are case-sensitive and kept as given.
        const targetValue = it.targetType === 'domain' ? normalizeHostname(it.targetValue) : it.targetValue.trim();
        if (targetValue === null) throw fail(400, 'invalid_domain', `"${it.targetValue}" is not a valid domain`);
        const platform = it.platform ?? 'all';
        const existing = live.find((r) => r.data.targetType === it.targetType && r.data.targetValue === targetValue && r.data.platform === platform);
        if (existing) { items.push(existing); continue; } // already committed: don't restart or extend its lock
        const lockedAt = tx.now.getTime();
        const rec = await tx.put({
          type: 'shield_item', id: randomUUID(), updatedAt: tx.now, deleted: false,
          data: {
            targetType: it.targetType, targetValue, displayName: it.displayName ?? it.targetValue.trim(), platform, batchId,
            lockedAt: new Date(lockedAt).toISOString(),
            graceExpiresAt: new Date(lockedAt + GRACE_MS).toISOString(),
            lockedUntil: new Date(lockedAt + LOCK_MS).toISOString(),
          },
        });
        live.push(rec);
        items.push(rec);
        created++;
      }
      return { batchId: created ? batchId : null, items, now: tx.now };
    });
    return { batchId: value.batchId, items: value.items.map((r) => present(r, value.now)) };
  }

  /** Undo a mistaken add — only within the 60 s grace window. */
  async cancelGrace(me: AuthContext, id: string) {
    const { rec } = await this.mutate(me, id, async (tx, item) => {
      if (tx.now.getTime() > Date.parse(item.data.graceExpiresAt)) throw fail(409, 'grace_expired', 'The 60-second grace period has ended; this item is locked');
      return this.tombstone(tx, item);
    });
    return rec;
  }

  /** Remove an item — refused until its 14-day lock has elapsed. */
  async remove(me: AuthContext, id: string) {
    await this.mutate(me, id, async (tx, item) => {
      if (tx.now.getTime() < Date.parse(item.data.lockedUntil)) throw fail(403, 'locked', `Locked until ${item.data.lockedUntil}`);
      return this.tombstone(tx, item);
    });
  }

  /** After expiry, start another 14-day lock for an item that is still blocked. */
  async recommit(me: AuthContext, id: string) {
    const { rec, now } = await this.mutate(me, id, async (tx, item) => {
      if (tx.now.getTime() < Date.parse(item.data.lockedUntil)) throw fail(409, 'still_locked', 'This item is still inside its lock period');
      const rec = await tx.put({
        type: 'shield_item', id: item.id, updatedAt: tx.now, deleted: false,
        data: { ...item.data, lockedUntil: new Date(tx.now.getTime() + LOCK_MS).toISOString(), recommittedAt: tx.now.toISOString() },
      });
      return rec;
    });
    return present(rec, now);
  }

  private async mutate<T>(me: AuthContext, id: string, fn: (tx: Tx, item: RecordRow) => Promise<T>) {
    if (!UUID.test(id)) throw notFound();
    const { value } = await this.records.transact(me.userId, me.deviceId, async (tx) => {
      const item = await tx.get('shield_item', id.toLowerCase());
      if (!item || item.deleted) throw notFound();
      return { rec: await fn(tx, item), now: tx.now };
    });
    return value;
  }

  private tombstone(tx: Tx, item: RecordRow) {
    return tx.put({ type: 'shield_item', id: item.id, data: {}, updatedAt: tx.now, deleted: true });
  }
}
