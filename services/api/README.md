# NowFocus Sync API

NestJS service that keeps a user's Android, macOS and Windows apps in sync. Design: [`docs/superpowers/specs/2026-09-29-sync-api-design.md`](../../docs/superpowers/specs/2026-09-29-sync-api-design.md). Contract: [`openapi.json`](openapi.json) (also served at `/docs` when running).

The apps stay local-first: they enforce from their own state and use this API only to converge. Nothing here is required for an active session to keep blocking.

## Run

```sh
cp .env.example .env            # set JWT_SECRET (>= 32 chars) and DATABASE_URL
createdb nowfocus
npm ci && npm run migrate       # builds, then applies migrations
npm run start:dev
```

## Test

Needs a local Postgres with an empty database named `nowfocus_test` (`createdb nowfocus_test`); override with `DATABASE_URL`. The e2e run **drops and recreates the `public` schema** of that database and refuses any name not ending in `_test`.

```sh
npm test            # unit (pure rules)
npm run test:e2e    # HTTP + WebSocket against real Postgres
npm run openapi     # regenerate openapi.json (CI fails if it is stale)
```

## Protocol in one screen

- `POST /v1/auth/register|login` → per-device tokens; `POST /v1/auth/refresh` rotates; `DELETE /v1/devices/:id` revokes instantly.
- `GET /v1/sync/pull?cursor=N` returns changes with `seq > N` (tombstones included) plus `serverTime`.
- `POST /v1/sync/push` applies each change independently: `applied` | `stale` (adopt the returned record) | `rejected` (with a `code`).
- Commitment Shield is server-authoritative: `POST /v1/always-blocked/items` (server stamps a 60 s grace and a 14-day lock), `…/cancel-grace`, `…/recommit`, `DELETE` (403 while locked). Shield items arrive to other devices through `pull` as type `shield_item`.
- `WS /ws/device` (`Authorization: Bearer …`): on connect the server sends `{"type":"changes","cursor":N}` with the user's current cursor, then the same message to a user's *other* devices after any commit. If `cursor` is ahead of yours, `pull`. Close code 4401 = unauthenticated, 4403 = device revoked (also sent as `{"type":"device_revoked"}`).
- While a session runs on a policy, pushes that remove, disable or weaken its rules (or delete it) are rejected with `policy_in_use`; additions are fine.
