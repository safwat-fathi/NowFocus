# NowFocus

A privacy-first, cross-platform focus and distraction-blocking application designed to help people control their digital environment.

The application blocks distracting websites and applications, manages focus sessions, controls notifications where supported by the operating system, and provides insights into focus behavior.

## Vision

> Help people intentionally control their digital environment so they can focus on what matters.

NowFocus is not intended to be a surveillance product.

The product is designed around:

- Privacy
- Reliability
- Cross-platform synchronization
- Simple focus workflows
- Powerful automation
- Minimal distractions

---

# Features

## Core

- Website blocking
- Application blocking
- Focus sessions
- Scheduled focus sessions
- Recurring focus routines
- Bedtime wind-down & sleep mode (phone usage & blue light restriction)
- Always-Blocked Commitment Shield (strict 14-day lock for adult content, gambling, and addictive distractions)
- Notification management where supported
- Focus statistics
- Cross-device synchronization

## Extensibility & Integrations

- Public REST API with OpenAPI 3.1 specifications
- OAuth 2.0 authorization with granular resource scopes
- Realtime HMAC-SHA256 signed Webhooks (`session.started`, `session.completed`, `bedtime.started`, etc.)
- Third-party developer extensions (e.g., Slack focus sync, Salah/prayer time focus automation, smart home bedtime integrations)
- Strict resource isolation & safety invariants (extensions cannot trigger lock mode, alter Always-Blocked shields, or prevent user cancellation)

## Platforms

The planned ecosystem includes:

- iOS
- Android
- Windows
- macOS
- Web Dashboard

Future platforms may include:

- Linux

---

# Product Plans

## Free

The free version provides the fundamental focus experience.

Includes:

- Website blocking
- Application blocking
- Basic focus sessions
- Basic schedules
- Basic statistics
- Single-device usage
- No third-party extension authorization

The purpose of the Free plan is to let users experience the core product without requiring payment.

## Pro

Pro provides advanced control.

Includes:

- Unlimited blocklists
- Advanced schedules
- Focus routines
- Bedtime Wind-Down & Sleep Guard (automated phone restriction & blue light reduction before bedtime)
- Always-Blocked Commitment Shield (strict 14-day lock, curated adult/gambling filters, anti-tamper clock verification)
- Cross-device synchronization
- Advanced statistics
- Custom rules
- Lock mode
- Advanced automation
- Developer Extensions & Public API (OAuth 2.0, REST endpoints, webhooks, 60 req/min rate limit)

## Family

Family provides multi-user functionality.

Includes:

- Multiple family members
- Child profiles
- Device management
- Study schedules
- Bedtime schedules (parental sleep guard & wind-down controls)
- Always-Blocked parental shield for child devices (locking out adult content and harmful apps)
- Family extension controls (parental approval & monitoring for child profiles)
- Family dashboard

## Business

Business provides organization-level functionality.

Includes:

- Organizations
- Teams
- User management
- Organization policies
- Team focus sessions
- Aggregated analytics
- Organization-level extension governance (admin allowlist/blocklist, team pre-installation, 300 req/min rate limit)
- Admin dashboard
- Organization billing


---

# Architecture

The application consists of several core components, client platforms, and an external developer integration layer:

```text
                       ┌─────────────────────┐
                       │      Web App        │
                       │   User Dashboard    │
                       └──────────┬──────────┘
                                  │
┌──────────────────────┐ ┌────────▼──────────┐
│ Third-Party Services │ │      API Layer    │
│  & Developer Exts    │ │ Authentication    │
│ (Salah, Slack, etc.) │─┼─▶ REST API /v1    │
│  OAuth 2.0 / Webhook │◀┼─  Webhooks        │
└──────────────────────┘ │ Focus Sessions    │
                         │ Sync & Devices    │
                         └────────┬──────────┘
                                  │
             ┌────────────────────┼────────────────────┐
             │                    │                    │
      ┌──────▼──────┐      ┌──────▼──────┐      ┌─────▼──────┐
      │ PostgreSQL  │      │    Redis    │      │  Workers   │
      │             │      │             │      │            │
      │ Users       │      │ Sessions    │      │ Jobs       │
      │ Devices     │      │ Presence    │      │ Analytics  │
      │ Rules       │      │ Sync        │      │ Webhooks   │
      │ Extensions  │      │ Events      │      │ Notifications│
      │ Billing     │      │ Rate Limits │      │            │
      └─────────────┘      └─────────────┘      └────────────┘

             │
             │ Synchronization
             │
      ┌──────┴───────────────────────────────┐
      │                                      │
┌─────▼─────┐                         ┌──────▼──────┐
│   Mobile  │                         │   Desktop   │
│           │                         │             │
│ iOS       │                         │ Windows     │
│ Android   │                         │ macOS       │
└───────────┘                         └─────────────┘
```

## Developer Extension System

NowFocus provides an external integration architecture allowing third-party developers to connect external services without executing untrusted code inside the client applications.

For comprehensive technical specifications, see [extension_system_architecture.md](file:///Users/safwat/Coding/Projects/side-projects/now-focus/extension_system_architecture.md).

### Key Architectural Pillars

1. **External API Model:** Extensions run on developer infrastructure and communicate via standard HTTPS REST endpoints (`/v1/`) and HMAC-SHA256 signed webhooks. No arbitrary third-party code executes in the client apps.
2. **OAuth 2.0 & Scoped Permissions:** Users authorize extensions via standard OAuth 2.0 authorization code flow with granular, resource-based scopes (`sessions:read`, `sessions:write`, `policies:read`, `policies:write`, `schedules:read`, `schedules:write`, `devices:read`, `webhooks:subscribe`, `commitment-shield:read`).
3. **Strict Resource Isolation:** Extensions can only manipulate resources they created (`created_by_extension_id`). An extension cannot cancel user-created sessions or alter policies created by other extensions.
4. **Session Coexistence & Policy Union:** User sessions and extension sessions run simultaneously. Active domain and app blocking rules are merged via union. If conflicting notification modes are requested, the most restrictive mode takes precedence.
5. **Non-Negotiable Safety Invariants:**
   - **No Lock Mode:** Extensions cannot start locked sessions (`enforcement_mode: locked` is rejected with `403 Forbidden`).
   - **Always Cancellable:** Users retain the unilateral power to cancel any extension-created session at any time.
   - **Commitment Shield Safety:** Extensions only have read-only visibility (`commitment-shield:read`); no write endpoints exist.
   - **Bedtime Safety:** Extensions cannot initiate Bedtime Wind-Down sessions; they can only listen to `bedtime.started` / `bedtime.ended` webhooks.
6. **Transparent UI Attribution & Revocation:** All extension-created sessions display clear attribution badges in the client UI. Users can review activity metrics and revoke extension access at any time from Settings > Extensions.

---

# Repository Structure

A possible monorepo structure:

```text
focus-app/
│
├── apps/
│   ├── web/
│   ├── daemon/
│   ├── ios/
│   ├── android/
│   ├── windows/
│   └── macos/
│
├── services/
│   ├── api/
│   ├── worker/
│   └── sync/
│
├── packages/
│   ├── types/
│   ├── config/
│   ├── validation/
│   ├── protocol/
│   └── shared/
│
├── infrastructure/
│   ├── docker/
│   ├── terraform/
│   └── deployment/
│
├── docs/
│   ├── architecture/
│   ├── product/
│   ├── api/
│   └── platform/
│
└── README.md
```

The exact structure can change depending on the selected native/mobile stack.

---

# Core Concepts

## Focus Session

A Focus Session represents an intentional period during which distraction rules are active.

Example:

```json
{
  "duration": 3600,
  "mode": "deep_work",
  "startedAt": "2026-09-19T09:00:00Z",
  "endsAt": "2026-09-19T10:00:00Z"
}
```

## Focus Profile

A Focus Profile defines the rules for a particular context.

Example:

```text
Deep Work

Blocked:
- youtube.com
- reddit.com
- facebook.com
- instagram.com

Allowed:
- github.com
- stackoverflow.com
- docs.google.com
```

## Device

A device represents an installation of the application.

Examples:

- iPhone
- Android phone
- Windows PC
- Mac

Each device receives the user's applicable focus state.

## Bedtime Wind-Down (Sleep Guard)

A specialized scheduled focus mode engineered for evening and night hours. It automatically restricts phone usage, shields stimulating digital feeds, and minimizes blue light exposure prior to sleep to promote restorative sleep hygiene.

## Developer Extension

An external application or service authorized by a user via OAuth 2.0 that integrates with NowFocus through public REST endpoints and signed webhooks. Extensions enable automated session triggers (e.g. Salah prayer times), external presence sync (e.g. Slack DND), or smart-home automations, operating under strict resource isolation where the user always maintains full control and cancellation rights.

---

# Synchronization

The backend maintains the authoritative focus state.

Example:

```text
User starts session
        |
        v
     API
        |
        v
  Focus Session
        |
        v
   Event / Sync
        |
   ┌────┼────┐
   v    v    v
 iOS Android Windows
```

Devices should be able to recover their state after:

- Network disconnection
- Application restart
- Device restart
- Temporary backend failure

The local device should retain enough state to enforce an already-started session while offline.

---

# Privacy

Privacy is a core product requirement.

The system should avoid collecting unnecessary information.

The product should not sell:

- Browsing history
- Application usage history
- Personal activity
- User focus data

Analytics should preferably use aggregated and anonymized information.

Users should be able to:

- Export their data
- Delete their account
- Control analytics
- Understand what data is collected

---

# Security

Security requirements include:

- TLS for network communication
- Secure authentication
- Secure token storage
- Password hashing
- Device authentication
- Session revocation
- Rate limiting
- Input validation
- Authorization checks
- Encryption at rest for sensitive information
- Audit logging for administrative actions

Platform-specific security mechanisms should be used wherever possible.

---

# Platform Responsibilities

## iOS

The iOS application should handle platform-supported:

- Focus functionality
- App restrictions where permitted
- Screen-time related APIs
- Notifications
- Local session state
- Background behavior

The implementation must respect Apple's platform and privacy restrictions.

## Android

Android can provide more extensive device-level functionality through appropriate platform APIs.

Responsibilities may include:

- Application blocking
- Notification management
- Background enforcement
- Local focus state
- Usage information where permitted

The implementation must avoid abusive accessibility or device-admin behavior.

## Windows

The Windows application is responsible for:

- Application blocking
- Website enforcement integration
- Local focus sessions
- Background enforcement
- System notifications
- Local configuration

The application should remain lightweight.

## macOS

The macOS application is responsible for:

- Application blocking
- System-level website enforcement (Local DNS/Network Extension)
- Local focus sessions
- Background enforcement
- Menu-bar integration

The application must request appropriate system privileges for network blocking.

## Web

The web application provides:

- Account management
- Focus configuration
- Analytics
- Billing
- Device management
- Family management
- Business administration

The web application is not responsible for local blocking.

---

# Backend

The backend manages:

- Authentication
- Users
- Subscriptions
- Devices
- Focus profiles
- Focus sessions
- Synchronization
- Analytics
- Organizations
- Family accounts
- Billing

Possible stack:

```text
API:
Node.js
TypeScript
NestJS

Database:
PostgreSQL

Cache / realtime:
Redis

Background jobs:
Redis-backed workers

Infrastructure:
Docker
Nginx
Cloud infrastructure
```

The final technology choices should be documented separately in the technical architecture document.

---

# Data Model

Core entities:

```text
User
 ├── Devices
 ├── FocusProfiles
 ├── FocusSessions
 ├── BlockRules
 ├── ExtensionAuthorizations
 ├── WebhookSubscriptions
 └── Subscription

Organization
 ├── Members
 ├── Devices
 ├── Policies
 ├── ApprovedExtensions
 └── Subscription
```

Potential database tables:

```text
users
devices
focus_profiles
focus_rules
focus_sessions
focus_events
subscriptions
organizations
organization_members
organization_policies
extensions
extension_authorizations
webhook_subscriptions
billing_events
```

### Extension-Ready Foundations

To prevent costly database migrations, core session and policy models incorporate extension attribution from day one:
- `source`: `"user"` | `"extension"` | `"schedule"`
- `created_by_extension_id`: ID of the creating extension (or `null` for user-initiated)
- `extension_metadata`: Display name and icon URL for UI attribution

---

# Development Roadmap

## Phase 1 — Foundation

- Repository setup
- Authentication
- User accounts
- Database & extension-ready core schemas (`source`, `createdByExtensionId`, `extensionMetadata`)
- API
- Basic web application
- CI/CD
- Development environments

## Phase 2 — Blocking Engine

- Website blocking
- Application blocking
- Local rules
- Focus sessions
- Session persistence
- Offline enforcement

## Phase 3 — Cross-platform

- macOS
- iOS
- Android
- Windows
- Device registration
- Device synchronization

## Phase 4 — Analytics

- Focus statistics
- Session history
- Blocking events
- Productivity trends
- Dashboard

## Phase 5 — Monetization

- Free plan
- Pro plan
- Subscription management
- Payment integration
- Entitlements
- Billing portal

## Phase 6 — Advanced Features

- Advanced scheduling
- Focus routines
- Lock mode
- Advanced synchronization
- AI features

## Phase 7 — Family

- Family accounts
- Child profiles
- Family dashboard
- Study schedules
- Bedtime schedules
- Family extension controls & child profile approvals

## Phase 8 — Business

- Organizations
- Teams
- Admin dashboard
- Policies
- Aggregated analytics
- Organization billing
- Organization-level extension governance (allowlist / pre-installation)

## Phase 9 — Ecosystem & Extensions

- Public REST API `/v1/` and OpenAPI 3.1 specification
- HMAC-signed Webhook subscriptions & delivery
- Developer Portal & self-service registration
- Extension directory / marketplace
- Auto-generated client SDKs (TypeScript, Python, Swift, Kotlin)
- Focus programs
- Hardware integrations


---

# Development Principles

## 1. Reliability over features

A blocker that occasionally fails is worse than a blocker with fewer features.

## 2. Local-first enforcement

Blocking should not depend entirely on the availability of the backend.

The backend manages configuration and synchronization.

The device performs enforcement.

## 3. Privacy by design

Collect only what is necessary.

## 4. Cross-platform consistency

The user should understand the same concepts regardless of the platform.

```text
Focus Profile
Focus Session
Schedule
Block Rule
Device
```

These concepts should remain consistent.

## 5. Fail safely

If synchronization fails:

```text
Existing local focus session
        ↓
Continue enforcing locally
        ↓
Sync when connection returns
```

The user should not suddenly lose protection because the API is temporarily unavailable.

---

# Metrics

Important product metrics include:

### Acquisition

- Downloads
- Account registrations
- Activation rate

### Activation

- First focus session started
- First focus session completed
- Time to first completed session

### Engagement

- Daily active users
- Weekly active users
- Focus sessions per user
- Focus minutes per user

### Retention

- D1
- D7
- D30
- Monthly retention

### Monetization

- Free → Pro conversion
- Trial → paid conversion
- Monthly recurring revenue
- Annual recurring revenue
- Churn
- Average revenue per paying user

### Technical

- Blocking success rate
- Sync failure rate
- Crash rate
- CPU usage
- Memory usage
- Battery impact

---

# Product Philosophy

The application should not try to force productivity onto users.

The user decides:

> "I want to focus."

The application then makes it easier for them to honor that decision.

The product should therefore optimize for:

```text
Intent
  ↓
Focus Session
  ↓
Digital Environment Controlled
  ↓
Less Distraction
  ↓
Completed Work
```

The core product promise is simple:

> **You decide what deserves your attention. NowFocus protects it.**

---

# Long-Term Direction

The long-term product can evolve from a blocker into a complete digital focus platform:

```text
                    Focus Platform
                          |
        ┌─────────────────┼─────────────────┐
        │                 │                 │
     Personal           Family           Business
        │                 │                 │
     Focus              Study            Teams
     Routines            Kids             Policies
     AI                  Safety           Analytics
        │
        └──────────── Ecosystem ────────────┐
                                            │
                                      Marketplace
                                      Integrations
                                      Hardware
```

The first version, however, should remain deliberately narrow.

The initial objective is to build the best possible experience for one fundamental problem:

> **When I choose to focus, help me stay focused.**
