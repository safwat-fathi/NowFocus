# NowFocus sync — wire format and client rules

Normative guide for people writing a client adapter (Android, macOS, iOS, Windows). [`openapi.json`](openapi.json) is the schema of the envelopes; this file is everything the schema cannot say: field mappings between the platforms, what an adapter must preserve, and the safety rules. If this file and a client disagree, the client is wrong.

Production base URL: **`https://api.nowfocus.online`** (one build constant per client, overridable in debug builds for a local server). Design background: [`docs/superpowers/specs/2026-09-29-sync-api-design.md`](../../docs/superpowers/specs/2026-09-29-sync-api-design.md).

**Slice status.** Slices 1 and 2 are implemented: `policy`, `bedtime_settings` and `session` sync on Android (`apps/android/app/src/main/kotlin/app/getnowfocus/android/sync/`) and Windows (`apps/windows/sync/`), both unreleased; macOS has a slice-1 adapter (`apps/macos/NowFocusCore/Sync/`, parked; the engine compiles into iOS too, which has no account UI yet). `shield_item` (slice 3) is specified here so the model doesn't change later, but no adapter implements it. `user_settings` exists on the server and no client has any settings to put in it.

## 1. Conventions

- JSON, camelCase keys. Enum values are **lowercase** snake_case on the wire (`bedtime_winddown`, `locked`), even where a client's own enum is UPPERCASE (Android).
- Ids are **lowercase** UUIDs. macOS and iOS generate uppercase ones, so lowercase on the way out and on ingest, and rewrite local ids once (the macOS GRDB migration does) so a local lookup and a server id always compare equal. Singletons use the id `default`.
- Timestamps are ISO-8601 with a timezone (`2026-10-01T09:30:00.000Z`). Convert from epoch-ms (Android), GRDB text `YYYY-MM-DD HH:MM:SS.SSS` UTC (macOS/iOS) or RFC3339 (Windows). Bedtime minutes are **device-local** minutes since midnight and are *not* converted; a policy that says 22:00 means 22:00 wherever the device is.
- Send an explicit `User-Agent: NowFocus-<platform>/<version>`.
- Errors are `{ statusCode, code?, message }`. Branch on `code`, never on `message`.

## 2. Authentication

| | |
|---|---|
| Register / sign in | `POST /v1/auth/register` or `/login` with `{email, password, device: {name, platform}}` → `{user, device, accessToken, refreshToken, expiresIn}`. **Every sign-in creates a new device row.** Never sign in again just to get new tokens; refresh. |
| Access token | JWT, 15 min. `Authorization: Bearer …`. |
| Refresh token | `"<deviceId>.<secret>"`, 60 days, **single use**: `POST /v1/auth/refresh {refreshToken}` returns a new pair and the old one dies. Run refresh **single-flight** (one at a time per device), and **persist the new refresh token before using the new access token**. If the process dies in between, the account is signed out and a new device row is created on the next login. |
| Storage | Platform secure storage only: Android Keystore, Keychain, Credential Manager. Exclude it from OS backups (Android `allowBackup` is on). |
| 401 | Refresh once and retry once. If refresh returns `invalid_refresh_token`, the device was revoked or the token is gone: enter the **signed-out** state (section 8). |
| 403 `wrong_password` | Wrong password on `POST /v1/me/delete`. Deliberately not 401 so you don't enter a refresh loop. |
| 429 | Auth routes allow 10 requests per minute per client IP. Honour `Retry-After`. |
| Sign out | `POST /v1/auth/logout` revokes this device. `DELETE /v1/devices/:id` revokes another one (its socket closes with `4403`). |
| Delete account | `POST /v1/me/delete {password}` → 204. Deletes the account, all devices and everything synced. Local data on the device is **kept** and keeps enforcing. Required in-app for store review. |

## 3. The sync loop

State per adapter: one `cursor` (integer, starts at 0) and per record `{serverRevision, lastPushedUpdatedAt, dirty, deleted, rawServerData}`.

```
sync():
  repeat  GET /v1/sync/pull?cursor=C        until hasMore == false     # apply every change, then C = response.cursor
  push every dirty record   POST /v1/sync/push {changes:[…≤100]}      # each change is judged on its own
```

- **Pull** returns every record with `seq > cursor`, tombstones included (`deleted: true`, `data: {}`). Cursor `0` is a full state pull. `serverTime` is trusted time.
- **Push** results per change: `applied` (store the returned `record`), `stale` (the server already has this or newer: **adopt the returned `record`** and clear `dirty`; an identical retry is also `stale`), `rejected` (see `code`).
- **Last-write-wins** types (`policy`, `bedtime_settings`, `user_settings`): a change applies only if its `updatedAt` is strictly newer than the stored one. The server clamps `updatedAt` to now + 5 min. You must **stamp `updatedAt = now` on every local edit**; a missing stamp loses to older data.
- **Deleting** a policy is a push with `deleted: true` (a tombstone, kept forever). Use a local tombstone, not a hard delete, until the push is acknowledged. Settings singletons and sessions cannot be deleted.
- Triggers: sign-in, app foreground, about 2 s after a local write (debounced), and a WebSocket `changes` message whose `cursor` is ahead of yours. The WebSocket is only an optimization; `pull` is the recovery path.
- **A signed-out device makes zero network calls.** No pull, no heartbeat, no time sync. The privacy page promises this.

Per-change rejection codes and what to do:

| `code` | Meaning | Adapter action |
|---|---|---|
| `invalid_data` / `invalid_change` / `invalid_id` | The server's validation failed (for example an unsafe domain) | Do not retry the same payload. Log, surface "couldn't sync <name>", keep the local value. |
| `policy_in_use` | A session is running on this policy and the edit would loosen it (blocklist: remove, disable or weaken a rule; allowlist: add or enable one), change `mode`, or delete it | **Adopt the server record and restore it locally** (both clients do, and stop resending). Additions are always accepted. |
| `unknown_type` / `read_only` / `not_deletable` | You pushed something the server doesn't accept (`shield_item` is written only through `/v1/always-blocked`) | A bug in the adapter. Do not retry. |
| session codes (`invalid_transition`, `immutable_field`, `end_shortened`, `session_locked`, `too_early`, `too_long`) | Slice 2 | See section 6. |

## 4. `policy`

Wire shape (extra fields are stored verbatim and returned to every device):

```json
{ "id": "…", "name": "Deep Work", "mode": "blocklist",
  "domainRules":      [{ "id": "…", "domain": "youtube.com", "includeSubdomains": true, "enabled": true }],
  "applicationRules": [{ "id": "…", "platform": "android", "nativeIdentifier": "com.google.android.youtube", "displayName": "YouTube", "enabled": true }],
  "partial": ["YT_SHORTS", "FB_REELS"] }
```

Server-enforced: `name` 1–200 chars; `mode` is `blocklist` or `allowlist`; at most 5000 `domainRules` and 2000 `applicationRules`; every `domainRules[].domain` must survive **hostname normalization** (below) and is stored in that form; every `applicationRules[]` needs a `platform` in `macos|windows|android|ios` and a `nativeIdentifier` of 1–512 characters. Anything else is kept as sent: `categories`, `notificationPolicy`, `feedRules`, future fields.

**Allowlist (`mode: "allowlist"`).** The rules are what stays open and everything else is closed, so `applicationRules` is the set of allowed apps. Clients enforce apps only: `domainRules` of an allowlist are not enforced anywhere and a client writes none. App rules are per platform, so an allowlist can hold zero rules for the platform a device runs on; **a device with none enforces nothing** (and does not start or join a session for it) instead of closing every app. `mode` is chosen when the policy is created and clients never change it: flipping a list in place would turn "block these" into "allow only these". Mid-session the server's direction flips (`policy_in_use` rejects *adding or enabling* a rule, removal is accepted, except the last allowed app of a platform, because a device with none enforces nothing), because removing an allowed app only narrows the session. `partial` still blocks inside an allowed app, so dropping one is rejected in either mode.

**Hostname normalization** (a port of the `normalize()` all three clients already share): trim, lowercase, strip `https://` then `http://`, strip one leading `www.`, cut at the first of `/ : ? #`, then require non-empty, a `.` inside, no leading or trailing `.` or `-`, and only `a-z 0-9 . -`. Unicode, wildcards and underscores are rejected (IDNs arrive as punycode `xn--…`). A domain the server rejects fails that one change with `invalid_data`.

### Per-platform mapping

| Wire | Android (`BlockPolicy`) | macOS / iOS (`BlockPolicy`) | Windows (`BlockPolicy` + feed rules) |
|---|---|---|---|
| `domainRules[]` | `domains: List<String>`. Every domain implies `includeSubdomains: true, enabled: true`. | `domains: [DomainRule]` (same shape; **rename the key**) | `domains` with `include_subdomains` (snake_case) |
| `applicationRules[]` | `apps: List<AppRule(packageName, label)>` ↔ rules with `platform: "android"`, `nativeIdentifier = packageName`, `displayName = label` | `applications: [ApplicationRule]` (**rename the key**), `platform: "macos"` | `applications` with `native_identifier`, `display_name`, `platform: "windows"` |
| `mode` | `mode: PolicyMode` (`BLOCKLIST` / `ALLOWLIST`) | `mode` (macOS/iOS enforce blocklists only, see rule 4) | `mode` |
| `partial` | `partial: Set<PartialRule>` | `partial: Set<String>` (the three URL rules, see below) | `feed_rules` rows, mapped below |
| ids | lowercase already | **uppercase today: lowercase them** | lowercase already |

### `partial` vocabulary

Exactly seven names, **UPPERCASE**: `YT_SHORTS`, `YT_HOME`, `YT_RELATED`, `FB_REELS`, `IG_REELS`, `X_FOR_YOU`, `TT_FOR_YOU` (Android's `PartialRule` enum is the source of order and spelling). The server treats the list as opaque. Which platform *enforces* which name:

| Name | Android | Windows | macOS |
|---|---|---|---|
| `YT_SHORTS` | yes (screen) | yes (`feed_key` `shorts`, URL `youtube.com/shorts`) | yes (URL) |
| `IG_REELS` | yes (screen) | yes (`reels`, URL `instagram.com/reels`, `/reel/`, `/explore`) | yes (URL) |
| `FB_REELS` | yes (screen) | yes (`fbreels`, URL `facebook.com/reel/`, `/reels`) | yes (URL) |
| `YT_HOME`, `YT_RELATED`, `X_FOR_YOU`, `TT_FOR_YOU` | yes (screen) | stored/synced (`ythome`, `xfy`), **not enforced** | preserved, not enforced |

Desktop enforcement reads the front browser tab's address in memory and closes the tab; a page section such as "the For you tab" has no URL, so those four names stay Android-only. A client never drops a name it cannot enforce: Windows maps `shorts → YT_SHORTS`, `ythome → YT_HOME`, `xfy → X_FOR_YOU`, `reels → IG_REELS`, `fbreels → FB_REELS` and preserves `YT_RELATED` and `TT_FOR_YOU`; macOS owns only the three URL names. `feedRules` is reserved and unspecified: preserve it, don't write it.

### The preserve-unknown rule (most important)

Policies are last-write-wins **as a whole**. If an adapter rebuilds a policy from its lossy local model, it deletes everything it cannot represent, for every device. So:

1. Keep the last server JSON (`rawServerData`) for every policy.
2. On a local edit, **merge only what this platform owns into that JSON** and push the result. Android owns `name`, `mode` (set when it creates a policy, never changed afterwards), the `domainRules` it can express, `applicationRules` with `platform: "android"`, and the `partial` names it knows. Other platforms' app rules, unknown `partial` names, `includeSubdomains: false`, `enabled: false`, `categories`, `notificationPolicy`, `feedRules` and unknown fields are carried over untouched.
3. A newly created policy has no raw JSON and is built from scratch.
4. **A policy with `mode: "allowlist"` must not be enforced by an adapter that cannot enforce allowlists** (macOS and iOS today; Android and Windows can): applying its rules as blocks would do the opposite of what the user set. Show it as "not supported on this device".
5. A rule for another platform (`applicationRules[].platform` not matching this device) is ignored for enforcement but never dropped.

## 5. `bedtime_settings` (singleton, id `default`)

```json
{ "enabled": true, "windDownMinute": 1320, "sleepMinute": 1380, "wakeMinute": 420, "lockAtSleep": true, "policyId": "…" }
```

Server-enforced: `enabled`/`lockAtSleep` booleans; the three minutes are integers 0–1439. `policyId` is a lowercase policy UUID or null. Android has an extra `quietNotifications`: send it, and preserve it from the server record on other platforms. Android forces `lockAtSleep` off below API 28; do not push that forced value as if the user chose it. Pushing bedtime must keep the local nightly schedule running from local state, not from the last pull.

## 6. `session` (slice 2, implemented on Android and Windows)

`{ id, policyId, sessionType: "focus"|"bedtime_winddown", source: "user"|"extension"|"schedule" (default "user"), status: "scheduled"|"active"|"completed"|"cancelled"|"expired"|"error", enforcementMode: "normal"|"strict"|"locked", startAt, endAt, … }`, extras such as `notificationMode`, `deviceId`, `policySnapshot` stored verbatim.

**`policySnapshot` of an allowlist session.** The snapshot is `{ "mode": "allowlist", "domainRules": [], "applicationRules": [], "partial": […] }`: the rule arrays are **empty on purpose**. Some installed clients join a remote session from the snapshot alone (Android 0.6 and earlier read `policySnapshot.applicationRules` as the apps to block), and an allowlist snapshot full of allowed apps would make them block exactly those apps. A receiver that understands `mode: "allowlist"` ignores the snapshot's rules and takes its own platform's apps from the synced policy; with no policy, or no apps for its platform, it does not join.

State machine on the server: statuses only move forward, terminal sessions never change, `policyId`/`sessionType`/`startAt`/`source`/`enforcementMode` are immutable, `endAt` can only be extended on a non-`normal` session, cancelling a `locked` session before `endAt` is `session_locked`, completing a non-`normal` session more than 60 s before `endAt` is `too_early`, and a session longer than 24 hours (at creation or after an extension) is `too_long`. Several sessions may run at once: an adapter that keeps an "at most one active session" invariant must **union or queue** a second running session, never silently replace enforcement. Android's stored status can stay `ACTIVE` after `endAt`; evaluate the status from the clock before pushing.

## 7. `shield_item` (slice 3, read-only)

Written only by `POST /v1/always-blocked/items` (and `cancel-grace`, `recommit`, `DELETE`) with **server-stamped** `lockedAt`, `graceExpiresAt` (+60 s) and `lockedUntil` (+14 days). Adding needs connectivity. `status` in a pulled record is correct only as of `serverTime`; recompute it from `graceExpiresAt`/`lockedUntil`. A shield created offline stays local, and when it is later adopted by the server **the lock restarts from the time of adoption** (never shorter, possibly longer): show the new end date. **Never remove a local shield because the server doesn't have it.**

## 8. Safety rules (not negotiable)

1. **Local-first.** Enforcement reads only local state. A failed, slow or rejected sync never changes what is being blocked, and a running session keeps enforcing with the server unreachable.
2. **Never drop local shield or locked-session state** on sign-out, password change, account deletion, token loss or account switch. Wiping local state and signing in fresh would be a shield bypass. Sign-out keeps all local data; it only forgets the tokens and the cursor.
3. **Signed out = silent.** No network traffic of any kind, including time heartbeats.
4. **Validate what the server sends before acting on it**, exactly like user input. Run `DomainValidation.normalize` on every pulled domain *at the point where it reaches a hosts-file or DNS writer*, not only on ingest. The server also normalizes, but a compromised account or a future server bug must not be able to put a newline into a root-owned file.
5. Don't claim tamper-proof enforcement anywhere in UI or copy.
6. Never sync browsing history, block events, People, Goals, stats or voice notes. No such type exists, by design.

## 9. Realtime: `WS /ws/device`

`Authorization: Bearer <access token>` on the upgrade request (no token in the URL). The server sends `{"type":"changes","cursor":N}` on connect (the user's current cursor) and after any commit from another device; if `N` is ahead of your cursor, `sync()`. `{"type":"device_revoked"}` is followed by close code `4403`; `4401` means unauthenticated (refresh and reconnect once). The server pings every 30 s: answer pongs (every WebSocket library does this by default) and reconnect with exponential backoff (1 s up to 60 s) when the socket drops. A reconnect for the same device replaces the old socket.

## 10. Reserved behaviour

- `GET /v1/sync/pull` returning **410** will mean (Android and Windows already reset the cursor to 0 and re-pull) "your cursor is older than the server's history: discard the cursor, pull from 0 and reconcile". It is never returned today. Handle it now so tombstone compaction can ship later without breaking old clients.
- Access tokens carry `sub` (user) and `did` (device). Don't parse them; treat both tokens as opaque.
