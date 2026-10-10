# NowFocus Sync API

NestJS service that keeps a user's Android, macOS and Windows apps in sync. Design: [`docs/superpowers/specs/2026-09-29-sync-api-design.md`](../../docs/superpowers/specs/2026-09-29-sync-api-design.md). Contract: [`openapi.json`](openapi.json) (also served at `/docs` when running).

**Writing a client adapter? Read [`WIRE_FORMAT.md`](WIRE_FORMAT.md) first.**

The apps stay local-first: they enforce from their own state and use this API only to converge. Nothing here is required for an active session to keep blocking.

## Run

```sh
cp .env.example .env            # set JWT_SECRET (>= 32 chars) and DATABASE_URL
createdb nowfocus
pnpm install && pnpm run migrate   # builds, then applies migrations
pnpm run start:dev
```

## Deploy notes

- Run exactly **one** instance (pm2 fork mode): the WebSocket hub and rate limits are in memory.
- Put the git sha in a `REVISION` file next to `dist/` when you deploy; `GET /healthz` returns it so you can confirm which build is live.
- Behind nginx and Cloudflare, `TRUST_PROXY=1` is only correct if nginx takes the client IP from `CF-Connecting-IP` (`deploy/cloudflare-realip.sh` generates the `real_ip_header` + `set_real_ip_from` file, limited to Cloudflare's ranges; skip it if nginx already has a `real_ip_header`) and sends `proxy_set_header X-Forwarded-For $remote_addr;` (overwrite, never `$proxy_add_x_forwarded_for`); `deploy/api.nowfocus.online.conf` is that vhost, `ecosystem.config.cjs` the pm2 app. Otherwise every client shares one 10/min auth bucket, or a client can spoof its IP.
- The server pings each WebSocket every 30 s, so the proxy's `proxy_read_timeout` must be above that (75 s works) and Cloudflare's idle limit is never reached.

Ops: [`deploy/RUNBOOK.md`](deploy/RUNBOOK.md) covers releases, rollback, encrypted off-box backups (`deploy/backup.sh`) and the restore drill.

## Test

Needs a local Postgres with an empty database named `nowfocus_test` (`createdb nowfocus_test`); override with `DATABASE_URL`. The e2e run **drops and recreates the `public` schema** of that database and refuses any name not ending in `_test`.

```sh
pnpm test            # unit (pure rules)
pnpm run test:e2e    # HTTP + WebSocket against real Postgres
pnpm run openapi     # regenerate openapi.json (CI fails if it is stale)
```

## Protocol in one screen

- `POST /v1/auth/register` always answers 202 `verification_sent` (new or taken email alike) and emails a confirm link; the account exists once the link is confirmed, then `POST /v1/auth/login` → per-device tokens; `POST /v1/auth/refresh` rotates; `DELETE /v1/devices/:id` revokes instantly.
- `GET /v1/me`; `POST /v1/me/delete {password}` deletes the account and everything synced (403 `wrong_password` if the password is wrong). `GET /healthz` is the ops probe (not in the contract).
- `GET /v1/sync/pull?cursor=N` returns changes with `seq > N` (tombstones included) plus `serverTime`.
- `POST /v1/sync/push` applies each change independently: `applied` | `stale` (adopt the returned record) | `rejected` (with a `code`).
- Commitment Shield is server-authoritative: `POST /v1/always-blocked/items` (server stamps a 60 s grace and a 14-day lock), `…/cancel-grace`, `…/recommit`, `DELETE` (403 while locked). Shield items arrive to other devices through `pull` as type `shield_item`.
- `WS /ws/device` (`Authorization: Bearer …`): on connect the server sends `{"type":"changes","cursor":N}` with the user's current cursor, then the same message to a user's *other* devices after any commit. If `cursor` is ahead of yours, `pull`. Close code 4401 = unauthenticated, 4403 = device revoked (also sent as `{"type":"device_revoked"}`).
- While a session runs on a policy, pushes that remove, disable or weaken its rules (or delete it) are rejected with `policy_in_use`; additions are fine.
