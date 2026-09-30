# NowFocus Sync API — Design

_Status: approved 2026-09-29 (plan mode). Round 1 = API + OpenAPI only; client adapters are follow-ups._

## Context
Android, macOS and Windows are three single-device, local-first apps. None has any HTTP/auth/device-id code (macOS hardcodes `deviceId: "local"`; the Devices screens say "sync is Phase 6"). `native_tech_stack_spec.md` (Phase 6) and `focus_app_technical_architecture.md` §23–25 already choose NestJS + PostgreSQL + WebSocket and sketch the endpoints. This round builds that backend: the **sync core only**, as a NestJS service with a published OpenAPI contract. Client adapters (Kotlin/Swift/Rust) are separate follow-up rounds, so the user's uncommitted Android/macOS work is not touched.

**Round-1 scope (user-confirmed):** API + OpenAPI only · sync of policies/rules, sessions, Commitment Shield, bedtime + user settings · email+password auth with per-device revocable tokens · change-feed push/pull protocol · readable JSON (E2E encryption later, as an envelope).
**Deferred:** Stripe/billing, orgs/family, OAuth extension API + webhooks, Redis/multi-instance, E2E encryption, People/goals (phone numbers) + session history/block-event sync, curated category bloom filters, QR/pairing codes, email verification + password reset (needs an email provider — flagged gap), client adapters.

## Hard constraints from the docs (baked into the design)
- **Local-first:** API is never required for enforcement; clients push/pull and keep enforcing last valid state offline.
- **Conceptual rules only:** server treats `nativeIdentifier` as an opaque optional string tagged with `platform`; never requires it. Clients ignore rules for other platforms.
- **Shield is server-authoritative:** server sets `lockedAt`, `graceExpiresAt = +60s`, `lockedUntil = +14d`; clients can never supply them; server also supplies trusted time.
- **Privacy:** no browsing history / `block_events` type exists in the API. Passwords via stdlib `crypto.scrypt`.
- **Forward-compat:** entity `data` is stored as jsonb and unknown fields/values round-trip untouched (Android's parser currently *drops* unknown `PartialRule` names — the server must not).

## Design

**Location/stack:** `services/api/` (matches `native_tech_stack_spec.md`). NestJS 11, TypeORM 0.3 + `pg` (migrations, `synchronize` off), class-validator, `@nestjs/swagger`, `@nestjs/jwt`, `@nestjs/throttler` (auth routes only), `@nestjs/platform-ws`. Config = one `config.ts` reading `DATABASE_URL`, `JWT_SECRET` (required, no default), `PORT`. No `@nestjs/config`, no Redis.

**Tables (4):**
- `users(id uuid, email unique lower, password_hash, sync_seq bigint, created_at)`
- `devices(id uuid, user_id, name, platform macos|windows|android|ios, refresh_hash, refresh_expires_at, created_at, last_seen_at, revoked_at)`
- `sync_records(user_id, type, id text, seq bigint, revision int, data jsonb, client_updated_at, updated_at, deleted_at, origin_device_id; PK (user_id,type,id); idx (user_id, seq))` — *all* synced entities incl. shield items.
- (no refresh_tokens / shield tables: refresh hash lives on `devices`; shield item is a `sync_records` row of type `shield_item`, written only by `ShieldService`).

**Auth (`/v1/auth/*`, `/v1/devices`):** `POST register|login` `{email,password,device:{name,platform}}` → `{user,device,accessToken(15m JWT sub=user,did=device),refreshToken("<deviceId>.<secret>", 60d)}`; each login registers a new device. `POST refresh` rotates the token. `POST logout` revokes current device. `GET /v1/devices`, `DELETE /v1/devices/:id` (revoke → closes its socket). Guard verifies JWT **and** `devices.revoked_at IS NULL` (one indexed query/request; needed for instant revocation).

**Sync protocol:**
- `GET /v1/sync/pull?cursor=N&limit=500` → `{changes:[{type,id,revision,seq,data,deleted,originDeviceId,updatedAt}], cursor, hasMore, serverTime}` (cursor 0 = full state; tombstones included; `serverTime` is the anti-tamper time anchor).
- `POST /v1/sync/push` `{changes:[{type,id,updatedAt,data,deleted?}]}` (≤100/req, 1 MB body) → per-change `{status: applied|stale|rejected, record?, code?}` + `cursor`, `serverTime`. Writable types: `policy`, `session`, `bedtime_settings`, `user_settings` (singletons use id `default`); `shield_item` → `rejected: read_only`.
- **Per-user monotonic `seq`:** `UPDATE users SET sync_seq = sync_seq+1 … RETURNING` inside the write transaction (row lock held to commit ⇒ seqs become visible in order, so a client cursor can never skip a row). *ponytail: serializes one user's writes; fine per-user.*
- **LWW types** (policy, bedtime, settings): apply iff `updatedAt` (clamped to server now+5 min) `>` stored `client_updated_at`; else `stale` + current record — a retried push is therefore idempotent, and clients simply adopt the server record on `stale`. Deletes are tombstones (`deleted_at`), kept forever *(ponytail: compaction later)*.
- **Session rules (server state machine, `session-rules.ts`, pure function):** status rank scheduled < active < terminal(completed|cancelled|expired|error); terminal never changes; no backward moves; `policyId, sessionType, startAt, enforcementMode, source` immutable after create; `endAt` may only extend unless mode is `normal`; `cancelled` on a `locked` session before `endAt` → rejected `session_locked`; `completed|expired` on non-`normal` before `endAt − 60 s` → rejected `too_early` (closes the "complete early to bypass" hole); sessions can't be deleted; multiple concurrent sessions allowed (clients union policies). Session payload is stored verbatim incl. optional opaque `policySnapshot`.

**Shield (`/v1/always-blocked/*`, doc §25):** `POST items {items:[{targetType,targetValue,displayName,platform?}]}` → one `batchId`, server stamps `lockedAt/graceExpiresAt/lockedUntil`; re-adding an already-active target is idempotent. `POST items/:id/cancel-grace` → 409 `grace_expired` after 60 s. `DELETE items/:id` → 403 `locked` while `now < lockedUntil`, else tombstone (archived). `POST items/:id/recommit` (only when unlocked_active) → new 14 d window. No PATCH. `status` (`grace_period|locked|unlocked_active|archived`) is **derived at read time**, so no cron/worker. `now` comes from an injectable `Clock` service (Node clock; tests fake it) *(ponytail: assumes NTP-synced host)*.

**Realtime:** `WS /ws/device` authenticated by `Authorization: Bearer` on upgrade (no token in URLs). After any commit the in-memory hub sends `{"type":"changes","cursor":N}` to the user's *other* sockets; revoke sends `{"type":"device_revoked"}` then closes. WS is only an optimization — recovery is always `pull`. *ponytail: in-memory hub, swap for Redis pub/sub when >1 instance.*

**Wire normalisation (client pitfalls found in exploration):** ids lowercase UUID (macOS emits uppercase → server lowercases), timestamps ISO-8601 UTC strings, camelCase JSON, enum values as in `native_tech_stack_spec.md` (`bedtime_winddown`, `locked`, …). Clients must add `updatedAt`, real `deviceId`, and tombstones in their own rounds (none of them have these today).

**Errors:** Nest default shape plus a stable `code` (`session_locked`, `too_early`, `locked`, `grace_expired`, `read_only`, `stale`). Every query is scoped by JWT `userId`.

**Module layout:** `auth/`, `devices/`, `sync/` (records service = the single write primitive, controller, per-type validators, `session-rules.ts`), `shield/`, `realtime/` (gateway + hub), `clock.ts`, `migrations/`. Entities/migration first, then modules in that order.

## Assumptions to correct if wrong
1. TypeORM chosen over Prisma/Drizzle (Nest's default path; built-in pessimistic locking/migrations).
2. LWW trusts client `updatedAt` (clamped) — clock skew can mis-order concurrent policy edits; acceptable since shield/sessions don't depend on it.
3. Shield add needs connectivity (server stamps the lock). Offline-created local commitments must be reconciled in the client round — flagged, not solved here.
4. `strict` sessions' unlock flow stays client-side (server only hard-blocks `locked`).
5. No password reset/email verification this round.

## As built (2026-09-29)

**Code:** `services/api/` · contract `services/api/openapi.json` (OpenAPI 3.1, also served at `/docs`) · CI `.github/workflows/api.yml`.

**Deviations from the plan above**
- NestJS **12** (not 11) with **Vitest 4**, TypeScript 6 and ESM (`.js` import suffixes) — the current Nest CLI's defaults; no design impact. TypeORM stays on 0.3.x.
- `services/api/.npmrc` sets `legacy-peer-deps=true`: `npm install` crashes inside npm 10.9's arborist on this tree without it. It also hides peer-range conflicts, so re-check peers when upgrading Nest addons (all currently declare Nest 12).
- The acceptance scenario is covered across `test/{auth,sync,shield,realtime}.e2e-spec.ts` (55 e2e) plus pure-rule unit specs (27), not one monolithic test.
- Added after review: **policy edit protection** (below), shield target case rules, `source` defaulting, connect-time cursor message.

**Rules added beyond the original design**
- *Policy edit protection* (mirrors `focus_app_technical_architecture.md` §4.5, all enforcement modes): while a not-finished session that has started and not ended runs on a policy, a push may add rules but is rejected with `policy_in_use` if it deletes the policy, changes its mode, or removes/disables/weakens any domain rule (identity = domain, case-insensitive), application rule (identity = platform + nativeIdentifier) or `feedRules`/`partial` entry. Without this a second device could hollow out a locked session, because macOS and Windows sessions enforce from the *live* policy.
- Shield `targetValue` is lowercased only for `domain` targets; application identifiers (case-sensitive Android package names, opaque iOS tokens) and category keys are kept verbatim (trimmed).
- A session with no `source` gets `"user"` (Android and Windows sessions have no such field). `policyId` must be a UUID: every client's bedtime session carries a real policy id.
- On WebSocket connect the server sends `{"type":"changes","cursor":N}` after registering the socket, closing the window in which a commit could land between a client's pull and its registration.

**Stable machine-readable codes**
- HTTP `code`: `invalid_credentials`, `email_taken`, `invalid_refresh_token`, `unauthorized`, `device_not_found`, `item_not_found`, `locked` (403), `grace_expired` (409), `still_locked` (409).
- Per-change push `code`: `unknown_type`, `read_only`, `invalid_change`, `invalid_id`, `invalid_data`, `not_deletable`, `policy_in_use`, `invalid_transition`, `immutable_field`, `end_shortened`, `session_locked`, `too_early`.
- WebSocket close codes: `4401` unauthenticated, `4403` device revoked.

**Notes for the client-adapter rounds (not solved here)**
- **Never drop local shield or locked-session state on logout or account switch.** The server can't see a device that logged out; wiping local state and logging into a fresh account would be a shield bypass. Enforcement stays local and is only *reconciled* with the server.
- Shield `status` in `pull` is correct only as of `serverTime`; recompute from `graceExpiresAt`/`lockedUntil`.
- Shield add currently needs connectivity (the server stamps the lock). A commitment made offline must be created locally, pushed later, and reconciled to the server's `lockedUntil`.
- All three clients keep an "at most one active session" invariant; the server permits several. An adapter receiving a second running session must union or queue it, not silently replace enforcement.
- Android snapshots the policy into the session; macOS and Windows reference the live policy. The server protects the live policy either way.
- Clients need to add: real `deviceId` (macOS hardcodes `"local"`), `updatedAt` on policies/settings, tombstones instead of hard deletes, and lowercase UUIDs (macOS emits uppercase; the server normalizes but local references should match). Android's parser drops unknown `PartialRule` names — it must keep them when round-tripping.
- Store the refresh token in platform secure storage; a refresh token is single-use and rotates on every refresh.
