# NowFocus — Cross-Platform Focus & Distraction Blocking
## Technical Architecture and Implementation Specification

**Document type:** System Architecture + Technical Design Specification  
**Status:** Proposed  
**Target:** MVP → V1 → V2  
**Platforms:** iOS, Android, Windows, macOS
**Primary product principle:** Local-first enforcement, privacy-first, Freemium SaaS model  
**Authoring perspective:** System Architect / Tech Lead  
**Last reviewed:** September 2026

---

## 1. Executive Summary

This document defines the technical architecture for a cross-platform focus application whose core job is simple:

> Start a focus session, enforce a distraction policy on the device, and make it difficult to casually escape the session.

The product is intentionally not a general productivity suite. It should not become a task manager, social network, habit tracker, AI assistant, or analytics-heavy productivity platform.

The system should be:

- Local-first.
- Freemium SaaS (Free core functionality, paid Pro/Family/Business tiers).
- Backend-powered synchronization and account management.
- Privacy-first (no advertising, no data monetization).
- No advertising.
- No telemetry by default.
- Usable without an internet connection after installation.
- Cross-platform through native enforcement adapters.
- Designed around a common `FocusSession` and `BlockPolicy` model.
- Extensible to optional cross-device synchronization later.

### Core MVP

The MVP should provide:

1. Create a focus session.
2. Select a duration.
3. Select a predefined distraction list or custom blocklist.
4. Block websites.
5. Optionally block selected applications where the operating system permits it.
6. Optionally suppress interruptions/notifications where the operating system permits it.
7. Show a countdown and session state.
8. Persist the session locally.
9. Record basic local statistics.
10. Support normal and strict/locked enforcement modes.

The product requires:

- Cloud user accounts for synchronization and billing.
- A cloud backend (API, PostgreSQL, Redis) for state management.
- Local-first enforcement (backend downtime should not break active blocking).
- Zero advertising or data-harvesting schemes.

### Commercial Model (Freemium SaaS)

The application follows a freemium model:

- **Free Tier:** Basic blocking, single device, core focus sessions.
- **Pro Tier:** Unlimited rules, advanced schedules, Bedtime Wind-Down & Sleep Guard (evening phone usage & blue light restriction), cross-device sync, AI, lock mode.
- **Family Tier:** Multi-user, child profiles, family dashboard.
- **Business Tier:** Organizations, team sessions, policies, admin dashboards.
- **Privacy Alignment:** Revenue is generated via subscriptions, ensuring no business incentive to monetize user data, collect browsing history, or serve advertisements.

---

# 2. Product and Technical Goals

## 2.1 Product goals

### P0 — Complete a focus session

The most important user flow is:

```text
Open app
  ↓
Choose blocklist
  ↓
Choose duration
  ↓
Start
  ↓
Distractions become blocked
  ↓
Timer reaches zero
  ↓
Session completed
```

### P1 — Reduce bypass opportunities

The system should make the most common escape paths inconvenient:

- Different browser.
- Different website.
- Mobile app instead of web.
- Desktop app instead of website.
- Notifications.
- Casual session cancellation.

It does not need to be a security product. A determined technical user should always be able to defeat a personal-focus tool on a device they control.

### P2 — Minimize friction

Starting a session should take less than 20 seconds after initial onboarding.

### P3 — Preserve privacy

The product should work without collecting browsing history or app usage data remotely.

### P4 — Sustainable freemium commercial model

The product is funded directly by user subscriptions (Pro/Family/Business). The commercial model must remain strictly aligned with user privacy: no ads, no trackers, and no selling of user distraction patterns or browsing history.

---

# 3. Non-Goals

The following are explicitly outside the MVP:

- Task management.
- Calendar management.
- Team productivity.
- Parental-control management.
- Remote device administration.
- Employee monitoring.
- Web content classification using AI.
- Ad blocking.
- Traffic monetization (ad networks, data brokers, proxy network monetization).
- Cloud analytics.
- Social leaderboards.
- Gamification.
- Mandatory authentication.

The architecture must not accidentally evolve into employee monitoring or parental-control infrastructure without a separate product/security review.

---

# 4. Architectural Principles

## 4.1 Local-first

The authoritative enforcement state is local to the device.

```text
             Focus Session
                   |
                   v
            Local Session State
                   |
         +---------+---------+
         |         |         |
         v         v         v
     Website      App      Notification
     Enforcer   Enforcer    Enforcer
```

The application must continue enforcing an active session if:

- Internet is unavailable.
- The cloud server is unavailable.
- The API is unavailable.
- The UI process is restarted.

## 4.2 Enforcement is platform-specific

The product must not pretend that iOS, Android, Windows, and browsers provide equivalent capabilities.

Instead:

```text
                 Focus Policy
                      |
       +-----------+--+--------+-----------+
       |           |           |           |
      iOS       Android     Windows      macOS
       |           |           |           |
  Screen Time    VPN +       WFP /      Local DNS /
  /Managed       Access-     Local DNS   Network
  Settings       ibility                 Extension
```

Each platform has an `EnforcementAdapter`.

## 4.3 Policy is the shared contract

The policy itself is platform-neutral.

The enforcement mechanism is platform-specific.

Example:

```json
{
  "mode": "block",
  "domains": [
    "youtube.com",
    "instagram.com",
    "reddit.com"
  ],
  "applications": [
    "com.example.social"
  ],
  "notifications": "quiet"
}
```

The policy does not contain iOS- or Windows-specific implementation details.

## 4.4 Fail closed for active sessions

If the UI crashes during a session, enforcement should remain active.

The correct architecture is:

```text
UI process
    |
    | starts session
    v
Native enforcement state
    |
    | survives UI restart
    v
Session remains active
```

The timer authority should be the persisted `endAt` timestamp, not a process-local countdown.

---

# 5. High-Level System Architecture

The application relies on a backend for synchronization, authentication, and billing, while enforcing rules locally on devices.

```text
                       ┌─────────────────────┐
                       │      Web App        │
                       │   User Dashboard    │
                       └──────────┬──────────┘
                                  │
                       ┌──────────▼──────────┐
                       │      API Layer      │
                       │ Authentication      │
                       │ Users & Billing     │
                       │ Focus Sessions      │
                       │ Sync                │
                       └──────────┬──────────┘
                                  │
             ┌────────────────────┼────────────────────┐
             │                    │                    │
      ┌──────▼──────┐      ┌──────▼──────┐      ┌─────▼──────┐
      │ PostgreSQL  │      │    Redis    │      │  Workers   │
      │ Users       │      │ Sessions    │      │ Jobs       │
      │ Devices     │      │ Presence    │      │ Analytics  │
      │ Rules       │      │ Sync        │      │ Notifications│
      └─────────────┘      └─────────────┘      └────────────┘
             │
             │ Synchronization
             │
      ┌──────┴───────────────────────────────┐
      │                                      │
┌─────▼─────┐                         ┌──────▼──────┐
│   Mobile  │                         │   Desktop   │
│ iOS       │                         │ Windows     │
│ Android   │                         │ macOS       │
└───────────┘                         └─────────────┘
```

 Technology Stack

## 6.1 iOS

- Language: Swift
- UI: SwiftUI
- State/concurrency: Swift Concurrency (`async/await`, actors)
- Local persistence: SwiftData or SQLite
- Secrets: Keychain
- Shared extension data: App Groups
- App/site blocking: Family Controls + Managed Settings
- Scheduled monitoring: Device Activity
- Block screen: Managed Settings UI
- Optional network filtering: Network Extension, only when platform/product approval makes sense

### Important

Family Controls distribution requires Apple's entitlement approval before App Store distribution. The architecture must treat this as a project dependency, not an afterthought.

## 6.2 Android

- Language: Kotlin
- UI: Jetpack Compose
- State: ViewModel + Kotlin Coroutines/Flow
- Local database: Room
- Small preferences/session flags: DataStore
- Website filtering: `VpnService`
- App detection/blocking: `AccessibilityService` for MVP/V1 where permitted
- Usage statistics: `UsageStatsManager`
- Notification suppression: `NotificationManager` / `AutomaticZenRule`
- Background scheduling: WorkManager where appropriate
- Secrets: Android Keystore

### Important

`AccessibilityService` and `VpnService` are sensitive platform capabilities with Google Play policy requirements. The implementation must include the required declarations, disclosure, and policy review before Play Store submission.

## 6.3 Windows

Recommended split:

- Desktop UI: Tauri 2
- UI: React + TypeScript
- Native core/enforcement: Rust
- WebView: WebView2
- Local DB: SQLite
- System networking: Windows Filtering Platform (WFP)
- Process/window detection: Win32 APIs
- Packaging: MSIX or MSI/EXE depending on native service/filter requirements
- Distribution: Direct download installer, Microsoft Store (free to download, SaaS login), and/or winget
- Crash diagnostics: local files, opt-in upload only

### Important

Do not attempt to implement Windows system enforcement entirely in TypeScript.

Use a native Rust enforcement layer.

## 6.4 macOS

Recommended split:

- UI: Swift + SwiftUI + AppKit
- Shell: `LSUIElement` + `MenuBarExtra`
- Native core/enforcement: Swift
- Local DB: SQLite + GRDB
- System networking: LaunchDaemon using `SMAppService` and `/etc/hosts` DNS Proxy
- Process/window detection: `NSWorkspace` notifications
- Privileged IPC: `NSXPCConnection`
- Packaging: Notarized `.dmg` outside Mac App Store
- Updates: Sparkle 2

### Important

The privileged helper boundary must be strict. The XPC service accepts only validated, versioned network-policy commands.

---

# 7. Shared Domain Model

The same conceptual model must exist on every platform.

## 7.1 FocusSession

```typescript
type FocusSessionStatus =
  | "scheduled"
  | "active"
  | "completed"
  | "cancelled"
  | "expired"
  | "error";

type EnforcementMode =
  | "normal"
  | "strict"
  | "locked";

type SessionType =
  | "focus"
  | "bedtime_winddown";

interface FocusSession {
  id: string;
  policyId: string;
  sessionType?: SessionType; // defaults to "focus"

  startAt: string;       // ISO-8601 UTC
  endAt: string;         // ISO-8601 UTC

  status: FocusSessionStatus;
  enforcementMode: EnforcementMode;

  notificationMode:
    | "normal"
    | "quiet"
    | "silent";

  createdAt: string;
  completedAt?: string;
  cancelledAt?: string;

  deviceId: string;
  revision: number;
}
```

## 7.2 BlockPolicy

```typescript
interface BlockPolicy {
  id: string;
  name: string;

  mode: "blocklist" | "allowlist";

  domains: DomainRule[];
  applications: ApplicationRule[];

  categories: string[];

  notificationPolicy:
    | "normal"
    | "quiet"
    | "silent";

  createdAt: string;
  updatedAt: string;
}
```

## 7.3 DomainRule

```typescript
interface DomainRule {
  id: string;

  domain: string;

  includeSubdomains: boolean;

  enabled: boolean;
}
```

Canonicalization:

```text
YouTube.com
youtube.com/
HTTPS://www.youtube.com
```

must resolve to one canonical domain representation:

```text
youtube.com
```

## 7.4 ApplicationRule

```typescript
interface ApplicationRule {
  id: string;

  platform: "ios" | "android" | "windows";

  nativeIdentifier: string;

  displayName: string;

  enabled: boolean;
}
```

The `nativeIdentifier` is platform-specific.

Examples:

```text
Android:
com.google.android.youtube

Windows:
Publisher\YouTube.exe
or normalized executable identity

iOS:
opaque Family Controls token
```

The server must never require the app's native identifier because iOS may expose opaque authorization tokens rather than conventional package IDs.

## 7.5 AlwaysBlockedItem

Represents a website, application, or curated category locked under the 24/7 Always-Blocked Commitment Shield.

```typescript
interface AlwaysBlockedItem {
  id: string;
  userId: string;

  targetType: "domain" | "application" | "category";
  targetValue: string; // e.g. "pornhub.com", "com.riotgames.leagueoflegends", or "preset:adult_content"
  displayName: string;

  platform: "all" | "macos" | "windows" | "ios" | "android";

  batchId?: string; // Identifier grouping items committed together

  lockedAt: string; // ISO 8601 timestamp of commitment
  lockedUntil: string; // ISO 8601 timestamp (lockedAt + 14 days / 336 hours)

  gracePeriodExpiresAt?: string; // ISO 8601 timestamp (lockedAt + 60 seconds)

  status: "grace_period" | "locked" | "unlocked_active" | "archived";

  createdAt: string;
  updatedAt: string;
}
```

### Item Status Lifecycle:
- `grace_period`: First 60 seconds after addition. Can be cancelled immediately if added by accident.
- `locked`: Irreversible 14-day commitment window. Deletion, editing, or bypassing is strictly prohibited by daemon and cloud.
- `unlocked_active`: 14 days have elapsed. The item **remains blocked permanently by default**, but the user is now permitted to unlock/remove it, or re-commit it to a new 14-day lock.
- `archived`: Item explicitly removed by the user after the 14-day lock expired.

## 7.6 AlwaysBlockedCategoryPreset

Represents pre-packaged curated threat and distraction blocklists (e.g., Adult Websites, Gambling).

```typescript
interface AlwaysBlockedCategoryPreset {
  id: string;
  key: "adult_content" | "gambling";
  name: string;
  description: string;
  ruleCount: number;
  bloomFilterUrl?: string; // Compressed bloom filter URL for O(1) local DNS lookups
  version: string;
  updatedAt: string;
}
```

---

# 8. Session State Machine

The state machine is central to reliability.

```text
                    +-----------+
                    | SCHEDULED |
                    +-----+-----+
                          |
                     startAt <= now
                          |
                          v
                    +-----------+
        +---------->|   ACTIVE  |<----------+
        |            +-----+-----+           |
        |                  |                 |
        |                  | endAt <= now    |
        |                  v                 |
        |            +-----------+            |
        |            | COMPLETED |            |
        |            +-----------+            |
        |                                    |
        | cancel                              |
        v                                    |
   +-----------+                             |
   | CANCELLED |                             |
   +-----------+                             |
                                             |
                 enforcement error           |
                                             v
                                      +-------------+
                                      |    ERROR    |
                                      +-------------+
```

### Rules

1. `ACTIVE` is derived from persisted timestamps.
2. The UI countdown is presentation only.
3. Enforcers independently determine whether the session is currently active.
4. Every enforcer must recover the state after process/device restart.
5. Session expiration must be idempotent.

---

# 9. Enforcement Engine Contract

Each platform adapter must implement the same conceptual interface.

```typescript
interface EnforcementAdapter {
  getCapabilities(): PlatformCapabilities;

  initialize(): Promise<void>;

  applySession(
    session: FocusSession,
    policy: BlockPolicy
  ): Promise<ApplyResult>;

  updateSession(
    session: FocusSession,
    policy: BlockPolicy
  ): Promise<ApplyResult>;

  stopSession(sessionId: string): Promise<void>;

  getStatus(sessionId: string): Promise<EnforcementStatus>;

  recover(): Promise<RecoveryResult>;
}
```

Rust/Kotlin/Swift implementations can use platform-native types internally.

---

# 10. Capability Model

```typescript
interface PlatformCapabilities {
  websiteBlocking: boolean;
  applicationBlocking: boolean;
  notificationSuppression: boolean;

  scheduledSessions: boolean;
  lockedMode: "none" | "soft" | "strong";

  customBlockPage: boolean;

  maximumDomainRules?: number;

  requiresUserPermission: boolean;
  requiresSystemExtension: boolean;
}
```

The UI should render features from capabilities rather than assuming they exist.

Example:

```text
iOS:
Website blocking             YES
Application shielding        YES
Notifications                LIMITED
Custom shield                YES
Locked mode                  SYSTEM-DEPENDENT

Android:
Website blocking             YES
Application blocking         YES*
Notification suppression     YES*
```

`*` means additional user permission and/or platform policy constraints.

---

# 11. iOS Architecture

## 11.1 iOS components

Create these targets:

```text
FocusApp
  |
  +-- FocusCore
  |
  +-- ShieldConfigurationExtension
  |
  +-- ShieldActionExtension
  |
  +-- DeviceActivityMonitorExtension
  |
  +-- optional DeviceActivityReportExtension
```

Use an App Group:

```text
group.com.yourorg.focus
```

to share session and policy state between the app and extensions.

## 11.2 Apple frameworks

Primary:

- `FamilyControls`
- `ManagedSettings`
- `ManagedSettingsUI`
- `DeviceActivity`

Potentially:

- `NetworkExtension`

### Why Family Controls

Apple provides APIs for authorizing an individual user to use parental-control-style restrictions on their own device. The app must request authorization before configuring these controls.

### Why Managed Settings

Managed Settings exposes shielding for apps and websites.

The relevant model is:

```text
ManagedSettingsStore
       |
       +-- shield.applications
       |
       +-- shield.webDomains
```

## 11.3 Authorization flow

```text
App launch
   |
   v
Check AuthorizationCenter.authorizationStatus
   |
   +-- approved --> continue
   |
   +-- notDetermined
            |
            v
requestAuthorization(for: .individual)
            |
            v
System authentication
            |
            v
Approved / denied
```

Do not ask for authorization before explaining why it is needed.

Recommended onboarding:

```text
We need Screen Time permission to block
websites and apps during your focus sessions.

No browsing history is uploaded.
Your focus rules remain on this device.

[Continue]
```

Then invoke Apple's authorization flow.

## 11.4 App selection

Use Apple's activity picker to allow the user to select apps/websites/categories.

Do not attempt to build a fake list of installed iOS applications.

Store the authorized tokens in an app-group-backed policy representation.

Important: iOS can expose opaque tokens for activities. The core domain model should therefore not depend on bundle IDs.

## 11.5 Website blocking

For immediate sessions:

```swift
let store = ManagedSettingsStore()
store.shield.webDomains = selectedWebDomainTokens
```

or the appropriate category/application policy.

Apple documents a current limit of up to 50 web domain tokens simultaneously through `ShieldSettings.webDomains`.

Architecture implication:

```text
BlockPolicy
   |
   v
iOS Token Resolver
   |
   +--> <= 50 web tokens per active shield configuration
```

If the product eventually needs larger domain sets, the architecture must either:

- use category-level controls;
- maintain several policy phases;
- use another platform-approved mechanism;
- or clearly define an iOS domain-limit product constraint.

Do not assume arbitrary-size domain blocking on iOS.

## 11.6 Block screen

Use:

- `ManagedSettingsUI`
- `ShieldConfigurationDataSource`
- `ShieldActionExtension`

Recommended UX:

```text
FOCUS MODE

This website is blocked
until 7:20 PM.

Session:
Deep Work

[Go Back]
```

Avoid an easy "Disable Focus" action in the shield.

For normal mode, the main app may allow cancellation.

For locked mode, the shield should not become a bypass path.

## 11.7 Scheduled sessions

Use:

- `DeviceActivityCenter`
- `DeviceActivitySchedule`
- `DeviceActivityMonitorExtension`

Architecture:

```text
Main App
   |
   | persist recurring schedule
   v
DeviceActivityCenter
   |
   | system invokes extension
   v
DeviceActivityMonitorExtension
   |
   | intervalDidStart
   v
ManagedSettingsStore
   |
   v
Apply shield
```

The main UI must not be required to remain running for the schedule to activate.

### Bedtime Wind-Down on iOS
- **Overnight schedule support:** `DeviceActivitySchedule` natively supports overnight intervals where `intervalStart` (e.g., 22:00) is later than `intervalEnd` (e.g., 07:00 next morning).
- **Shield targeting:** When Bedtime Wind-Down activates, `ManagedSettingsStore` shields high-stimulation categories and application tokens (social, media, entertainment, web browsers), while strictly preserving access to essential system utilities (Phone, Clock, Alarms).
- **System Sleep Focus coordination:** Prompts or integrates with Apple's Sleep Focus to silence non-critical notifications.

## 11.8 Session recovery

On launch:

```text
session = loadActiveSession()

if session.endAt <= now:
    finalize()
    clear iOS shields
else:
    ensure shields are still applied
```

The extension must apply/clear restrictions based on durable configuration.

## 11.9 Notification suppression on iOS

Do not promise arbitrary system-wide notification suppression through the Focus app.

The app can provide guidance/integration around the user's system Focus configuration, but third-party app access to system notification behavior is constrained.

Therefore:

```text
iOS notification support in MVP:
"Use Apple Focus / Do Not Disturb configuration"
```

rather than:

```text
"Focus directly blocks every notification."
```

This limitation should be visible in the product requirements.

## 11.10 Optional Network Extension

`NEFilterDataProvider` can inspect/filter network flows and make allow/block decisions. However:

- it is more complex;
- requires Network Extension capabilities;
- introduces a different entitlement/review path;
- is not necessary for the first implementation because Managed Settings already provides app/website shielding.

Use Network Extension only after proving a real requirement that Screen Time APIs cannot satisfy.

---

# 12. Android Architecture

## 12.1 Android components

```text
FocusActivity
   |
   +-- FocusViewModel
   |
   +-- SessionRepository
   |
   +-- PolicyRepository
   |
   +-- Room Database
   |
   +-- DataStore
   |
   +-- FocusAccessibilityService
   |
   +-- FocusVpnService
   |
   +-- NotificationRuleManager
   |
   +-- RecoveryWorker
```

## 12.2 Android website blocking

Recommended architecture:

```text
            Android Networking
                    |
                    v
              FocusVpnService
                    |
                 TUN iface
                    |
             +------+------+
             |             |
             v             v
          DNS query     packet flow
             |             |
             v             v
        Domain matcher   optional
             |
        +----+----+
        |         |
      block      allow
```

Use `VpnService` to create a local device-level tunnel.

The VPN should remain local.

Do not build a remote VPN server for MVP.

### Critical requirement

The product should not transmit all user traffic to your server.

For privacy:

```text
Device
  |
  +--> local VPN
          |
          +--> local policy matcher
          |
          +--> network
```

rather than:

```text
Device
  |
  +--> your remote server
          |
          +--> internet
```

Google Play's VpnService policy explicitly regulates use of this API and requires declaration/disclosure.

## 12.3 VPN policy engine

Start with DNS/domain blocking.

Pseudo-flow:

```text
DNS query: youtube.com
       |
       v
canonicalize("youtube.com")
       |
       v
PolicyTrie.contains(domain)
       |
  +----+----+
  |         |
 YES        NO
  |         |
BLOCK      ALLOW
```

Use a suffix-aware matcher.

Example:

```text
Rule:
youtube.com

Matches:
youtube.com
www.youtube.com
m.youtube.com
music.youtube.com
```

Avoid naive substring matching.

Bad:

```text
contains("youtube")
```

Good:

```text
DNS suffix match
```

### DNS limitations

A local VPN/DNS blocker can be bypassed or weakened by:

- encrypted DNS implemented outside the resolver path;
- apps using hard-coded IPs;
- QUIC/DoH/DoT behavior;
- alternate network paths;
- VPN conflicts.

Therefore the architecture must treat the VPN as a practical blocker, not an impossible-to-bypass firewall.

## 12.4 Android application blocking

There are two possible mechanisms.

### Primary MVP mechanism: AccessibilityService

Use `AccessibilityService` to observe top-level UI/window state and detect the currently foreground package.

Listen primarily for:

```text
TYPE_WINDOW_STATE_CHANGED
TYPE_WINDOWS_CHANGED
```

Do not request window-content access unless there is a demonstrated product requirement.

The minimal implementation can often use the event's `packageName`.

Flow:

```text
Accessibility event
       |
       v
packageName
       |
       v
Active policy matcher
       |
   +---+---+
   |       |
blocked   allowed
   |
   v
Launch FocusBlockedActivity
```

### Blocked activity

```text
This app is blocked

You're in a focus session
until 7:20 PM.

[Return to Focus]
```

The service should immediately return the user to the home screen or blocked activity if they attempt to reopen the target app.

### Important Play policy consideration

AccessibilityService is a sensitive API and Google Play requires policy compliance and a declaration for apps that use it outside the primary accessibility-tool category.

Before release:

- complete the Play Console declaration;
- provide prominent disclosure;
- use the API only for the described core user-facing feature;
- do not read unrelated user content;
- do not upload accessibility events.

## 12.5 Usage statistics

Use `UsageStatsManager` for:

- local statistics;
- time-spent estimates;
- app usage history;
- validating blocked-app behavior.

Do not use UsageStats as the primary real-time enforcement mechanism.

Use:

```text
AccessibilityService = real-time enforcement
UsageStatsManager = analytics/reconciliation
```

## 12.6 Android notification suppression

Use Android's notification policy/Zen facilities rather than attempting to delete every notification individually.

For modern Android versions, create/update an `AutomaticZenRule` associated with the focus session.

Example conceptual state:

```text
Focus Session Started
      |
      v
AutomaticZenRule = ACTIVE
      |
      v
Priority / None policy
```

At session completion:

```text
AutomaticZenRule = INACTIVE
```

Be careful not to overwrite the user's unrelated DND configuration.

The app should create and control **its own rule** rather than trying to replace the system's global settings.

### Bedtime Wind-Down on Android
- **Overnight schedule execution:** WorkManager schedules alarm-exact wakeups to register and activate the overnight Bedtime Wind-Down session.
- **Do Not Disturb activation:** Activates an `AutomaticZenRule` tuned to Bedtime/Priority mode, silencing disruptive late-night alerts while allowing starred contacts and alarms.
- **Evening phone restriction:** `AccessibilityService` intercepts foreground launches of high-dopamine and high-blue-light apps (social networks, video feeds, games), redirecting to a calming full-screen sleep barrier with time-to-wake display.
- **Essential allowlist:** Emergency dialer, stock Phone app, Alarms, and approved sleep audio tools (white noise, meditation) remain completely unblocked.

## 12.7 Android permissions onboarding

Permissions should be requested only when the relevant feature is enabled.

Example:

```text
Step 1:
Choose websites to block

Step 2:
Want app blocking?
[Enable App Blocking]

Step 3:
Explain Accessibility permission

Step 4:
Open Android Settings
```

Do not ask for all permissions during first launch.

## 12.8 Android persistence

Room tables:

```sql
focus_sessions
block_policies
domain_rules
application_rules
session_events
device_settings
```

DataStore:

```text
onboarding_complete
accessibility_enabled_last_known
vpn_enabled_last_known
notification_rule_id
app_preferences
```

The source of truth for active session timing remains:

```text
startAt
endAt
```

not a countdown integer.

---

# 13. Windows Architecture

## 13.1 Windows components

```text
Tauri UI
   |
   v
Rust App Core
   |
   +-------------------+
   |                   |
   v                   v
Session Store       Native Agent
                        |
              +---------+---------+
              |                   |
              v                   v
             WFP            Win32 Process
              |             / Foreground
              |                |
              v                v
         Network block      App soft-block
              |
              v
       Browser Extension
```

## 13.2 System-level Browser Blocking (Freedom Model)

The primary enforcement mechanism on Windows is system-level blocking, which automatically covers all browsers (Chrome, Edge, Firefox, Brave, etc.) without requiring browser extensions.

The Windows background service is responsible for:
- session state;
- intercepting DNS or filtering network packets;
- system-level enforcement.

## 13.3 Windows domain blocking

There are three implementation levels.

### Level A — Local DNS Proxy

A local DNS proxy intercepts all DNS queries made by the system. When a query is made for a blocked domain, the proxy returns a non-routable address (e.g., `0.0.0.0`) or NXDOMAIN.

Pros:
- Simpler than full WFP packet filtering.
- Lightweight and fast.
- Automatically handles new browser installations and Incognito modes.

Cons:
- Can be bypassed by DoH/DoT (DNS over HTTPS/TLS) if the browser is hardcoded to use it.
- To mitigate DoH bypass, the proxy must also sinkhole known public DoH endpoints.

This is the recommended V1 approach for its simplicity and broad compatibility.

### Level B — Windows Filtering Platform (WFP)

Recommended V2 system-level approach.

WFP provides hooks and a filtering engine capable of blocking traffic at multiple layers.

The native Rust module should:

1. Resolve policy domains to current destination IPs.
2. Maintain an in-memory blocked-IP set.
3. Install WFP filters for the active session.
4. Remove session filters on expiration.
5. Associate filters with the application identity where practical.
6. Keep enforcement state independent from the Tauri UI.

Conceptually:

```text
Domain policy
     |
     v
Resolver
     |
     v
IP set
     |
     v
WFP filters
     |
     v
Network blocked
```

### Limitations

Domain-to-IP enforcement has issues with:

- CDNs;
- shared IPs;
- changing IPs;
- IPv6;
- QUIC;
- encrypted DNS;
- applications that connect through proxies.

Therefore browser-level domain blocking remains important.

## 13.4 Windows application blocking

Do not start with hard execution prevention.

A self-control product is better served by two levels:

### MVP: soft application blocking

Use Win32 foreground-window APIs.

Flow:

```text
EVENT_SYSTEM_FOREGROUND
       |
       v
process ID
       |
       v
process executable/path
       |
       v
blocked?
  |
  +-- yes --> show focus window
```

The focus window should be a top-level window displayed over the blocked application.

The agent should avoid repeatedly stealing focus every few milliseconds. Use debounce and only reassert on a foreground change.

### V2: network-level process blocking

Where the product needs stronger enforcement, WFP can attach filters to application identity/network paths.

This prevents network use rather than preventing process execution.

### Hard execution blocking

AppLocker / WDAC / enterprise policy approaches are not recommended for the consumer MVP.

They introduce:

- administrative policy complexity;
- enterprise/security semantics;
- more dangerous failure modes;
- support burden.

---

# 14. System-Level DNS Proxy Architecture

The Freedom Model relies on a privileged local daemon (macOS LaunchDaemon or Windows Background Service) to intercept and filter network traffic.

## 14.1 Universal Coverage

By operating at the DNS or OS network layer, the daemon automatically covers:
- All browsers (Chrome, Safari, Edge, Firefox, Brave)
- Incognito/Private browsing modes
- Newly installed browsers
- Terminal commands (curl, wget) and other apps making network requests

No browser extensions are required.

## 14.2 Local DNS Proxy Implementation

The daemon runs a lightweight DNS proxy on a local port (e.g., `127.0.0.1:53` or a custom port bound to the OS resolver).

1. The OS is configured to route DNS queries through this proxy.
2. When a query for a blocked domain (e.g., `instagram.com`) arrives, the proxy returns `NXDOMAIN` or a non-routable IP like `0.0.0.0`.
3. Safe queries are forwarded to the user's upstream DNS provider.

## 14.3 Communication and Security

The desktop UI app runs at the user level and communicates with the privileged daemon via secure IPC (XPC on macOS, Named Pipes/LPC on Windows).
The daemon expects a valid `BlockPolicy` and `FocusSession` JSON payload to activate.

## 14.4 24/7 Baseline Filtering & Commitment Shield Screen

The daemon maintains a dual-layer evaluation pipeline:

```text
Incoming DNS / Network Request
              │
              ▼
   [Always-Blocked Layer] (24/7 Continuous Enforcement)
   - Curated Adult / Gambling Bloom Filters
   - Custom User Always-Blocked Domains
              │
         Match? ──► YES ──► Resolve to 127.0.0.1 (Local Commitment Shield Sinkhole)
              │ NO
              ▼
   [Active Session Layer] (Timed Focus Session)
   - Active Session Blocklist / Allowlist
              │
         Match? ──► YES ──► Resolve to NXDOMAIN / 0.0.0.0
              │ NO
              ▼
    Forward to Upstream DNS
```

### Commitment Shield HTTP Sinkhole
When a browser attempts to load an Always-Blocked domain:
1. The local DNS proxy resolves the domain to `127.0.0.1` (or `::1`).
2. An embedded lightweight HTTP/HTTPS daemon listening on localhost responds with the **Commitment Shield** splash screen.
3. The screen renders:
   - Shield banner: *"Always Blocked • Commitment Shield"*
   - Block details: *"Access to [domain] is strictly restricted."*
   - Countdown timer: *"Locked until [Formatted Date/Time] — [X] days, [Y] hours remaining"*
   - Mindfulness quote promoting intentionality and impulse resistance.
   - Action controls: An "Unlock" button that remains disabled with a lock icon while `lockedUntil > now()`. Once the 14-day lock expires, the button becomes active, allowing the user to remove the block or re-lock for another 14 days.

## 14.5 Application Process Supervision

The privileged daemon maintains continuous process monitoring:
- **macOS:** Subscribes to endpoint security (`EndpointSecurity.framework`) or polling process table via `proc_pidinfo`.
- **Windows:** Uses WMI process creation events (`Win32_ProcessStartTrace`) or kernel process creation callbacks (`PsSetCreateProcessNotifyRoutineEx`).
- **Mobile (iOS/Android):** FamilyControls `ManagedSettingsStore.application.blockedApplications` (iOS) or `AccessibilityService` / `AppUsage` overlay blocker (Android).

When an Always-Blocked application launches:
1. The process is terminated immediately.
2. A native OS notification is triggered: *"Always Blocked: [Application Name] was closed. Locked until [Date]."*

---

# 15. Shared Policy Compilation

The most important software abstraction is a platform-specific compiler.

```text
Canonical Policy
       │
       ├──► iOS Policy Compiler
       │
       ├──► Android Policy Compiler
       │
       ├──► Windows Policy Compiler
       │
       └──► macOS Policy Compiler
```

Each compiler translates:

```text
BlockPolicy
```

into:

```text
NativeEnforcementPlan
```

Example:

```typescript
interface NativeEnforcementPlan {
  sessionId: string;
  startAt: string;
  endAt: string;

  websites: unknown;
  applications: unknown;

  notificationPlan: unknown;
}
```

Do not force one cross-platform runtime object to represent native enforcement details.

---

# 16. Policy Versioning

Every policy update must increment a monotonically increasing revision.

```text
policy:
  id = "study"
  revision = 7
```

The native platform agent receives revision 7.

If it receives revision 6 after revision 7:

```text
reject stale update
```

This prevents race conditions when multiple UI actions happen quickly.

---

# 17. Session Consistency

The device must tolerate:

- clock changes;
- reboot;
- sleep;
- app process death;
- system resource pressure.

## 17.1 Clock model

Use wall-clock `endAt` for the user-facing deadline.

Where the platform provides monotonic timers, use both:

```text
absolute endAt
+
monotonic elapsed measurement
```

The authoritative persisted value is still `endAt`.

## 17.2 Clock manipulation

For standard focus sessions:

- detect an obvious backwards clock jump;
- mark the local session as `clock_anomaly`;
- re-evaluate using system APIs;
- do not silently extend the session indefinitely.

## 17.3 Always-Blocked 14-Day Anti-Tamper Clock Synchronization

Unlike brief 25–60 minute focus sessions, Always-Blocked items carry a strict 14-day (336-hour) commitment. Users battling compulsive habits may attempt to bypass the lock by manually changing their OS clock forward by 14 days.

The system enforces anti-tamper protections at the background daemon layer:

1. **Authoritative Network Time (NTP / Server TLS Check):**
   - When an item is locked, `lockedUntil` is anchored to trusted UTC time provided by backend sync or authenticated NTP pools.
   - The daemon maintains a periodic secure time sync heartbeat.
2. **Monotonic Uptime Counter Correlation:**
   - The daemon tracks the OS monotonic clock (`clock_gettime(CLOCK_MONOTONIC_RAW)` on macOS/Linux, `GetTickCount64()` on Windows).
   - If the system wall-clock suddenly jumps forward while monotonic uptime indicates that 14 days of elapsed time have not occurred, the jump is flagged as clock tampering.
3. **Fail-Closed Unlocking Requirement:**
   - An item's status cannot transition from `locked` to `unlocked_active` based solely on the local system clock.
   - An active network check against trusted time (or verified monotonic accumulation across boots recorded in a tamper-resistant daemon ledger) is required to certify that 14 real-world calendar days have elapsed.
4. **Daemon Uninstallation Lock:**
   - On macOS and Windows, the privileged daemon intercepts service termination or uninstallation attempts while any item has `status == "locked"` and `lockedUntil > trustedNow()`, requiring administrative confirmation and preventing casual bypass.

---

# 18. Normal / Strict / Locked Modes

## 18.1 Normal

User can stop the session.

```text
[Stop Focus]
```

## 18.2 Strict

Require deliberate confirmation.

Example:

```text
Stop session?

Type:
STOP

[Cancel] [Stop]
```

This is a friction mechanism.

## 18.3 Locked

The UI does not expose a simple stop action.

The native enforcement layer continues until `endAt`.

However:

> Locked mode is a self-control mechanism, not a security boundary.

A technically capable user who owns the device can usually disable permissions, uninstall the app, kill the environment, or otherwise bypass restrictions.

The product should not claim impossible enforcement.

## 18.4 Always-Blocked Commitment Shield (14-Day Lock-In)

The Always-Blocked Commitment Shield is the product's highest-tier self-commitment mode, specifically engineered for adult content elimination and severe addiction mitigation.

### Behavioral Rules
1. **Independent 14-Day Timer:** Every blocked domain, application, or batch of items carries its own dedicated 14-day lock timer (`lockedUntil = lockedAt + 14 days`).
2. **Batch Consistency:** If a user activates a curated category (e.g., Adult Websites preset containing thousands of domains) or locks multiple items at once, they share a common `batchId` and expiration date. Subsequent items locked individually receive their own distinct 14-day expiration timestamps.
3. **Pre-Commitment Safeguard (60-Second Grace Period):**
   - Upon clicking "Lock into Always-Blocked", a warning modal explicitly cautions the user that this action cannot be reversed for 14 days.
   - For the first 60 seconds (`status: "grace_period"`), the UI provides an emergency "Undo / Cancel" button to catch accidental lockouts (e.g., mistakenly blocking a work domain).
   - At second 61, the state transitions to `status: "locked"`. From this point forward, no cancellation, unlock, or deletion is permitted until day 14.
4. **Default Permanent Retention:** When `now >= lockedUntil`, the item does **not** automatically unblock. It remains continuously blocked (`status: "unlocked_active"`), but the user is now granted permission to remove it from the list or re-commit it for another 14-day lock.

---

# 19. Notification Strategy

The requirement must be defined as:

> Reduce interruptions during a focus session.

Not:

> Guarantee that no notification can ever appear.

Platform support:

| Platform | Strategy |
|---|---|
| iOS | Guide user to Apple Focus / system controls; no universal arbitrary notification suppression |
| Android | Automatic Zen rule / DND-based interruption suppression |
| Windows | OS notification settings integration where practical; otherwise do not promise universal suppression |
| macOS | Focus Mode / DND system integration where practical |

---

# 20. Data Architecture

## 20.1 MVP local database

Use SQLite on desktop/Android.

Use SwiftData/SQLite on iOS.

Tables:

```sql
CREATE TABLE block_policies (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  mode TEXT NOT NULL,
  source TEXT NOT NULL DEFAULT 'user',
  created_by_extension_id TEXT,
  revision INTEGER NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE focus_sessions (
  id TEXT PRIMARY KEY,
  policy_id TEXT NOT NULL,
  source TEXT NOT NULL DEFAULT 'user',
  created_by_extension_id TEXT,
  extension_metadata_json TEXT,
  start_at TEXT NOT NULL,
  end_at TEXT NOT NULL,
  paused_at TEXT,
  status TEXT NOT NULL,
  enforcement_mode TEXT NOT NULL,
  notification_mode TEXT NOT NULL,
  revision INTEGER NOT NULL,
  created_at TEXT NOT NULL,
  completed_at TEXT,
  cancelled_at TEXT
);

CREATE TABLE domain_rules (
  id TEXT PRIMARY KEY,
  policy_id TEXT NOT NULL,
  domain TEXT NOT NULL,
  include_subdomains INTEGER NOT NULL,
  enabled INTEGER NOT NULL
);

CREATE TABLE application_rules (
  id TEXT PRIMARY KEY,
  policy_id TEXT NOT NULL,
  platform TEXT NOT NULL,
  native_identifier TEXT NOT NULL,
  display_name TEXT NOT NULL,
  enabled INTEGER NOT NULL
);

CREATE TABLE session_events (
  id TEXT PRIMARY KEY,
  session_id TEXT NOT NULL,
  type TEXT NOT NULL,
  occurred_at TEXT NOT NULL,
  metadata_json TEXT
);
```

## 20.2 Session event types

```text
SESSION_CREATED
SESSION_STARTED
SESSION_COMPLETED
SESSION_CANCELLED
SESSION_EXPIRED
ENFORCEMENT_APPLIED
ENFORCEMENT_FAILED
BLOCK_ATTEMPT
PERMISSION_MISSING
RECOVERY_PERFORMED
```

For privacy, `BLOCK_ATTEMPT` should store the minimum possible information.

Prefer:

```json
{
  "category": "social",
  "targetType": "domain"
}
```

over:

```json
{
  "fullUrl": "https://youtube.com/watch?v=...."
}
```

Do not persist full browsing URLs unless there is a compelling, user-visible feature requiring them.

---

# 21. Privacy Architecture

## 21.1 Default

No data leaves the device.

## 21.2 No browsing history

The app should not collect or upload:

- full URLs;
- search queries;
- page contents;
- notification contents;
- private messages;
- accessibility screen content.

## 21.3 Optional telemetry

If telemetry is later introduced:

```text
Default = OFF
```

Opt-in metrics may include:

```text
app_version
os_version
platform
session_completed = true
session_duration_bucket
enforcement_error_code
```

Do not send raw URLs.

## 21.4 Crash reporting

Prefer local crash logs first.

Optional:

```text
Upload anonymous crash report
```

must be explicit and separately documented.

---

# 22. Security Model

This application does not require a traditional server-side security boundary in the MVP.

Primary threats:

1. User bypassing their own focus session.
2. Malicious software attempting to alter local policy.
3. Corrupted local session state.
4. Browser extension compromise.
5. Future cloud account compromise.

## 22.1 Local integrity

Use:

- file permissions;
- platform secure storage for secrets;
- atomic writes;
- checksums where useful;
- transactionally updated SQLite state.

Do not over-engineer cryptographic anti-tampering in MVP.

## 22.2 Future synchronized architecture

If cloud sync is added, use:

```text
Device A
  |
  | encrypt
  v
Ciphertext
  |
  v
Sync API
  |
  v
Database
  |
  v
Device B
  |
  | decrypt
  v
Policy
```

The server should ideally store encrypted policy/session data rather than raw browsing data.

---

# 23. Synchronization Architecture

## 23.1 Sync as a Core Feature

Synchronization is a core feature for Pro, Family, and Business plans. It ensures focus sessions and policies are universally applied across all of a user's devices.

## 23.2 Backend Stack

- API: Node.js / NestJS
- Database: PostgreSQL
- Realtime: WebSocket or SSE
- Queue: Redis-backed workers
- Authentication: Standard user accounts (Email/Password, OAuth)

## 23.3 Device identity

Each device has:

```text
deviceId
publicKey
displayName
platform
createdAt
lastSeenAt
```

Pairing can use a QR code.

Example QR payload:

```json
{
  "protocol": 1,
  "spaceId": "uuid",
  "deviceId": "uuid",
  "publicKey": "base64",
  "pairingSecret": "one-time-secret"
}
```

Do not put permanent passwords in QR codes.

---

# 24. Realtime Session Synchronization

Future flow:

```text
Laptop
   │
   │ START_SESSION / LOCK_ALWAYS_BLOCKED
   ▼
Sync API
   │
   ├────────────────────┐
   │                    │
   ▼                    ▼
Phone                Desktop / Tablet
   │                    │
activate              activate
enforcement           enforcement
```

Session messages:

```json
{
  "type": "SESSION_STARTED",
  "sessionId": "uuid",
  "revision": 12,
  "startAt": "...",
  "endAt": "...",
  "policyRevision": 4
}
```

On duplicate delivery:

```text
revision <= currentRevision
```

ignore.

This makes messages idempotent.

---

# 25. API Specification for Future Sync

## `POST /v1/devices`

Registers a device.

## `POST /v1/pairing/sessions`

Creates a pairing transaction.

## `POST /v1/focus-sessions`

Starts or stores a synchronized session.

## `GET /v1/focus-sessions/:id`

Returns encrypted session state.

## `POST /v1/focus-sessions/:id/events`

Pushes an idempotent event.

## `POST /v1/always-blocked/items`

Submits an item, category preset, or batch of items to the 24/7 Always-Blocked Commitment Shield.
The server authoritatively timestamps `lockedAt`, sets `gracePeriodExpiresAt = lockedAt + 60s`, and calculates `lockedUntil = lockedAt + 14 days`.

## `POST /v1/always-blocked/items/:id/cancel-grace`

Cancels a newly added item during its 60-second grace period. Rejected by the server if `now > gracePeriodExpiresAt`.

## `DELETE /v1/always-blocked/items/:id`

Removes an item from Always-Blocked. Strictly rejected with HTTP 403 Forbidden if `now < lockedUntil`.

## `GET /v1/always-blocked/categories`

Returns curated category presets (Adult Content, Online Gambling) including compressed bloom filter asset URLs and hash versions.

## WebSocket

```text
/ws/device
```

Events:

```text
SESSION_STARTED
SESSION_UPDATED
SESSION_CANCELLED
SESSION_COMPLETED
POLICY_UPDATED
DEVICE_REVOKED
ALWAYS_BLOCK_COMMITTED
ALWAYS_BLOCK_GRACE_CANCELLED
ALWAYS_BLOCK_LOCKED
ALWAYS_BLOCK_UNLOCKED
```

The websocket is an optimization.

A reconnecting client must always be able to recover from REST state.

## 25.1 Developer Extension API & Webhooks Specification

NowFocus exposes a public REST API and Webhook event delivery system designed for third-party developer integrations (e.g., Slack status sync, Salah/prayer time focus automation, smart home bedtime integrations).

For complete design rationale and data structures, see [extension_system_architecture.md](file:///Users/safwat/Coding/Projects/side-projects/now-focus/extension_system_architecture.md).

### Architecture & Protocol
- **Specification:** OpenAPI 3.1 single source of truth.
- **Protocol:** REST over HTTPS with JSON payloads.
- **Authentication:** OAuth 2.0 Authorization Code flow with granular resource scopes (`sessions:read`, `sessions:write`, `policies:read`, `policies:write`, `schedules:read`, `schedules:write`, `devices:read`, `webhooks:subscribe`, `commitment-shield:read`).
- **Rate Limiting:** Tiered per-user token buckets (Pro: 60 req/min, 120 burst; Business: 300 req/min, 600 burst).

### Endpoints
```text
# Focus Sessions
GET    /v1/sessions                    # List extension's sessions
POST   /v1/sessions                    # Start/schedule a session
GET    /v1/sessions/:id                # Get session state
DELETE /v1/sessions/:id                # Cancel a session
PATCH  /v1/sessions/:id/extend         # Extend active session duration

# Block Policies
GET    /v1/policies                    # List extension's policies
POST   /v1/policies                    # Create a block policy
GET    /v1/policies/:id                # Read policy details
PATCH  /v1/policies/:id                # Update rules
DELETE /v1/policies/:id                # Remove policy

# Schedules
GET    /v1/schedules                   # List recurring schedules
POST   /v1/schedules                   # Create a schedule
GET    /v1/schedules/:id               # Get schedule details
PATCH  /v1/schedules/:id               # Update schedule
DELETE /v1/schedules/:id               # Delete schedule

# Devices & State
GET    /v1/devices                     # List linked devices
GET    /v1/devices/:id/status          # Get enforcement health

# Webhook Subscriptions
GET    /v1/webhooks                    # List webhooks
POST   /v1/webhooks                    # Register webhook callback URL
DELETE /v1/webhooks/:id                # Unsubscribe webhook

# Commitment Shield (Read-Only)
GET    /v1/commitment-shield/status    # Inspect shield active status & locked categories
```

### Webhook Event Catalog
Outbound HTTP POST payloads signed with HMAC-SHA256 (`X-NowFocus-Signature`):
- `session.started`, `session.completed`, `session.cancelled`, `session.extended`, `session.paused`, `session.resumed`
- `schedule.triggered`
- `device.connected`, `device.disconnected`
- `bedtime.started`, `bedtime.ended`

### Security & Isolation Invariants
1. **Zero Client Code Execution:** Extensions run exclusively on external developer infrastructure.
2. **Strict Resource Ownership:** Extensions can only modify or cancel sessions/policies tagged with their own `created_by_extension_id`.
3. **No Lock Mode for Extensions:** Extensions cannot invoke `enforcement_mode: locked`. Attempts return HTTP 403 Forbidden.
4. **Unilateral User Cancellation:** The user can cancel any extension-created session at any time from the app UI.
5. **Commitment Shield Protection:** The Always-Blocked Commitment Shield is strictly read-only for extensions; no write endpoints exist.

---

# 26. Local IPC Specification

The UI app communicates with the privileged system daemon (macOS LaunchDaemon or Windows Service) using secure Local Inter-Process Communication (IPC).

## 26.1 IPC Mechanisms

- **macOS:** XPC via `NSXPCConnection`.
- **Windows:** Named Pipes or Local Procedure Calls (LPC).

## 26.2 Protocol

The UI sends JSON payloads to the daemon containing the active session state or Always-Blocked rules.

### Focus Session Payload
```json
{
  "type": "start_session",
  "sessionId": "ses_123",
  "policy": {
    "blockedDomains": ["youtube.com", "reddit.com"]
  },
  "endAt": "2026-09-20T10:00:00Z"
}
```

### Always-Blocked Commit Payload
```json
{
  "type": "commit_always_blocked",
  "items": [
    {
      "id": "item_abc123",
      "targetType": "domain",
      "targetValue": "adult-example.com",
      "displayName": "Adult Example",
      "lockedAt": "2026-09-20T03:00:00Z",
      "lockedUntil": "2026-10-04T03:00:00Z",
      "gracePeriodExpiresAt": "2026-09-20T03:01:00Z",
      "status": "grace_period"
    }
  ]
}
```

The daemon verifies the payload, updates its persistent local database, binds rules to its local DNS proxy and process monitor, and responds:

```json
{
  "type": "ack",
  "status": "enforcing",
  "activeRules": 2
}
```

---

# 27. Core Repository Structure

Recommended monorepo:

```text
focus/
├── apps/
│   ├── web/
│   │
│   ├── windows/
│   │   ├── src/
│   │   ├── src-tauri/
│   │   └── native/
│   │
│   ├── android/
│   │   ├── app/
│   │   ├── accessibility/
│   │   ├── vpn/
│   │   └── notifications/
│   │
│   └── ios/
│       ├── FocusApp/
│       ├── ShieldConfiguration/
│       ├── ShieldAction/
│       └── DeviceActivityMonitor/
│
├── packages/
│   ├── focus-schema/
│   ├── focus-types/
│   ├── policy-fixtures/
│   └── extension/
│
├── services/
│   └── sync-api/              # V1, not MVP
│
├── docs/
│   ├── architecture/
│   ├── platform/
│   └── security/
│
└── tooling/
    ├── build/
    └── test/
```

---

# 28. Recommended Shared Package Strategy

Do not try to share all code between platforms.

Share:

- schemas;
- fixtures;
- behavior specifications;
- protocol definitions;
- test vectors;
- user-facing terminology.

Implement native platform behavior in:

- Swift on iOS;
- Kotlin on Android;
- Rust on Windows;
- TypeScript in extensions.

This is intentionally less "DRY" at the implementation layer but much more maintainable for OS integrations.

---

# 29. Policy Matching Engine

The policy matcher must support:

## Exact domain

```text
reddit.com
```

## Subdomains

```text
*.reddit.com
```

## Categories

```text
social
video
news
shopping
gaming
```

## Allowlist mode

Example:

```text
ALLOW:
github.com
stackoverflow.com
developer.mozilla.org

BLOCK:
everything else
```

The allowlist semantics must be explicit.

Do not implement:

```text
block empty list = allow everything
```

without making this visible to the UI.

---

# 30. Category Definition Format

Built-in categories should be data, not hard-coded application logic.

Example:

```json
{
  "id": "social",
  "name": "Social Media",
  "domains": [
    "facebook.com",
    "instagram.com",
    "reddit.com",
    "x.com",
    "tiktok.com"
  ]
}
```

Version these definitions.

```text
categoryVersion = 3
```

A user policy should store the actual resolved rules at session time so that a future category update does not unexpectedly alter an already-running session.

---

# 31. Focus Profiles

Profiles are not separate policy engines.

They are named policies:

```text
Study
Work
Coding
Writing
Reading
Deep Work
```

Example:

```json
{
  "id": "coding",
  "mode": "blocklist",
  "domains": [
    "reddit.com",
    "instagram.com",
    "facebook.com",
    "youtube.com"
  ]
}
```

Later:

```text
Coding
ALLOW ONLY:
github.com
npmjs.com
developer.mozilla.org
stackoverflow.com
chatgpt.com
```

---

# 32. Scheduling

MVP:

- Start now.
- Duration presets.
- Custom duration.

V1:

- Weekday schedule.
- Start/end time.
- Repeating rules.

Example:

```text
Mon-Fri
09:00 - 12:00
Policy: Work
```

## Scheduling architecture

Do not depend on JavaScript timers for recurring enforcement.

Use OS scheduling facilities:

- iOS: Device Activity.
- Android: system scheduling + persisted timestamps.
- Windows: Windows Task Scheduler/startup/service mechanisms.
- Browser: persisted session end time plus extension alarms where appropriate.

## 32.1 Bedtime Wind-Down & Sleep Guard (Pro Feature)

Bedtime Wind-Down is a dedicated recurring schedule designed to reduce phone usage and blue light exposure during late evening hours before sleep.

### Functional Behavior
- **Configurable Wind-Down Window:** The user sets their target bedtime (e.g., 23:00) and desired wind-down buffer (e.g., 60 minutes). The system automatically initiates Bedtime Wind-Down at 22:00.
- **Overnight Session Boundary:** The active session boundary spans across midnight (e.g., `startAt: 22:00` Day N, `endAt: 07:00` Day N+1). The domain logic relies on UTC ISO 8601 timestamps so evaluating `now >= startAt && now < endAt` remains robust regardless of day transitions or local timezone shifts.
- **Phone Usage & Blue Light Reduction:**
  - High-dopamine, visually stimulating mobile apps (social networking feeds, short-form video players, web browsers, mobile games) are shielded or blocked.
  - Mitigates screen blue light exposure by discouraging late-night device interaction and encouraging prompt sleep onset.
- **Essential Nighttime Allowlist:**
  - Phone calls & emergency contacts (always permitted).
  - Alarms, timers, and clock app.
  - Sleep-supporting utilities (white noise, meditation apps, sleep tracking).
- **Lock Mode Default:** Defaults to locked/strict enforcement during the pre-sleep window, preventing impulsive override or cancellation during periods of cognitive fatigue.
- **Cross-Device Bedtime Synchronization (Pro):** When Bedtime Wind-Down activates on the user's phone, the Pro sync engine propagates the session to registered desktop and tablet clients, preventing cross-device screen shifting.

---

# 33. Timer Implementation

Never:

```typescript
setInterval(() => seconds--, 1000)
```

as the source of truth.

Use:

```typescript
remaining = max(0, endAt - now())
```

The countdown only redraws the UI.

This avoids:

- sleep bugs;
- tab throttling;
- process restart errors;
- timer drift.

---

# 34. Crash Recovery

Every component must support startup recovery.

## Main UI

```text
load state
   |
check active session
   |
repair enforcement if needed
```

## iOS extensions

Re-evaluate persisted session and shield state.

## Android

AccessibilityService and VPN service independently check the current session.

## Windows

Native agent loads current session from SQLite before accepting UI commands.

## Browser

Extension checks persisted session at startup.

---

# 35. Failure Handling

## Permission removed

Example:

```text
Accessibility permission removed
```

System state:

```text
ENFORCEMENT_DEGRADED
```

UI:

```text
App blocking is disabled because Android permission
was removed.

[Fix]
```

Do not silently tell users that the session is fully protected.

## Extension disabled

Desktop UI:

```text
Chrome blocker unavailable
```

The Windows native component can continue system/network blocking if configured, but it should explicitly report browser enforcement as degraded.

---

# 36. Observability

Because privacy is a product principle, observability should be primarily local.

Local debug log example:

```text
2026-09-19T19:30:00Z SESSION_STARTED id=...
2026-09-19T19:30:00Z IOS_SHIELD_APPLIED
2026-09-19T19:31:10Z BLOCK_ATTEMPT category=social
2026-09-19T20:20:00Z SESSION_COMPLETED
```

Never log:

- full private URLs;
- notification contents;
- screen contents;
- message text.

---

# 37. Testing Strategy

## 37.1 Unit tests

Test:

- domain normalization;
- suffix matching;
- allowlist matching;
- category expansion;
- session state transitions;
- timestamp handling;
- policy revision;
- idempotency.

## 37.2 Property tests

Useful properties:

```text
canonicalize(canonicalize(domain)) == canonicalize(domain)
```

and:

```text
endAt <= now => session is not active
```

and:

```text
stale revision cannot overwrite newer revision
```

## 37.3 Platform tests

### iOS

Test:

- Family Controls authorization;
- app shield;
- website shield;
- shield UI;
- shield action;
- reboot;
- device lock/unlock;
- schedule start;
- schedule end.

### Android

Test:

- Accessibility enable/disable;
- foreground app detection;
- VPN start/stop;
- DNS blocking;
- network switching;
- DND rule activation/deactivation;
- reboot;
- battery optimization;
- permission removal.

### Windows

Test:

- browser blocking;
- native agent startup;
- WFP filter install/remove;
- IPv4;
- IPv6;
- DNS cache;
- sleep/resume;
- user sign-in;
- user sign-out;
- browser restart;
- agent crash.

### Browser

Test:

- Chrome;
- Edge;
- Firefox;
- incognito/private mode where extension policy allows;
- browser restart;
- extension restart;
- session expiry while browser remains open.

---

# 38. End-to-End Acceptance Test

The most important E2E test is:

```text
1. Install application.
2. Create "Study" blocklist.
3. Add:
   youtube.com
   reddit.com
   instagram.com
4. Start 10-minute focus session.
5. Open YouTube.
6. Verify blocked.
7. Open Reddit.
8. Verify blocked.
9. Close/reopen browser.
10. Verify still blocked.
11. Restart desktop application.
12. Verify still blocked.
13. Wait until session end.
14. Verify access restored.
15. Confirm no permanent policy remains.
```

Then repeat for every platform.

---

# 39. Performance Requirements

## Desktop UI

- First screen render: < 1.5 s target.
- Session start: < 500 ms excluding OS permission calls.
- Local policy update: < 200 ms target.

## Browser extension

- Rule update: < 500 ms target.
- No continuous polling of every request.
- Use declarative rules for enforcement.

## Android

- Accessibility event handling must be lightweight.
- Avoid scanning full accessibility trees.
- VPN packet processing must minimize allocations.

## iOS

- Keep enforcement logic in system-managed frameworks/extensions.
- Avoid polling.
- Prefer scheduled system callbacks.

---

# 40. Battery Requirements

This is especially important on mobile.

## Android

Do not:

```text
poll foreground app every 100 ms
```

Prefer accessibility events.

Do not:

- run a permanent high-frequency timer;
- continuously inspect window content;
- perform network calls during enforcement.

## iOS

Use Screen Time/Device Activity system scheduling rather than timers where possible.

---

# 41. Offline Requirements

The app must support:

```text
Install
→ configure
→ start focus
→ block
→ complete
```

with zero network connectivity.

The only features that may require network connectivity later:

- sync;
- software updates;
- optional crash reporting;
- optional category database update.

---

# 42. Update Strategy

The blocker must remain compatible when the UI updates.

The enforcement state should be stored using stable versioned structures.

Example:

```text
Policy schema version 1
Session schema version 1
Native enforcement plan version 1
```

Do not make the app unable to interpret an active session because a new UI version was installed.

---

# 43. Packaging and Installation

## Windows

Potential delivery options:

### Option A

MSIX packaged application.

Pros:

- clean install/uninstall;
- package identity;
- update facilities.

Cons:

- some native/service scenarios have packaging constraints.

### Option B

Signed MSI/EXE.

Pros:

- maximum flexibility;
- native services and system components easier to install.

Cons:

- more installer responsibility.

For the first Windows release, choose the packaging model based on whether the selected WFP/native agent requires capabilities that complicate MSIX packaging.

## Android

Publish on Google Play as a free download with in-app subscriptions.

Direct APK distribution must still respect Android platform rules.

## iOS

App Store distribution requires Apple entitlements. App is free to download with in-app subscriptions.

## Browser

Publish extensions through:

- Chrome Web Store;
- Microsoft Edge Add-ons;
- Firefox Add-ons.

Browser extensions function as companion enforcement tools for the paid desktop/mobile application, connecting via Native Messaging or user account login.

---

# 44. Permission/Capability Matrix

| Capability | iOS | Android | Windows | Browser |
|---|---|---|---|---|
| Website blocking | Family Controls / Managed Settings | VpnService | WFP / DNS / extension | declarativeNetRequest |
| App blocking | Managed Settings | AccessibilityService | Win32 soft block | N/A |
| Usage stats | Device Activity | UsageStatsManager | optional local process metrics | extension-local events |
| Notification suppression | limited/system Focus integration | AutomaticZenRule/DND | limited | limited |
| Scheduling | Device Activity | system scheduling | Task Scheduler/agent | extension alarms + timestamps |
| Custom blocked UI | Shield UI | own Activity | own Tauri window | extension page/redirect |
| Cross-device sync | future | future | future | future |

---

# 45. Important Platform Limitations

## iOS

Strong app/website shielding is available through Screen Time frameworks, but authorization and App Store entitlement approval are required. Arbitrary global notification suppression is not the core capability to design around.

## Android

App blocking can be practical through AccessibilityService, but this API carries Play policy requirements. Website blocking through a local VPN is powerful but has networking edge cases.

## Windows

Browser blocking is straightforward through the extension. Strong OS-level blocking is substantially more complex than on iOS and Android and should be implemented incrementally.

## Browser

Extensions only control the browser in which they are installed. They do not solve the user's other browser, mobile apps, or desktop apps.

---

# 46. MVP Implementation Sequence

Do not implement all platforms simultaneously.

## Phase 0 — Core specification

Build:

- canonical data model;
- policy schema;
- session state machine;
- domain matcher;
- test fixtures.

No OS integration yet.

## Phase 1 — Windows + Chromium

Why:

- fast development cycle;
- easy manual testing;
- good browser APIs;
- gives a working public prototype quickly.

Build:

```text
Tauri
+
Rust session engine
+
Chrome/Edge extension
```

Focus on:

- website blocking;
- session lifecycle;
- profiles;
- normal/strict mode;
- local statistics.

## Phase 2 — Android

Add:

- AccessibilityService;
- local VPN;
- notification/DND integration;
- Android recovery;
- permissions UX.

## Phase 3 — iOS

Add:

- Family Controls;
- Managed Settings;
- Shield extensions;
- Device Activity scheduling.

## Phase 4 — Cross-device sync

Only after the local versions are stable.

---

# 47. MVP Definition of Done

The product is ready for public testing when:

### Session

- [ ] User can start a session in <20 seconds.
- [ ] Session survives UI restart.
- [ ] Session expires correctly after reboot.
- [ ] Stop works in normal mode.
- [ ] Strict mode requires deliberate confirmation.

### Websites

- [ ] Built-in categories work.
- [ ] Custom domains work.
- [ ] Subdomains are correctly handled.
- [ ] Browser restart does not bypass an active session.
- [ ] Expiry restores access.

### Privacy & Commercial

- [ ] User accounts and authentication integrated.
- [ ] Free/Pro subscription tiers integrated.
- [ ] Local enforcement continues even if API is unreachable.
- [ ] No browsing history transmitted.
- [ ] No ads.
- [ ] No remote telemetry enabled by default.

### Platforms

- [ ] One desktop platform stable.
- [ ] One browser family stable.
- [ ] Android prototype functional.
- [ ] iOS prototype functional or entitlement work confirmed.

---

# 48. Product/Engineering Risks

## R1 — Platform restrictions

The biggest technical risk is not building the UI. It is maintaining enforcement across OS updates.

Mitigation:

- native adapters;
- automated platform tests;
- minimal dependence on undocumented APIs.

## R2 — Android Play policy

Accessibility and VPN usage need careful policy compliance.

Mitigation:

- build a policy checklist before implementation;
- use the minimum permissions;
- add prominent user-facing explanations;
- never use these APIs for unrelated tracking.

## R3 — iOS entitlement approval

Family Controls distribution requires Apple's approval.

Mitigation:

- request entitlement early;
- build the application so the core architecture does not depend on a single Apple-specific class.

## R4 — Windows enforcement complexity

Windows system-level enforcement can become a project of its own.

Mitigation:

```text
Extension first
→ DNS/WFP
→ application enforcement
→ stronger controls only if necessary
```

## R5 — Scope explosion

The product can easily turn into:

```text
Freedom + Opal + one sec + Forest + Todoist
```

Do not allow this.

The primary loop is:

```text
Start → Block → Focus → Finish
```

---

# 49. Engineering Quality Standards

## Code

- TypeScript strict mode.
- Kotlin explicit nullability.
- Swift strict concurrency where practical.
- Rust clippy + fmt + tests.
- No platform adapter should silently swallow enforcement failures.

## Error model

Use explicit typed errors:

```text
PermissionDenied
ExtensionUnavailable
PolicyInvalid
UnsupportedPlatformCapability
EnforcementInstallFailed
SessionAlreadyExpired
StaleRevision
```

Avoid generic:

```text
Error("something went wrong")
```

for core enforcement.

---

# 50. Recommended Error UX

Do not expose technical errors such as:

```text
WFP_ERROR_CONTEXT_IN_USE
```

Translate:

```text
Windows cannot activate website blocking
because another network filter is active.

[Learn more]
```

But the internal log retains:

```text
code=WFP_ERROR_CONTEXT_IN_USE
```

---

# 51. Security Boundary Between UI and Native Agent

The UI is untrusted input from the standpoint of the native enforcement agent.

Example:

```text
Tauri UI
    |
    | IPC
    v
Rust Agent
    |
validate:
  sessionId
  timestamps
  policy revision
  domain format
  command authorization
    |
    v
Native enforcement
```

The agent should reject:

- malformed domains;
- invalid timestamps;
- negative durations;
- duplicate conflicting session IDs;
- stale revisions.

---

# 52. Domain Validation

Canonical domain rules:

```text
Allowed:
youtube.com
www.youtube.com
subdomain.youtube.com

Rejected:
https://youtube.com
youtube.com/watch
youtube.com:443
javascript:...
file://...
```

Normalize user input at the application boundary.

Store canonical values only.

---

# 53. Application Identifier Normalization

Android:

```text
packageName
```

Windows:

```text
normalized executable identity/path
```

iOS:

```text
opaque Screen Time token
```

The UI should use:

```text
ApplicationRef
```

and let each platform map it to its native representation.

---

# 54. Recommended UX Architecture

## Home screen

```text
FOCUS

Ready to focus?

[ 50 min ▼ ]

Blocklist
[ Study ▼ ]

This device
[ Enabled ]

[ START FOCUS ]

Today
2h 35m focused
```

## Active session

```text
FOCUS

37:42 remaining

Study

Blocked:
YouTube
Reddit
Instagram

[ Stop ]
```

## Completion

```text
Session complete.

50 minutes focused.

[ Focus Again ]
[ Done ]
```

This should remain the dominant UX even as features grow.

---

# 55. Basic Local Analytics

Track only:

```text
focus duration
sessions completed
sessions cancelled
block attempts
```

Recommended dashboard:

```text
Today
2h 40m

This week
11h 20m

Sessions
14

Blocked attempts
27
```

Do not expose meaningless vanity metrics.

---

# 56. Block Attempt Semantics

A "block attempt" means:

> The enforcement layer observed an attempted transition to a configured blocked target during an active session.

Examples:

```text
Browser:
main_frame request to youtube.com blocked

Android:
foreground package changed to blocked package

iOS:
system shield displayed
```

The exact data should be platform-normalized.

---

# 57. Recommended Source of Truth

| Data | Source |
|---|---|
| Current time | OS |
| Session end | local DB |
| Active policy | local DB |
| Enforcement state | native adapter |
| Browser rules | extension |
| iOS shield | Managed Settings |
| Android app blocking permission | system |
| Sync state | API backend |

Do not make the backend authoritative for active blocking.

---

# 58. Architecture Decision Records

## ADR-001 — Local-first

**Decision:** Local-first enforcement (backend downtime does not break active sessions).

**Reason:** Reliability, privacy, and ensures uninterrupted blocking even if the cloud sync API is unreachable.

## ADR-002 — Native enforcement adapters

**Decision:** Every platform has a native enforcement implementation.

**Reason:** OS APIs are fundamentally different.

## ADR-003 — Browser extension uses declarativeNetRequest

**Decision:** Use DNR rather than JS interception.

**Reason:** MV3 compatibility and lower overhead.

## ADR-004 — Android uses AccessibilityService for app blocking

**Decision:** Use the API only for real-time blocked-app detection.

**Reason:** Better real-time semantics than polling usage statistics.

**Constraint:** Requires Play policy review.

## ADR-005 — iOS uses Screen Time APIs

**Decision:** Use Family Controls + Managed Settings + Device Activity.

**Reason:** These are Apple's supported system mechanisms for app/site restrictions.

## ADR-006 — Windows uses native enforcement

**Decision:** Use Rust native code for WFP/Win32 integration.

**Reason:** TypeScript is inappropriate for low-level system enforcement.

---

# 59. Release Roadmap

## v0.1 — Private prototype

Platforms:

```text
Windows
Chrome
```

Features:

- session timer;
- custom blocklist;
- browser blocking;
- local statistics;
- normal/strict mode.

## v0.2 — Public beta

Add:

- built-in categories;
- allowlist;
- better block page;
- Windows system agent;
- Firefox.

## v0.3 — Android

Add:

- app blocking;
- website filtering;
- notification suppression;
- recovery.

## v0.4 — iOS

Add:

- app/site shielding;
- Device Activity schedules;
- shield UI.

## v1.0 — Cross-device

Add:

- optional pairing;
- synchronized sessions;
- encrypted policy synchronization.

---

# 60. Final Architecture

The final desired architecture is:

```text
                         +----------------------+
                         |    Focus Domain      |
                         |                      |
                         | Session State Machine|
                         | Policy Model         |
                         | Profiles             |
                         +----------+-----------+
                                    |
                         Canonical Policy
                                    |
              +---------------------+---------------------+
              |                     |                     |
              v                     v                     v
        +-----------+         +-----------+         +-----------+
        |    iOS    |         |  Android  |         | Windows   |
        +-----------+         +-----------+         +-----------+
        | SwiftUI   |         | Compose   |         | Tauri     |
        | Family   |         | VPN       |         | Rust      |
        | Controls |         | Access.   |         | WFP       |
        | Managed  |         | Zen Rule  |         | Win32     |
        | Settings |         | Usage     |         | Agent     |
        +-----+-----+         +-----+-----+         +-----+-----+
              |                     |                     |
              v                     v                     v
           System              System                  System
         Enforcement         Enforcement             Enforcement
              ^                     ^                     ^
              |                     |                     |
              +---------------------+---------------------+
                                    |
                              Browser Layer
                                    |
                           +--------+--------+
                           | Chrome/Edge/FF  |
                           | MV3/DNR         |
                           +-----------------+
```

Optional future:

```text
                     +------------------+
                     |   Sync Service   |
                     |   NestJS + PG    |
                     +--------+---------+
                              |
                    encrypted device state
                              |
                +-------------+-------------+
                |             |             |
                v             v             v
               iOS         Android       Windows
```

The defining architectural rule is:

> **The backend coordinates devices; it does not control the user's ability to focus. Each device must be capable of enforcing its own active session independently.**

---

# 61. Official Platform References

These references should be rechecked during implementation because platform capabilities and store policies change.

## Apple

Family Controls / authorization:

https://developer.apple.com/documentation/familycontrols

https://developer.apple.com/documentation/familycontrols/authorizationcenter

Family Controls entitlement:

https://developer.apple.com/documentation/familycontrols/requesting-the-family-controls-entitlement

Managed Settings:

https://developer.apple.com/documentation/managedsettings

Shield settings:

https://developer.apple.com/documentation/managedsettings/shieldsettings

Managed Settings UI:

https://developer.apple.com/documentation/managedsettingsui

Device Activity:

https://developer.apple.com/documentation/deviceactivity/deviceactivitycenter

Device Activity schedules:

https://developer.apple.com/documentation/deviceactivity/deviceactivityschedule

Network Extension content filtering:

https://developer.apple.com/documentation/networkextension/nefilterdataprovider

## Android

Usage stats:

https://developer.android.com/reference/android/app/usage/UsageStatsManager

Accessibility Service:

https://developer.android.com/reference/android/accessibilityservice/AccessibilityService

Accessibility policy:

https://support.google.com/googleplay/android-developer/answer/10964491

VPN:

https://developer.android.com/develop/connectivity/vpn

Google Play VpnService policy:

https://support.google.com/googleplay/android-developer/answer/12564964

Automatic DND rules:

https://developer.android.com/reference/android/app/AutomaticZenRule

Notification Manager:

https://developer.android.com/reference/android/app/NotificationManager

Android target API requirements:

https://support.google.com/googleplay/android-developer/answer/11926878

## Windows

Windows Filtering Platform:

https://learn.microsoft.com/en-us/windows/win32/fwp/about-windows-filtering-platform

Windows app development:

https://learn.microsoft.com/en-us/windows/apps/desktop/

Windows packaging:

https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/packaging/

MSIX:

https://learn.microsoft.com/en-us/windows/msix/overview

## macOS Network Extension

NetworkExtension Framework:
https://developer.apple.com/documentation/networkextension

NEFilterDataProvider:
https://developer.apple.com/documentation/networkextension/nefilterdataprovider

---

# 62. Tech Lead Recommendation

The engineering mistake to avoid is building four complete applications before validating the enforcement loop.

The recommended implementation order is:

```text
1. Domain/policy/session specification
2. Windows + DNS Proxy prototype
3. macOS LaunchDaemon prototype (Freedom Model)
4. Reliable website blocking
5. Session persistence/recovery
6. Strict mode
7. Android app blocking + VPN
8. iOS Screen Time integration
9. Cross-device synchronization
```

The product should not be judged by how many features exist.

The key engineering test is:

> Can a user press "Start Focus" and trust that the distractions they selected remain blocked until the session ends, even if the UI restarts?

If that answer is not reliably "yes", additional features are premature.
