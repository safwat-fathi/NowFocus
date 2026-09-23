# Graph Report - focus-app  (2026-09-20)

## Corpus Check
- Corpus is ~20,660 words - fits in a single context window. You may not need a graph.

## Summary
- 161 nodes · 252 edges · 17 communities (9 shown, 8 thin omitted)
- Extraction: 92% EXTRACTED · 8% INFERRED · 0% AMBIGUOUS · INFERRED: 20 edges (avg confidence: 0.89)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- Block Policy & Rules Model
- macOS App Shell & Lifecycle
- Focus Session Domain Model
- App Blocker & Overlay Enforcement
- Session Engine & Timer Evaluation
- Privileged Daemon XPC Service
- GRDB Persistence & Storage
- Network Enforcer & Hosts Blocker
- macOS App Targets & Frameworks
- DMG Packaging & Release Scripts
- Android Compose Platform Architecture
- iOS Screen Time Architecture
- Windows Tauri & Rust Architecture
- Business Plan & Team Policies
- Family Plan & Parental Shield
- Freemium Privacy Strategy
- Cross-Device State Sync

## God Nodes (most connected - your core abstractions)
1. `FocusSession` - 16 edges
2. `BlockPolicy` - 15 edges
3. `FocusSessionStatus` - 11 edges
4. `NotificationMode` - 11 edges
5. `BlockPolicyRecord` - 11 edges
6. `AppBlocker` - 10 edges
7. `NetworkEnforcer` - 10 edges
8. `DaemonXPCDelegate` - 9 edges
9. `PolicyMode` - 8 edges
10. `DomainRule` - 8 edges

## Surprising Connections (you probably didn't know these)
- `Focus Profile Configuration` --conceptually_related_to--> `BlockPolicy`  [INFERRED]
  README.md → apps/macos/NowFocusCore/BlockPolicy.swift
- `Shared Domain Specification Protocol` --conceptually_related_to--> `BlockPolicy`  [INFERRED]
  native_tech_stack_spec.md → apps/macos/NowFocusCore/BlockPolicy.swift
- `Focus Session Lifecycle` --conceptually_related_to--> `SessionEngine`  [INFERRED]
  README.md → apps/macos/NowFocusCore/SessionEngine.swift
- `Local DNS Proxy & Network Extension Filtering` --conceptually_related_to--> `NetworkEnforcer`  [INFERRED]
  focus_app_technical_architecture.md → apps/macos/NowFocusDaemon/NetworkEnforcer.swift
- `SMAppService LaunchDaemon & XPC Boundary` --implements--> `NetworkEnforcer`  [INFERRED]
  focus_app_technical_architecture.md → apps/macos/NowFocusDaemon/NetworkEnforcer.swift

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **macOS Enforcement Subsystem** — apps_macos_nowfocus_enforcement_appblocker, apps_macos_nowfocusdaemon_networkenforcer_networkenforcer, apps_macos_nowfocuscore_sessionengine_sessionengine [INFERRED 0.85]
- **Always-Blocked Commitment Shield Architecture** — readme_commitment_shield, product_plans_commitment_shield_mechanics, product_plans_grace_period, focus_app_technical_architecture_monotonic_time [EXTRACTED 1.00]
- **Native Multi-Platform Implementations** — native_tech_stack_spec_windows_tauri, native_tech_stack_spec_macos_swift, native_tech_stack_spec_android_compose, native_tech_stack_spec_ios_screentime [EXTRACTED 1.00]

## Communities (17 total, 8 thin omitted)

### Community 0 - "Block Policy & Rules Model"
Cohesion: 0.14
Nodes (23): ApplicationRule, BlockPolicy, DomainRule, PolicyMode, allowlist, blocklist, Bool, Date (+15 more)

### Community 1 - "macOS App Shell & Lifecycle"
Cohesion: 0.11
Nodes (17): App, AppDelegate, NowFocusApp, .body, Notification, MenuBarView, .body, Bool (+9 more)

### Community 2 - "Focus Session Domain Model"
Cohesion: 0.15
Nodes (17): EnforcementMode, locked, normal, strict, FocusSession, FocusSessionStatus, active, cancelled (+9 more)

### Community 3 - "App Blocker & Overlay Enforcement"
Cohesion: 0.18
Nodes (9): AppBlocker, Bool, Notification, String, BlockOverlayPanel, NSPanel, NSRect, NSRunningApplication (+1 more)

### Community 4 - "Session Engine & Timer Evaluation"
Cohesion: 0.12
Nodes (14): SessionEngine, Bool, Date, Fail-Closed Session Architecture, Monotonic Network-Verified Time Anti-Tamper, 14-Day Commitment Shield Mechanics, 60-Second Grace Cancellation Safeguard, Sleep Guard Blue Light & Phone Restriction (+6 more)

### Community 5 - "Privileged Daemon XPC Service"
Cohesion: 0.23
Nodes (8): DaemonXPCDelegate, Bool, Data, Error, NSXPCConnection, NSXPCListener, NSXPCListenerDelegate, Void

### Community 6 - "GRDB Persistence & Storage"
Cohesion: 0.21
Nodes (7): DatabaseManager, .migrator, NowFocusDaemonProtocol, DatabaseMigrator, DatabaseQueue, Foundation, GRDB

### Community 7 - "Network Enforcer & Hosts Blocker"
Cohesion: 0.30
Nodes (6): NetworkEnforcer, String, NowFocusDaemon Privileged Tool Target, Local DNS Proxy & Network Extension Filtering, SMAppService LaunchDaemon & XPC Boundary, System-Level Freedom Model (Zero Extension Blocking)

### Community 8 - "macOS App Targets & Frameworks"
Cohesion: 0.20
Nodes (8): GRDB.swift Package Dependency, NowFocus App Target, NowFocusCore Framework Target, Sparkle Package Dependency, Cocoa, Combine, Soft Application Blocking via NSWorkspace, macOS Swift & MenuBar Extra Stack

## Knowledge Gaps
- **36 isolated node(s):** `Combine`, `.body`, `Sparkle`, `.body`, `blocklist` (+31 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **8 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `NowFocusCore` connect `macOS App Shell & Lifecycle` to `macOS App Targets & Frameworks`, `Privileged Daemon XPC Service`, `Network Enforcer & Hosts Blocker`?**
  _High betweenness centrality (0.287) - this node is a cross-community bridge._
- **Why does `Foundation` connect `GRDB Persistence & Storage` to `Block Policy & Rules Model`, `Focus Session Domain Model`, `Session Engine & Timer Evaluation`, `Privileged Daemon XPC Service`, `Network Enforcer & Hosts Blocker`?**
  _High betweenness centrality (0.287) - this node is a cross-community bridge._
- **Why does `BlockPolicy` connect `Block Policy & Rules Model` to `Session Engine & Timer Evaluation`, `Network Enforcer & Hosts Blocker`?**
  _High betweenness centrality (0.209) - this node is a cross-community bridge._
- **Are the 2 inferred relationships involving `BlockPolicy` (e.g. with `Shared Domain Specification Protocol` and `Focus Profile Configuration`) actually correct?**
  _`BlockPolicy` has 2 INFERRED edges - model-reasoned connections that need verification._
- **What connects `Combine`, `.body`, `Sparkle` to the rest of the system?**
  _36 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Block Policy & Rules Model` be split into smaller, more focused modules?**
  _Cohesion score 0.13793103448275862 - nodes in this community are weakly interconnected._
- **Should `macOS App Shell & Lifecycle` be split into smaller, more focused modules?**
  _Cohesion score 0.11428571428571428 - nodes in this community are weakly interconnected._