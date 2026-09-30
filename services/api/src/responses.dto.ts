import { ApiProperty } from '@nestjs/swagger';
import type { Platform } from './db/entities.js';

const INT = { type: 'integer' } as const;

/** Error body for every 4xx: `code` is stable and machine-readable, `message` is for humans. */
export class ApiError {
  @ApiProperty(INT) statusCode!: number;
  /** e.g. invalid_credentials, email_taken, unauthorized, item_not_found, locked, grace_expired, still_locked */
  code?: string;
  /** A string, or a list of strings for request-validation failures (400). */
  @ApiProperty({ oneOf: [{ type: 'string' }, { type: 'array', items: { type: 'string' } }] }) message!: string | string[];
}


/** One synced record as the server holds it. `deleted: true` is a tombstone (its `data` is `{}`). */
export class SyncRecord {
  /** policy | session | bedtime_settings | user_settings | shield_item (server-written, read-only) */
  type!: string;
  /** UUID (lowercase), or `default` for the settings singletons. */
  id!: string;
  /** Increments on every accepted write to this record. */
  @ApiProperty(INT) revision!: number;
  /** Per-user monotonic change number; the pull cursor. */
  @ApiProperty(INT) seq!: number;
  /**
   * Entity payload. Unknown fields are preserved exactly as pushed.
   * For shield items it includes a derived `status`, correct only as of `serverTime`: no new `seq` is emitted when it
   * changes with time, so clients must recompute it from `graceExpiresAt` and `lockedUntil`.
   */
  data!: Record<string, any>;
  deleted!: boolean;
  originDeviceId!: string;
  /** The (clamped) client timestamp last-write-wins compares against. */
  updatedAt!: string;
}

export class PullResponse {
  changes!: SyncRecord[];
  /** Pass this back as `cursor` next time. */
  @ApiProperty(INT) cursor!: number;
  hasMore!: boolean;
  /** Trusted server time — use it as the anti-tamper anchor for local clock checks. */
  serverTime!: string;
}

export class PushResult {
  type!: string;
  id!: string;
  /** applied: written. stale: the server already has this or newer — adopt `record`. rejected: see `code`. */
  status!: 'applied' | 'stale' | 'rejected';
  /** The server's current record (applied and stale). */
  record?: SyncRecord;
  /** unknown_type | read_only | invalid_change | invalid_id | invalid_data | not_deletable | policy_in_use | invalid_transition | immutable_field | end_shortened | session_locked | too_early */
  code?: string;
  message?: string;
}

export class PushResponse {
  results!: PushResult[];
  /** Highest seq after this push. */
  @ApiProperty(INT) cursor!: number;
  serverTime!: string;
}

export class AuthUser {
  id!: string;
  email!: string;
}

export class AuthDevice {
  id!: string;
  name!: string;
  platform!: Platform;
}

export class AuthSession {
  user!: AuthUser;
  device!: AuthDevice;
  /** Short-lived JWT (15 min). Send as `Authorization: Bearer`. */
  accessToken!: string;
  /** Single-use: every refresh returns a new one. Store in the platform's secure storage. */
  refreshToken!: string;
  /** Access token lifetime in seconds. */
  @ApiProperty(INT) expiresIn!: number;
}

export class TokenPair {
  accessToken!: string;
  refreshToken!: string;
  @ApiProperty(INT) expiresIn!: number;
}

export class DeviceView {
  id!: string;
  name!: string;
  platform!: Platform;
  createdAt!: Date;
  lastSeenAt!: Date | null;
  revokedAt!: Date | null;
  /** True for the device making this request. */
  current!: boolean;
}

export class AddItemsResponse {
  /** Shared by the items created in this call; null when every item was already committed. */
  batchId!: string | null;
  items!: SyncRecord[];
}
