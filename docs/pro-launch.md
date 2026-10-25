# Free and Pro: what is gated, the switches, and how to launch

Status: **built and dormant.** Nothing is enforced anywhere until the launch steps below are done. With every switch off, every account and every device behaves exactly as before.

## The split

| | Free | Pro |
|---|---|---|
| Blocking | Sites and apps (blocklist), the 14-day Commitment Shield, partial rules, daily limits | the same, plus **allow-only profiles** |
| Passes | A flat 5-minute pass | **Streak-scaled pass bonus** (up to 15 min; Android only, Windows has no bonus) |
| Schedules, stats, DNS choice, notification hold | yes | yes |
| Cheat day | no | **yes** |
| Bedtime Wind-Down | no | **yes** |
| Sync between devices | no (so, effectively one device) | **yes**, unlimited devices |

Not gated: the Commitment Shield (apps enforce it locally; its server endpoints exist but no app calls them, and since shield items are read back through the gated `sync/pull`, they are write-only on Free), DNS, schedules, stats. Not gated yet: one-off planned sessions (`PlannedSession.kt`, no UI yet): gate them with `canStart`/`Pro` when the UI lands.

## How an app learns its plan

The server owns it: `users.plan` (`free` or `pro`) and `users.plan_expires_at` (null = never lapses), read on every request. There is **no plan field in any response**. An app finds out from the call it already makes:

- `GET /v1/sync/pull` answers `200` for Pro and `403 pro_required` for Free. The `403` arrives before the app has changed any local state.
- The app stores one boolean next to its refresh token (Android: the `sync_auth` DataStore, Windows: Credential Manager, macOS: Keychain) and clears it on sign-out. Never asked yet, or signed out, counts as Free.
- A signed-in Free app keeps asking about every 5 minutes (not its usual 30 s) so an upgrade shows up.
- The realtime socket answers a Free account with close code `4402`.

Pro therefore needs a **signed-in account**. A signed-out app is Free. Details: `services/api/WIRE_FORMAT.md` section 9a.

## Where each gate lives

| Feature | Android | Windows | macOS | API |
|---|---|---|---|---|
| Allow-only profiles | `BlockPolicy.canStart` (`Pro.kt`) at `SessionViewModel.startSession` and `ScheduledSessions`; `NewProfileDialog`, `SetupScreen` | `AppState::check_can_start`, `create_profile` | not on macOS | |
| Streak pass bonus | `BlockedActivity` (streak counts as 0 for Free) | | | |
| Cheat day | `SessionRepository.cheatDayFlow` | `AppState::cheat`, `schedule_cheat_day` | not on macOS | |
| Bedtime Wind-Down | `BedtimeAlarmReceiver` (three gates) | `AppState::bedtime_tick` | `BedtimeScheduler.tick` | |
| Sync | `SyncApi.pull`, `SyncController` | `sync/src/api.rs`, `sync_glue.rs` | `SyncAPI.pull`, `SyncController` | `ProGuard` on `SyncController`, `DeviceGateway` |

## What happens on a downgrade

A live session is never weakened or ended. From then on: an allow-only profile stays in the list (and keeps syncing from other devices) but cannot start; a stored Cheat day stops applying and showing, and comes back with Pro; Bedtime keeps its saved settings but nothing starts or locks; passes revert to the flat length; sync stops. A cached Pro on a device that cannot reach the server stays until the next successful call (at worst until its refresh token dies, 60 days).

## The switches

| Switch | Where | Off means |
|---|---|---|
| API | `PRO_ENFORCED` in the VPS `.env` (`1` = on) | every account counts as Pro; sync is never refused |
| Android | `PRO_ENFORCED` in `app/build.gradle.kts` (QA: `-PproEnforced=true`) | every feature open, signed in or not |
| Windows | `PRO_ENFORCED` in `apps/windows/core/src/plan.rs` | same |
| macOS | `Plan.enforced` in `apps/macos/NowFocusCore/Plan.swift` | same |

Why the apps have their own switch: a signed-out app never contacts the server, so the server cannot tell it to stay open. Why the API has one: it deploys on every push to `main`, while the apps already in people's hands are older. **Releasing an app build with its switch on launches Pro for every signed-out user of that platform.** Do it only at launch.

Each app has a guard test that fails if its switch is on (`the_shipped_build_is_dormant` in `plan.rs`, the check in `scripts/CoreChecks/main.swift`). Android has none, because QA builds flip it with a Gradle property.

## Launch checklist

1. Decide who keeps Pro for free (see below) and grant them: `POST /v1/admin/plan` (recipe in `services/api/deploy/RUNBOOK.md`). Do this first, while nothing is enforced.
2. Flip the three app switches in one commit, bump versions, write release notes (Android: `docs/release-notes/android-<ver>.md`, 500 characters or less in en-US and ar), and release Android, Windows and macOS (API first for Windows: `docs/windows-release.md`).
3. Set `PRO_ENFORCED=1` in the VPS `.env`, then `pm2 restart nowfocus-api --update-env`.
4. Update the public copy that was deliberately left saying "free during early access": the pricing FAQ on `apps/web/download/` and the monetization post (+ `ar/`), `llms.txt`, `llms-full.txt`, the per-platform guides, `privacy/index.html` (the server now stores your plan; sync needs Pro) and `terms/index.html` (it says an account is "free while sync is in beta").
5. Smoke test with one Free and one Pro account on each platform: Free sees the notes and `403 pro_required`, Pro syncs, an admin grant reaches the Free device within 5 minutes (Android: on the next foreground or the 15-minute alarm).

**Rollback:** unset `PRO_ENFORCED`, restart the API. Apps with the switch on still gate their local features; to undo those, release builds with the switch off.

## Open decision before launch

Who keeps Pro without paying? Today every tester syncs and uses Bedtime, Cheat day and allow-only profiles for free, and the public pages promised "nothing is locked behind a payment". Local-only users have no account to grant Pro to, so they lose these features unless they sign in to a granted account. Pick the list, grant it in step 1, and say so on the download page.

## Not built

Checkout. Pro can only be granted by an operator today; there is no payment provider, price, or receipt handling anywhere. The plan model (`plan`, `plan_expires_at`) is what a checkout webhook would write to.
