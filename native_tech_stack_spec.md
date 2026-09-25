# Native Tech Stack Specification

## Purpose

This document defines the recommended native technology stack for NowFocus based on:

- `README.md`
- `product_plans.md`
- `focus_app_technical_architecture.md`

The goal is to support a privacy-first focus app that can reliably block websites and applications across platforms while keeping enforcement local, resilient, and platform-native.

The core product test is simple:

> A user can start a focus session and trust that selected distractions remain blocked until the session ends, even if the UI, network, or backend is unavailable.

## Recommendation Summary

Use a native-by-platform architecture.

Do not build the enforcement layer as one shared cross-platform app. Website blocking, app blocking, notification control, background execution, and permission models are meaningfully different on iOS, Android, Windows, and macOS.

Share the product model, schemas, fixtures, protocol contracts, and test vectors. Implement enforcement natively on each platform.

Recommended first build:

```text
Windows desktop app
+
Local DNS Proxy / System Network Filters
+
Local SQLite persistence
```

This gives the fastest path to proving the most important behavior: local, restart-safe focus session enforcement.

macOS follows as a separate native desktop implementation after the Windows prototype and reliability work. Do not start both desktop codebases at once.

## Product And Architecture Constraints

The stack must support these constraints from the docs:

- Focus sessions must continue without relying on the UI countdown.
- `startAt` and `endAt` are the source of truth for session timing.
- Enforcement must continue if the API or network is unavailable.
- The product should not transmit browsing history by default.
- Network blocking operates at the system level (e.g., local DNS proxy), protecting all browsers automatically.
- The Always-Blocked Commitment Shield operates 24/7 as an autonomous baseline filtering layer with independent 14-day lock commitments for adult content and custom distractions, independent of active focus sessions.
- Monotonic network-verified time (NTP / Server TLS sync) protects 14-day commitments against OS clock manipulation.
- Accidental locks are protected by an irrevocable 60-second grace cancellation window.
- The local DNS proxy sinkhole delivers a local Commitment Shield HTTP screen displaying remaining lock time and mindfulness prompts.
- OS-level enforcement must use OS-native APIs.
- Each enforcer must report when its protection is degraded; an active session is not proof that every blocking capability is available.
- Cross-device sync is valuable, but should come after local enforcement is reliable.
- Accounts and billing are commercial requirements, not prerequisites for proving the enforcement loop.

## Chosen Architecture

```text
                    Shared Domain Specification
                policy schema, session schema, fixtures
                              |
         +--------------------+--------------------+
         |                    |                    |
         v                    v                    v
      Windows              macOS              Android                iOS
   Tauri + Rust        Swift + SwiftUI    Kotlin + Compose       Swift + SwiftUI
         |                  |                 |                    |
         v                  v                 v                    v
  Native enforcement  Native enforcement  Native enforcement  Native enforcement
  (WFP/Local DNS)      (/etc/hosts DNS)        (VPN API)         (Screen Time)

Optional later:

Backend sync, accounts, billing, analytics
NestJS + PostgreSQL + Redis-backed workers
```

## Platform Stack

### Windows Desktop

Use Windows as the first native desktop target.

Recommended stack:

| Layer | Technology |
|---|---|
| Desktop shell | Tauri 2 |
| UI | React + TypeScript |
| Native core | Rust |
| WebView | WebView2 |
| Local database | SQLite |
| System network daemon | Windows Background Service (Rust) |
| App/process detection | Deferred: Win32 APIs in a separate native agent |
| Website blocking | Local DNS proxy or Windows Filtering Platform (WFP) |
| Packaging | MSI/EXE installer for the MVP |

Windows responsibilities:

- Create and manage focus sessions.
- Persist active sessions locally.
- Recover active sessions after restart.
- Own session lifecycle and strict-mode decisions.
- Publish an active policy snapshot to the background service daemon via IPC.
- Report daemon health and network enforcement status to the UI.
- Provide the local source of truth for the Windows device.

Use Rust for enforcement-sensitive logic:

- session engine;
- policy evaluation;
- persistence boundaries;
- future Windows Filtering Platform integration.

Use TypeScript only for UI behavior. Do not put Windows system enforcement in TypeScript.

Treat Tauri commands and IPC messages as trust boundaries: validate their schemas in Rust and persist session changes in SQLite transactions.

For the MVP, website blocking is handled entirely at the system level via a local DNS proxy or Windows Filtering Platform (WFP) running as a privileged background service. The MSI/EXE installer registers this service. By intercepting DNS resolution or filtering packets, all browsers (including Incognito mode and newly installed browsers) are automatically restricted without requiring browser extensions.

Windows application blocking is not in the first prototype. Tauri's Rust process exits when the app exits, so it cannot honestly provide restart-safe application enforcement by itself. Add Win32 foreground-window blocking only with a separately managed native agent, and only when application blocking becomes an active product requirement.

### macOS Desktop (Direct Distribution)

Build the macOS app as a separate, fully native Swift codebase with a minimum deployment target of macOS 14. Distribute it as a notarized `.dmg` outside the Mac App Store.

Direct distribution means the app is not App Sandbox-constrained by default. It does not make Screen Time or Network Extension entitlements unrestricted, nor does it make consumer software unkillable. A device owner or administrator can quit the app, disable a helper, alter network settings, or uninstall it. Locked mode is therefore a friction mechanism, not a security boundary.

Recommended stack:

| Layer | Technology |
|---|---|
| Language and concurrency | Swift + Swift Concurrency + actors |
| UI | SwiftUI, with AppKit only for menu-bar and overlay behavior |
| State | `@Observable` (macOS 14+) |
| App shell | `LSUIElement` + `MenuBarExtra` |
| Soft app blocking | `NSWorkspace` notifications + `NSRunningApplication` + `NSPanel` overlay |
| Local storage | SQLite + GRDB.swift, owned by the user-level app |
| Secrets | Keychain through `Security.framework` |
| Website blocking | LaunchDaemon + `/etc/hosts` DNS Proxy |
| Privileged IPC | XPC + `SMAppService.daemon` |
| Updates | Sparkle 2 for the user-level app |
| Distribution | Developer ID, hardened runtime, notarization, stapled `.dmg` |
| Release automation | GitHub Actions on a macOS runner with the required Xcode version |

The user-level menu-bar app owns the session engine, policy evaluation, local SQLite database, overlays, and health UI. Closing a window must not end a session; quitting the menu-bar process is an explicit bypass and must be reported if browser enforcement is also unavailable.

#### Application enforcement

Use `NSWorkspace.didLaunchApplicationNotification` and `didActivateApplicationNotification` to detect graphical applications in the current user session. Match configured bundle identifiers and show a focused `NSPanel` overlay when a blocked app becomes active. This is soft blocking.

Do not use browser-tab titles, Screen Recording permission, `SIGSTOP`, or forced termination in the MVP. Tab titles collect unnecessary browsing data; suspended processes can hold locks or become unstable; termination can lose user work. Add stronger process actions only after user research establishes a specific need and their data-loss behavior is understood.

#### Website and network enforcement

Website blocking operates exclusively at the system level via a lightweight local DNS proxy or Network Extension, known as the "Freedom Model." This mechanism eliminates the need for browser extensions entirely.

By running a privileged `LaunchDaemon` via `SMAppService`, the macOS app intercepts DNS queries or applies system-level network filters. When a focus session begins, configured domains (e.g., `instagram.com`) simply fail to resolve or are blocked at the socket layer.

**Benefits of the System-Level Freedom Model:**
- **Universal Coverage:** Covers Safari, Chrome, Edge, Firefox, Tor, Incognito mode, and even terminal requests automatically.
- **Zero Extensions Required:** Eliminates the friction of asking users to install browser extensions and avoids Manifest V3 limitations.
- **Robustness:** A privileged daemon is much harder for a user to bypass mid-session compared to disabling a browser extension.

To implement this securely:
1. Register a narrowly scoped LaunchDaemon with `SMAppService.daemon`.
2. Communicate active policies over XPC from the user-level app.
3. The daemon applies its own Network Extension configuration or local DNS proxy logic.
4. The daemon exposes network enforcement status back to the UI.

#### Privileged helper boundary

System-wide network configuration requires elevation. Register one narrowly scoped LaunchDaemon with `SMAppService.daemon` and communicate over XPC. The helper accepts only validated, versioned network-policy commands, applies/removes its own DNS proxy/Network Extension configuration, and exposes status. It must not execute arbitrary shell commands, inspect browser history, control application windows, or open the user's SQLite database. The user app sends a minimal policy snapshot over XPC; the helper owns separate privileged state.

The helper's installation, registration, and update behavior must be tested on the supported macOS versions. Do not promise a single silent administrator prompt: macOS can require explicit approval or reapproval.

#### Updates and `.dmg` release pipeline

Use the native toolchain first:

1. Archive and export with `xcodebuild`.
2. Sign the app, Sparkle helper, extensions, and any privileged component with a Developer ID Application certificate and hardened runtime.
3. Verify every nested component with `codesign --verify --strict`.
4. Submit the release artifact with `xcrun notarytool`, then staple the notarization ticket.
5. Create the drag-to-Applications `.dmg` with `hdiutil`.
6. Publish a signed Sparkle 2 feed for user-level app updates.

Sparkle updates the user-level app only. A privileged helper update is a new privileged installation/registration flow, never a silent root-level replacement.

### Android

Recommended stack:

| Layer | Technology |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose |
| State | ViewModel + Kotlin Coroutines/Flow |
| Local database | Room |
| Preferences | DataStore |
| Website blocking | DNS-aware local `VpnService` |
| App blocking | `AccessibilityService` |
| Usage data | `UsageStatsManager` |
| Notification/DND | `NotificationManager`, `AutomaticZenRule` |
| Background work | WorkManager |
| Secrets | Android Keystore |

Android responsibilities:

- Run local focus sessions.
- Persist session state in Room.
- Store lightweight preferences in DataStore.
- Block domains visible to the local VPN's DNS policy.
- Detect foreground apps through accessibility where permitted.
- Enforce Bedtime Wind-Down schedules (block high-stimulation apps, preserve emergency/alarm allowlists, and trigger DND via `AutomaticZenRule`).
- Integrate with Android DND where the user grants permission.
- Recover enforcement after process death or device restart where possible.

Android has sensitive permissions. The app must include clear user disclosure and be designed around Google Play policy review for `VpnService` and `AccessibilityService`.

The Android VPN is not a universal hostname firewall: encrypted DNS, hard-coded resolvers, shared IPs, CDNs, IPv6, proxies, and tunneling can limit domain-level blocking. The product and its enforcement status must state this limitation rather than claim complete website blocking.

### iOS

Recommended stack:

| Layer | Technology |
|---|---|
| Language | Swift |
| UI | SwiftUI |
| Concurrency | Swift Concurrency |
| Local persistence | SwiftData (iOS 17+) in an App Group container |
| Secrets | Keychain |
| Shared app/extension data | App Groups |
| App/site blocking | Family Controls + Managed Settings |
| Scheduling/monitoring | Device Activity |
| Shield UI | Managed Settings UI / Shield extensions |

iOS responsibilities:

- Request Screen Time authorization.
- Apply app and website shielding through Family Controls and Managed Settings.
- Schedule and monitor sessions through Device Activity where appropriate.
- Enforce Bedtime Wind-Down schedules via `DeviceActivitySchedule` and Managed Settings shields (shielding social, media, and browser apps while allowing Clock and Phone).
- Store local session and policy state.
- Share necessary state with extensions through App Groups.
- Keep enforcement local-first.

iOS is entitlement-dependent. Family Controls requires Apple approval before App Store distribution. That approval should be treated as a delivery dependency, not a late release checklist item.

### Backend And Sync

Do not build the backend before local enforcement is proven. This intentionally revises the older README/product-plan sequencing that places accounts in the MVP: this prototype validates enforcement first, not commercial onboarding.

When needed, recommended stack:

| Layer | Technology |
|---|---|
| API | Node.js + NestJS |
| Database | PostgreSQL |
| Realtime | WebSocket for session propagation |
| Queue/workers | Redis-backed workers |
| Auth | Email/password + OAuth |
| Billing | Stripe |
| Web dashboard | React/Next.js or equivalent |

Backend responsibilities:

- User accounts.
- Device registration.
- Subscription state.
- Cross-device policy sync.
- Optional encrypted backup of policies.
- Family and business account management.
- Aggregated analytics only where privacy policy allows.

The backend should not be required for an active local session to continue. If the API is down, local devices must continue enforcing the last valid active session.

When this phase starts, NestJS publishes an OpenAPI document in CI and the macOS client generates typed API transport models with `swift-openapi-generator`. Generated models are for backend payloads only; the language-neutral schemas in `spec/` remain the source of truth for local policy and session behavior.

## Shared Domain Layer

Create a small, language-neutral specification layer instead of a large shared runtime.

Share:

- session schema;
- policy schema;
- domain matching rules;
- protocol payloads;
- test fixtures;
- state machine definitions;
- user-facing terminology.

Do not share:

- OS enforcement code;
- platform permission logic;
- platform background execution logic;
- UI implementation;
- native service wrappers.

The shared layer should define behavior, not hide platform differences.

Store it as JSON Schema, JSON protocol examples, and JSON fixtures. Rust, Kotlin, Swift, and TypeScript each implement the contract locally. Do not make a TypeScript package the source of truth for native clients.

## Canonical Domain Model

The same model should exist on every platform.

Core entities:

- `FocusSession`
- `BlockPolicy`
- `DomainRule`
- `ApplicationRule`
- `Device`
- `SessionEvent`
- `UserSettings`

`ApplicationRule` is a conceptual selection, not a portable native identifier. Each device keeps its own enforcement binding (for example, an Android package name, Windows executable identity, or opaque iOS Family Controls token). Only the conceptual rule and supported metadata are synchronized.

Important invariant:

```text
endAt decides whether a session is active.
The countdown UI does not decide enforcement.
Every recovery path recomputes activity from persisted state and the current time.
```

Use wall-clock `endAt` as the persisted deadline and a monotonic elapsed timer while the platform provides one. Clock changes are not a security boundary: an obvious backwards jump is recorded as `clock_anomaly` and surfaced to the user rather than silently presented as a correctly timed session.

Session states:

```text
scheduled
active
completed
cancelled
expired
error
```

Session types:

```text
focus (standard work/study)
bedtime_winddown (evening phone & blue light restriction)
```

Enforcement modes:

```text
normal
strict
locked (critical for Bedtime Wind-Down to prevent sleep-deprived override)
```

Minimum persisted session fields:

```json
{
  "id": "uuid",
  "policyId": "uuid",
  "source": "user",
  "createdByExtensionId": null,
  "extensionMetadata": null,
  "sessionType": "bedtime_winddown",
  "startAt": "2026-09-19T22:30:00Z",
  "endAt": "2026-09-20T07:00:00Z",
  "pausedAt": null,
  "status": "active",
  "enforcementMode": "locked",
  "notificationMode": "normal",
  "deviceId": "uuid",
  "revision": 1,
  "createdAt": "2026-09-19T22:00:00Z",
  "completedAt": null,
  "cancelledAt": null
}
```

*Note on overnight sessions:* Bedtime Wind-Down sessions frequently cross midnight (e.g., `startAt` 22:30 on Day N, `endAt` 07:00 on Day N+1). The domain model uses absolute ISO 8601 UTC timestamps, ensuring local comparison (`now >= startAt && now < endAt`) works without date ambiguity across midnight.

*Note on Extension Compatibility:* As defined in [extension_system_architecture.md](file:///Users/safwat/Coding/Projects/side-projects/now-focus/extension_system_architecture.md), domain models include `source` (`user`, `extension`, `schedule`), `createdByExtensionId`, and `extensionMetadata` (attribution details) from day one. Active sessions coexist through policy union merge, and extension-created sessions are strictly cancellable by the user at all times.

## MVP Build Sequence

### Phase 0: Core Specification

Build first:

- policy schema;
- session schema;
- session state machine;
- domain matcher;
- test fixtures;
- local persistence contract;
- recovery, clock, and enforcement-health test vectors.

No OS integration yet.

Definition of done:

- A session can transition from scheduled to active to expired.
- Expiry is computed from `endAt`.
- A recovery check expires a session whose `endAt` is in the past.
- Domain matching handles exact domains and subdomains.
- Test fixtures can be reused by Windows, extension, Android, and iOS.

### Phase 1: Windows Prototype

Build:

- Tauri Windows app;
- Rust session engine;
- SQLite local storage;
- React/TypeScript UI;
- System network background service daemon (WFP / Local DNS);
- Native IPC between UI and daemon.

Definition of done:

- User can create a block policy.
- User can start a focus session.
- System daemon blocks selected websites automatically for all browsers.
- Session survives app restart.
- Browser restart does not bypass an active session.
- A disabled daemon or failed rule update is shown as degraded, not protected.

### Phase 2: Reliability And Strict Mode

Build:

- enforcement health and degraded-state UX;
- strict mode confirmation;
- local session events;
- basic statistics;
- better failure states.

Definition of done:

- The app reports each capability as active, degraded, or unavailable.
- Strict mode cannot be stopped accidentally.
- Local stats do not require cloud sync.
- Enforcement behavior is predictable after restart.

### Phase 3: macOS Native Direct-Distribution Desktop (Final Vision MVP)

Build:

- SwiftUI/AppKit menu-bar app;
- SQLite + GRDB local session store;
- `NSWorkspace`-based soft application blocking;
- `NSPanel` overlay;
- Privileged LaunchDaemon using `SMAppService` for system-level `/etc/hosts` DNS proxying;
- Secure XPC communication between app and daemon;
- Developer ID signing, notarization, stapled `.dmg`, and Sparkle updates.

Definition of done:

- A session remains active when the app's window closes.
- The LaunchDaemon blocks configured domains immediately via `/etc/hosts` after an XPC policy update, requiring no browser extensions.
- A blocked foreground app receives a soft-block overlay without terminating or suspending it.
- Browser, soft-application, and optional system-network enforcement are separately reported.
- Gatekeeper accepts the notarized, stapled `.dmg` on a clean supported Mac.

### Phase 4: Android

Build:

- Kotlin/Compose app;
- Room persistence;
- DataStore settings;
- DNS-aware local VPN website blocking with known limitations;
- accessibility-based app detection;
- DND integration where allowed.

Definition of done:

- Android can run local focus sessions.
- Domains visible to the VPN DNS policy are blocked; limitations are disclosed.
- Selected apps are blocked or interrupted.
- Permission state is clear to the user.
- Enforcement recovers after app process restart where possible.

### Phase 5: iOS

Build:

- SwiftUI app;
- Family Controls authorization;
- Managed Settings shielding;
- Device Activity schedules;
- App Groups state sharing.

Definition of done:

- iOS can shield selected apps/websites.
- Active session state is persisted.
- Shielding expires at the correct time.
- Entitlement path is confirmed.

### Phase 6: Backend Sync And Billing

Build only after local enforcement is reliable:

- accounts;
- device registration;
- subscriptions;
- sync API;
- optional realtime session propagation;
- encrypted policy backup where practical.

Definition of done:

- Devices can sync policies.
- A session started on one device can apply to another enrolled device.
- Local enforcement continues if the API becomes unavailable.
- No browsing history is uploaded.

## Suggested Repository Shape

Start small:

```text
focus-app/
  apps/
    windows/
    macos/
    extension/
  spec/
    schemas/
    fixtures/
    protocol/
  native_tech_stack_spec.md
```

Expand later:

```text
focus-app/
  apps/
    android/
    ios/
    macos/
    web/
  services/
    api/
    worker/
  infrastructure/
```

Do not create mobile apps, backend services, or infrastructure folders until work actually starts there.

## Key Technical Decisions

### Use native enforcement per platform

Reason:

Blocking is not a normal cross-platform UI problem. Each OS has different APIs, permissions, limitations, and review requirements.

Decision:

- Swift on iOS.
- Swift on macOS.
- Kotlin on Android.
- Rust on Windows.
- TypeScript in browser extensions.

### Use Tauri for Windows UI shell

Reason:

Tauri gives a small desktop shell while Rust can own native logic. This is a good fit for a privacy-first utility that needs local persistence and system integration.

Decision:

Use Tauri 2 with React/TypeScript for the UI and Rust for the core.

### Use native Swift for macOS

Reason:

The direct-distribution macOS app needs native menu-bar behavior, AppKit overlays, XPC, ServiceManagement, signing, and notarization. A separate Swift application makes those platform boundaries clear.

Decision:

Use SwiftUI with narrow AppKit interop and SQLite through GRDB. Start user-level; introduce a privileged XPC helper only for a validated system-network feature.

### Use browser-native blocking

Reason:

`declarativeNetRequest` lets the browser enforce blocking rules without inspecting every request in JavaScript.

Decision:

Use MV3 and `declarativeNetRequest` for Chrome/Edge.

### Use Native Messaging for Chromium desktop browser bridges

Reason:

The extension needs a local policy source without creating an unauthenticated localhost API. Native Messaging is the browser-supported bridge and keeps `declarativeNetRequest` as the enforcement layer.

Decision:

Use one allow-listed Native Messaging host per desktop implementation (e.g. Windows). The Windows Tauri app writes revisioned state transactionally; its host reads and publishes snapshots; the extension persists the last valid snapshot and reconciles it on alarms, startup, and reconnect. (Note: macOS MVP uses LaunchDaemon instead of an extension).

### Make enforcement health explicit

Reason:

An active session can coexist with a disabled extension, a revoked mobile permission, or a failed rule update. Calling that state protected would be misleading.

Decision:

Report per-capability `active`, `degraded`, or `unavailable` state. Strict mode increases stop friction; it does not turn local enforcement into a security boundary.

### Defer Windows Filtering Platform

Reason:

WFP is powerful but complex. The fastest useful validation is extension-based browser blocking plus local session reliability.

Decision:

Use the browser extension or local DNS proxy (macOS) for website blocking first. Add WFP only when the product needs stronger system-level network enforcement on Windows.

### Defer backend sync

Reason:

The product’s trust depends on local enforcement. Accounts and sync are important for paid plans, but they do not prove the core blocking loop.

Decision:

Build backend sync after the local Windows + extension prototype is reliable.

## Risks And Mitigations

| Risk | Mitigation |
|---|---|
| iOS entitlement approval delays release | Start entitlement process before committing to iOS launch dates |
| Android sensitive permissions trigger Play review issues | Design clear disclosures and permission-specific onboarding |
| System DNS proxy port conflicts or resolver overrides | Gracefully fall back to local hosts/filter injection and report degraded status |
| Local clock tampering to bypass 14-day lock | Require monotonic clock checking and network-authenticated NTP/server time validation |
| Accidental lock of essential work domains | Provide explicit confirmation modal with an irreversible 60-second cancellation grace period |
| Clock changes make expiry ambiguous | Use monotonic time where available and surface clock anomalies |
| Android domain blocking is bypassed by network behavior | Disclose DNS/VPN limitations and report reduced protection |
| macOS user-level controls can be quit or bypassed | Run LaunchDaemon process supervisor with administrative lock protection |
| macOS privileged helper expands the attack surface | Use narrow, schema-validated XPC commands with minimal privileges |
| Direct download triggers Gatekeeper warnings | Use Developer ID signing, notarization, stapling, and clean-Mac release testing |
| Backend outage weakens trust | Keep active session and Always-Blocked enforcement local |
| Cross-platform drift | Use shared fixtures and behavior tests |
| Privacy concerns | Avoid uploading browsing history and default to local stats |

## What Not To Build Yet

Do not build these in the first prototype:

- full enterprise backend;
- team/business dashboard;
- AI features;
- marketplace;
- multi-platform monorepo scaffolding for unused apps;
- custom analytics pipeline.

Add them when the local enforcement loop is already proven.

## Final Stack Decision

The recommended native stack is:

```text
Windows MVP:
Tauri 2 + React + TypeScript + Rust + SQLite

Browser MVP:
TypeScript + WebExtension MV3 + declarativeNetRequest + Native Messaging

Android:
Kotlin + Jetpack Compose + Room + DataStore + VpnService + AccessibilityService

iOS:
Swift + SwiftUI + Swift Concurrency + SwiftData + Family Controls + Managed Settings + Device Activity

macOS:
Swift + SwiftUI + AppKit + SQLite/GRDB + LaunchDaemon + SMAppService + Sparkle 2

Backend later:
NestJS + PostgreSQL + Redis-backed workers + WebSocket + Stripe
```

The first implementation should prove:

```text
Start Focus -> persist session -> publish revisioned policy -> apply and acknowledge rules -> recover -> expire correctly
```

Everything else should follow after that loop is trustworthy.
