import { PLATFORMS } from '../db/entities.js';
import { normalizeHostname } from './hostname.js';
import { SESSION_MODES, SESSION_SOURCES, SESSION_STATUSES, SESSION_TYPES, type SessionData } from './session-rules.js';

export const WRITABLE_TYPES = ['policy', 'session', 'bedtime_settings', 'user_settings'] as const;
export type WritableType = (typeof WRITABLE_TYPES)[number];
const SINGLETONS: ReadonlySet<string> = new Set(['bedtime_settings', 'user_settings']);

export interface Normalized {
  type: WritableType;
  id: string;
  updatedAt: Date;
  data: Record<string, any>;
  deleted: boolean;
}
export type Rejection = { code: 'unknown_type' | 'read_only' | 'invalid_change' | 'invalid_id' | 'invalid_data' | 'not_deletable'; message: string };
export type Validated = { ok: true; change: Normalized } | ({ ok: false } & Rejection);

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const ISO = /^\d{4}-\d\d-\d\dT\d\d:\d\d(:\d\d(\.\d+)?)?(Z|[+-]\d\d:\d\d)$/;
export const MAX_CLOCK_SKEW_MS = 5 * 60_000;

const isObject = (v: unknown): v is Record<string, any> => typeof v === 'object' && v !== null && !Array.isArray(v);
const isIso = (v: unknown): v is string => typeof v === 'string' && ISO.test(v) && !Number.isNaN(Date.parse(v));
const bad = (code: Rejection['code'], message: string): Validated => ({ ok: false, code, message });

/**
 * Structural validation of one pushed change. Only fields the server enforces are checked;
 * everything else in `data` is kept verbatim so newer clients' fields survive an older server.
 * ids are lowercased (macOS emits uppercase UUIDs); `data.id` is forced to match the envelope.
 */
export function validateChange(raw: unknown, now: Date): Validated {
  if (!isObject(raw) || typeof raw.type !== 'string') return bad('invalid_change', 'change must be an object with a string type');
  if (raw.type === 'shield_item') return bad('read_only', 'shield items are managed via /v1/always-blocked');
  if (!(WRITABLE_TYPES as readonly string[]).includes(raw.type)) return bad('unknown_type', `unknown type "${raw.type}"`);
  const type = raw.type as WritableType;

  let id: string;
  if (SINGLETONS.has(type)) {
    if (raw.id !== 'default') return bad('invalid_id', `${type} has the single id "default"`);
    id = 'default';
  } else {
    if (typeof raw.id !== 'string' || !UUID.test(raw.id)) return bad('invalid_id', 'id must be a UUID');
    id = raw.id.toLowerCase();
  }

  if (!isIso(raw.updatedAt)) return bad('invalid_change', 'updatedAt must be an ISO 8601 timestamp with a timezone');
  const updatedAt = new Date(Math.min(Date.parse(raw.updatedAt), now.getTime() + MAX_CLOCK_SKEW_MS));

  if (raw.deleted === true) {
    if (type !== 'policy') return bad('not_deletable', `${type} cannot be deleted`);
    return { ok: true, change: { type, id, updatedAt, data: {}, deleted: true } };
  }
  if (!isObject(raw.data)) return bad('invalid_change', 'data must be an object');

  const data: Record<string, any> = { ...raw.data };
  const err = TYPE_RULES[type](data, id);
  return err ? bad('invalid_data', err) : { ok: true, change: { type, id, updatedAt, data, deleted: false } };
}

const idMatches = (data: Record<string, any>, id: string) => data.id === undefined || (typeof data.id === 'string' && data.id.toLowerCase() === id);
const arrayWithin = (v: unknown, max: number) => v === undefined || (Array.isArray(v) && v.length <= max);
const minute = (v: unknown) => v === undefined || (Number.isInteger(v) && (v as number) >= 0 && (v as number) < 1440);

const TYPE_RULES: Record<WritableType, (data: Record<string, any>, id: string) => string | null> = {
  policy(d, id) {
    if (!idMatches(d, id)) return 'data.id must match the change id';
    if (typeof d.name !== 'string' || d.name.length < 1 || d.name.length > 200) return 'name must be a 1-200 character string';
    if (d.mode !== undefined && d.mode !== 'blocklist' && d.mode !== 'allowlist') return 'mode must be blocklist or allowlist';
    if (!arrayWithin(d.domainRules, 5000) || !arrayWithin(d.applicationRules, 2000)) return 'domainRules/applicationRules must be arrays within size limits';
    // Trust boundary: a root daemon writes these into the hosts file. Store the normalized form so every device agrees.
    if (d.domainRules !== undefined) {
      const rules: Record<string, any>[] = [];
      for (const [i, r] of (d.domainRules as unknown[]).entries()) {
        const host = isObject(r) && typeof r.domain === 'string' ? normalizeHostname(r.domain) : null;
        if (!isObject(r) || host === null) return `domainRules[${i}].domain is not a valid hostname`;
        rules.push({ ...r, domain: host });
      }
      d.domainRules = rules;
    }
    for (const [i, r] of ((d.applicationRules ?? []) as unknown[]).entries()) {
      const ok = isObject(r) && (PLATFORMS as readonly string[]).includes(r.platform) && typeof r.nativeIdentifier === 'string' && r.nativeIdentifier.length >= 1 && r.nativeIdentifier.length <= 512;
      if (!ok) return `applicationRules[${i}] needs a platform (${PLATFORMS.join(', ')}) and a 1-512 character nativeIdentifier`;
    }
    d.id = id;
    return null;
  },
  session(d, id) {
    if (!idMatches(d, id)) return 'data.id must match the change id';
    if (typeof d.policyId !== 'string' || !UUID.test(d.policyId)) return 'policyId must be a UUID';
    if (d.source === undefined) d.source = 'user'; // Android/Windows sessions have no source field
    const oneOf = (v: unknown, set: readonly string[]) => typeof v === 'string' && set.includes(v);
    if (!oneOf(d.sessionType, SESSION_TYPES)) return `sessionType must be one of ${SESSION_TYPES.join(', ')}`;
    if (!oneOf(d.source, SESSION_SOURCES)) return `source must be one of ${SESSION_SOURCES.join(', ')}`;
    if (!oneOf(d.status, SESSION_STATUSES)) return `status must be one of ${SESSION_STATUSES.join(', ')}`;
    if (!oneOf(d.enforcementMode, SESSION_MODES)) return `enforcementMode must be one of ${SESSION_MODES.join(', ')}`;
    if (!isIso(d.startAt) || !isIso(d.endAt)) return 'startAt and endAt must be ISO 8601 timestamps with a timezone';
    if (Date.parse(d.endAt) <= Date.parse(d.startAt)) return 'endAt must be after startAt';
    d.id = id;
    d.policyId = d.policyId.toLowerCase();
    return null;
  },
  bedtime_settings(d) {
    if (d.enabled !== undefined && typeof d.enabled !== 'boolean') return 'enabled must be a boolean';
    if (d.lockAtSleep !== undefined && typeof d.lockAtSleep !== 'boolean') return 'lockAtSleep must be a boolean';
    if (![d.windDownMinute, d.sleepMinute, d.wakeMinute].every(minute)) return 'minutes must be integers 0-1439 (local minutes since midnight)';
    return null;
  },
  user_settings: () => null,
};

export type ValidSession = SessionData;
