# NowFocus — Extension System Architecture Spec

**Status:** Proposed — Design Only (no implementation until core product is validated)  
**Goal:** Ensure core data models and session lifecycle are extension-friendly from day one  
**Date:** September 2026

---

## 1. Executive Summary

NowFocus will expose a **public REST API** that enables third-party developers to build **external integrations** on top of the NowFocus platform. Extensions run on the developer's own infrastructure and communicate with NowFocus via authenticated HTTP endpoints and webhook events.

This document captures all architectural decisions for the extension system so that the core product can be built with extension-friendly data models, avoiding costly refactors later.

### Motivating Examples

| Extension | What it does | APIs used |
|---|---|---|
| **Salah (Muslim Prayer Times)** | Automatically creates focus sessions at prayer times, blocks notifications and calls on mobile | Session Management, Schedule Management |
| **Slack Focus Sync** | Enables Slack DND when a focus session starts, disables it when it ends | Webhooks (`session.started`, `session.completed`) |
| **Smart Home Integration** | Dims lights when bedtime wind-down starts | Webhooks (`bedtime.started`, `bedtime.ended`) |
| **Study Mode Extension** | Adds education-specific blocklists to a focus policy | Policy Management |

---

## 2. Core Decisions

### 2.1 Extension Model → External API

Extensions are **external applications/services** that talk to NowFocus through a public API. NowFocus does **not** execute third-party code inside the app.

```text
┌──────────────────────┐         ┌──────────────────────┐
│   Third-Party App    │         │     NowFocus Cloud   │
│   (Salah Extension)  │────────▶│     REST API /v1     │
│   Runs on dev's      │◀────────│     Webhooks         │
│   infrastructure     │         │                      │
└──────────────────────┘         └──────────┬───────────┘
                                            │ Sync
                                 ┌──────────┴───────────┐
                                 │    NowFocus Devices   │
                                 │  macOS / iOS / Win /  │
                                 │  Android              │
                                 └──────────────────────┘
```

**Rationale:** This avoids sandboxing, code execution security, and review concerns entirely. Developers use familiar HTTP/REST patterns.

### 2.2 API Location → Cloud-Primary, Optional Local

| API | Purpose | Use Case |
|---|---|---|
| **Cloud API** (primary) | Auth, rate limiting, multi-device propagation | Salah extension creates a session → syncs to all devices |
| **Local API** (optional, future) | On-device automation, offline use | Power-user scripts, CLI automation without internet |

The cloud API is the primary integration surface. A local API (localhost HTTP or Unix socket on the daemon) may be added later for advanced/offline scenarios.

### 2.3 Plan Availability → Pro and Above

The extension API is available to **Pro, Family, and Business** plan subscribers only. Free-tier users cannot authorize extensions.

**Rationale:** Extensions are a power-user feature. Gating behind Pro aligns with the monetization strategy (Pro = advanced control + automation) and limits API abuse surface.

---

## 3. Authentication & Authorization

### 3.1 OAuth 2.0 with Scoped Permissions

All extensions authenticate via **OAuth 2.0 Authorization Code flow**.

```text
Developer registers extension on Developer Portal
         ↓
Gets client_id + client_secret
         ↓
User clicks "Connect to NowFocus" in extension
         ↓
Redirected to NowFocus OAuth consent screen
         ↓
User reviews requested scopes, approves
         ↓
Extension receives authorization code
         ↓
Exchanges for access_token + refresh_token
         ↓
Extension calls API with Bearer token
```

### 3.2 Permission Scopes

Granular, resource-based scopes:

| Scope | Description |
|---|---|
| `sessions:read` | Query focus sessions (status, history) |
| `sessions:write` | Create, cancel, extend focus sessions |
| `policies:read` | Read block policies and rules |
| `policies:write` | Create and modify block policies |
| `schedules:read` | Read scheduled sessions and routines |
| `schedules:write` | Create and modify scheduled sessions |
| `devices:read` | Query device list, online status, enforcement health |
| `webhooks:subscribe` | Register and manage webhook subscriptions |
| `commitment-shield:read` | Query whether the Commitment Shield is active and locked categories (read-only, **no write access**) |

> [!IMPORTANT]
> There is **no** `commitment-shield:write` scope. The Always-Blocked Commitment Shield cannot be modified by extensions. This is a deliberate safety decision.

### 3.3 Resource Ownership → Strict Isolation

Extensions can only create and control their **own** resources. They **cannot** modify sessions, policies, or schedules created by the user or other extensions.

```text
Extension A creates "Prayer Session"    → Extension A can cancel it ✓
Extension A tries to cancel user's      → Rejected (403 Forbidden) ✗
  "Deep Work" session
Extension A tries to modify Extension   → Rejected (403 Forbidden) ✗
  B's policy
```

Every resource created via the API includes:
- `created_by_extension_id` — which extension created it
- `source: "extension"` — distinguishes from `source: "user"` or `source: "schedule"`

---

## 4. API Surface

### 4.1 API Style

- **REST** over HTTPS with JSON request/response bodies
- **OpenAPI 3.1 specification** — single source of truth for:
  - Auto-generated client SDKs (TypeScript, Python, Swift, Kotlin)
  - Interactive API documentation
  - Request/response validation
- **URL-based versioning** — `/v1/sessions`, `/v1/webhooks`, etc.
  - Breaking changes ship in `/v2/`
  - Old versions deprecated with published sunset timelines

### 4.2 Endpoints

#### Session Management

```
GET    /v1/sessions                    # List extension's sessions
POST   /v1/sessions                    # Create a focus session
GET    /v1/sessions/:id                # Get session details
DELETE /v1/sessions/:id                # Cancel a session
PATCH  /v1/sessions/:id/extend         # Extend session duration
```

#### Policy Management

```
GET    /v1/policies                    # List extension's policies
POST   /v1/policies                    # Create a block policy
GET    /v1/policies/:id                # Get policy details
PATCH  /v1/policies/:id                # Update policy rules
DELETE /v1/policies/:id                # Delete a policy
```

#### Schedule Management

```
GET    /v1/schedules                   # List extension's schedules
POST   /v1/schedules                   # Create a scheduled/recurring session
GET    /v1/schedules/:id               # Get schedule details
PATCH  /v1/schedules/:id               # Update a schedule
DELETE /v1/schedules/:id               # Delete a schedule
```

#### Device State

```
GET    /v1/devices                     # List user's devices (name, platform, online status)
GET    /v1/devices/:id/status          # Get device enforcement health
```

#### Webhooks

```
GET    /v1/webhooks                    # List extension's webhook subscriptions
POST   /v1/webhooks                    # Register a webhook endpoint
DELETE /v1/webhooks/:id                # Remove a webhook subscription
```

#### Commitment Shield (Read-Only)

```
GET    /v1/commitment-shield/status    # Is the shield active? What categories are locked?
```

### 4.3 Example: Salah Extension Creates a Session

```http
POST /v1/sessions HTTP/1.1
Authorization: Bearer eyJhbGciOiJS...
Content-Type: application/json

{
  "name": "Dhuhr Prayer",
  "session_type": "focus",
  "policy_id": "pol_salah_quiet_mode",
  "start_at": "2026-09-25T12:15:00+03:00",
  "end_at": "2026-09-25T12:45:00+03:00",
  "enforcement_mode": "normal",
  "notification_mode": "silent",
  "device_ids": ["dev_iphone_14", "dev_macbook_pro"]
}
```

```http
HTTP/1.1 201 Created

{
  "id": "ses_abc123",
  "name": "Dhuhr Prayer",
  "source": "extension",
  "created_by_extension_id": "ext_salah_app",
  "status": "scheduled",
  ...
}
```

---

## 5. Webhook Events

### 5.1 Delivery Mechanism

**Standard HTTP webhooks** — NowFocus sends a `POST` request with a signed JSON payload to the extension's registered callback URL.

- **Signature verification:** HMAC-SHA256 signature in the `X-NowFocus-Signature` header, using the extension's webhook secret
- **Retry policy:** Exponential backoff on 4xx/5xx responses (3 retries, then mark as failed)
- **Idempotency:** Each event includes a unique `event_id` for deduplication

### 5.2 Event Catalog

| Event | Description | Payload includes |
|---|---|---|
| `session.started` | A focus session has begun | Session details, policy, device(s) |
| `session.completed` | A session reached its scheduled end time | Session details, duration |
| `session.cancelled` | A session was manually cancelled | Session details, cancelled_at |
| `session.extended` | A session's duration was extended | Session details, new end_at |
| `session.paused` | A session was paused | Session details, paused_at |
| `session.resumed` | A paused session was resumed | Session details, resumed_at |
| `schedule.triggered` | A scheduled session was automatically started | Schedule details, created session |
| `device.connected` | A device came online | Device details |
| `device.disconnected` | A device went offline | Device details, last_seen |
| `bedtime.started` | Bedtime Wind-Down mode began | Bedtime config, device(s) |
| `bedtime.ended` | Bedtime Wind-Down mode ended | Bedtime config, device(s) |

> [!NOTE]
> Extensions receive events **only for their own sessions** by default. Session lifecycle events for user-created sessions are also delivered if the extension has the corresponding read scope and has subscribed to the event type.

### 5.3 Example: Slack Integration Webhook Payload

```json
{
  "event_id": "evt_xyz789",
  "event_type": "session.started",
  "timestamp": "2026-09-25T09:00:00Z",
  "data": {
    "session": {
      "id": "ses_abc123",
      "name": "Deep Work",
      "source": "user",
      "status": "active",
      "start_at": "2026-09-25T09:00:00Z",
      "end_at": "2026-09-25T11:00:00Z",
      "enforcement_mode": "strict",
      "notification_mode": "quiet"
    },
    "user": {
      "id": "usr_456"
    }
  }
}
```

The Slack extension receives this, calls the Slack API to set the user's DND status for 2 hours.

---

## 6. Session Coexistence & Conflict Resolution

### 6.1 Policy Merge (Union)

Multiple sessions (user-created and extension-created) can coexist simultaneously. Active policies are **merged via union**:

```text
User Session: "Deep Work"              Extension Session: "Prayer Time"
├── Block youtube.com                  ├── Block slack.com
├── Block reddit.com                   ├── Block whatsapp.com
└── Block instagram.com                └── Notification mode: silent

                        ↓ Merged enforcement ↓

Active Policy (union):
├── Block youtube.com
├── Block reddit.com
├── Block instagram.com
├── Block slack.com
├── Block whatsapp.com
└── Notification mode: silent (most restrictive wins)
```

When one session ends, only its rules are removed. The other session's rules remain active.

### 6.2 Lock Mode Restriction

Extension-created sessions are **always cancellable** by the user. Only user-created sessions can use `enforcement_mode: locked`.

If an extension attempts to create a session with `enforcement_mode: locked`, the API returns:

```json
{
  "error": "forbidden",
  "message": "Extensions cannot create locked sessions. Use 'normal' or 'strict' enforcement mode."
}
```

### 6.3 Bedtime Wind-Down Restriction

Extensions **cannot** create Bedtime Wind-Down sessions. They **can** subscribe to `bedtime.started` and `bedtime.ended` webhook events to enable downstream integrations (e.g., smart home, sleep tracking).

---

## 7. Rate Limiting

### 7.1 Tiered by Plan

| Plan | Rate Limit | Burst |
|---|---|---|
| Pro | 60 requests/minute | 120 requests/minute for 10s |
| Business | 300 requests/minute | 600 requests/minute for 10s |
| Family | 60 requests/minute | 120 requests/minute for 10s |

Rate limits are **per-user** (all of a user's authorized extensions share the same pool).

### 7.2 Rate Limit Headers

Standard headers on every response:

```
X-RateLimit-Limit: 60
X-RateLimit-Remaining: 45
X-RateLimit-Reset: 1695650400
```

When rate-limited:

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 30
```

---

## 8. UI Integration

### 8.1 Extension Attribution

Extension-created resources are fully attributed in the NowFocus app UI:

```text
┌───────────────────────────────────────────┐
│  🕌 Dhuhr Prayer                          │
│  via Salah Extension                      │
│  12:15 — 12:45 · Silent · Normal          │
│                                  [Cancel]  │
└───────────────────────────────────────────┘
```

### 8.2 Extensions Management Section

A dedicated "Extensions" section in the app settings:

```text
┌──────────────────────────────────────────────────┐
│  Extensions                                      │
│                                                  │
│  🕌 Salah Extension                    [Revoke]  │
│  Scopes: sessions:write, schedules:write         │
│  Activity: 3 sessions created today              │
│  Authorized: Sep 15, 2026                        │
│                                                  │
│  💬 Slack Focus Sync                   [Revoke]  │
│  Scopes: sessions:read, webhooks:subscribe       │
│  Activity: 12 webhooks received this week        │
│  Authorized: Sep 20, 2026                        │
│                                                  │
│  ⚠️ You have 6 active extensions.                │
│  Many extensions may impact performance.         │
└──────────────────────────────────────────────────┘
```

### 8.3 Extension Count Warning

A soft warning is displayed when a user has **5+ active extensions** — no hard limit, but a UX nudge about potential performance implications.

---

## 9. Audit & Logging

### 9.1 Activity Summary (Privacy-First)

Following NowFocus's privacy-first principle, extensions are tracked via **activity summaries**, not detailed call logs:

| Metric | Example |
|---|---|
| Sessions created today | 3 |
| Sessions cancelled today | 0 |
| Webhooks received this week | 12 |
| API errors this week | 1 |
| Last active | 2 hours ago |

Detailed logging is reserved for **errors and security events** only (auth failures, rate limit violations, permission denials).

---

## 10. Business/Family Context

### 10.1 Organization-Level Extension Management

In **Business** plans, organization admins can:
- **Approve** extensions for the organization (allowlist)
- **Deny** specific extensions (blocklist)
- **Pre-install** extensions for all team members
- View aggregated extension activity across the team

### 10.2 Family-Level Extension Management

In **Family** plans, the family admin (parent) can:
- Control which extensions are active on **child profiles**
- Approve/deny extension authorization requests from children
- View extension activity on child accounts

### 10.3 Data Model Implications

The extension authorization model must include:

```text
ExtensionAuthorization
├── id
├── user_id
├── extension_id
├── organization_id       (nullable — for Business context)
├── family_id             (nullable — for Family context)
├── managed_by_admin      (boolean — was this installed by an admin?)
├── scopes                (granted permission scopes)
├── authorized_at
├── revoked_at            (nullable)
└── status                (active, revoked, suspended)
```

---

## 11. Developer Experience

### 11.1 Developer Portal

Extension developers register via a **web-based developer portal**:

1. Create a developer account on the NowFocus web dashboard
2. Register a new extension (name, description, redirect URIs, requested scopes)
3. Receive `client_id` and `client_secret`
4. Implement the OAuth flow in their application
5. Users authorize the extension via the NowFocus consent screen

### 11.2 Documentation

Public developer documentation should include:

- **API Reference** — auto-generated from OpenAPI spec
- **Authentication Guide** — OAuth 2.0 flow walkthrough
- **Webhook Guide** — event catalog, signature verification, retry behavior
- **Rate Limiting Guide** — limits, headers, best practices
- **Example Integrations** — step-by-step tutorials:
  - "Build a Salah Prayer Extension"
  - "Build a Slack Focus Sync Integration"
  - "Build a Smart Home Bedtime Integration"

---

## 12. Data Model Changes Required

To make the core data models extension-friendly from day one, the following fields should be added:

### FocusSession

```diff
  public struct FocusSession {
      public let id: String
      public let policyId: String
+     public let source: SessionSource          // .user | .extension | .schedule
+     public let createdByExtensionId: String?   // null for user-created
+     public let extensionMetadata: ExtensionMeta? // extension name, icon for UI
      public let sessionType: SessionType
      public let startAt: Date
      public let endAt: Date
      public var status: FocusSessionStatus
      public let enforcementMode: EnforcementMode
      public let notificationMode: NotificationMode
+     public var pausedAt: Date?                 // for session.paused support
      public let createdAt: Date
      public var completedAt: Date?
      public var cancelledAt: Date?
      public let deviceId: String
      public let revision: Int
  }

+ public enum SessionSource: String, Codable {
+     case user
+     case extension_api = "extension"
+     case schedule
+ }
+
+ public struct ExtensionMeta: Codable {
+     public let extensionId: String
+     public let extensionName: String
+     public let extensionIconUrl: String?
+ }
```

### BlockPolicy

```diff
  public struct BlockPolicy {
      public let id: String
      public var name: String
      public let mode: PolicyMode
+     public let source: SessionSource
+     public let createdByExtensionId: String?
      public var domains: [DomainRule]
      public var applications: [ApplicationRule]
      public var categories: [String]
      public let notificationPolicy: NotificationMode
      public let createdAt: Date
      public var updatedAt: Date
      public let revision: Int
  }
```

### New Models

```swift
// Extension registration (backend/database)
public struct Extension: Codable, Identifiable {
    public let id: String                      // ext_xxx
    public let name: String
    public let description: String
    public let developerName: String
    public let iconUrl: String?
    public let redirectUris: [String]
    public let requestedScopes: [String]
    public let clientId: String
    // client_secret stored separately, hashed
    public let createdAt: Date
    public let status: ExtensionStatus         // active, suspended, revoked
}

public enum ExtensionStatus: String, Codable {
    case active
    case suspended
    case revoked
}

// Per-user extension authorization
public struct ExtensionAuthorization: Codable, Identifiable {
    public let id: String
    public let userId: String
    public let extensionId: String
    public let organizationId: String?         // for Business context
    public let familyId: String?               // for Family context
    public let managedByAdmin: Bool
    public let grantedScopes: [String]
    public let authorizedAt: Date
    public var revokedAt: Date?
    public let status: AuthorizationStatus     // active, revoked
}

public enum AuthorizationStatus: String, Codable {
    case active
    case revoked
}

// Webhook subscription
public struct WebhookSubscription: Codable, Identifiable {
    public let id: String
    public let extensionId: String
    public let userId: String
    public let callbackUrl: String
    public let eventTypes: [String]            // ["session.started", "session.completed"]
    public let secret: String                  // for HMAC signature
    public let createdAt: Date
    public let status: WebhookStatus
}

public enum WebhookStatus: String, Codable {
    case active
    case paused
    case failed                                // after exhausting retries
}
```

---

## 13. Security Considerations

| Concern | Mitigation |
|---|---|
| Malicious extension creates unwanted sessions | User can always cancel extension-created sessions |
| Extension tries to use Lock Mode | API rejects `enforcement_mode: locked` for extensions |
| Extension modifies Commitment Shield | No write scope exists; API has no write endpoints |
| Webhook endpoint receives forged events | HMAC-SHA256 signature verification on every webhook |
| Extension exceeds API limits | Tiered rate limiting with `429` responses |
| Compromised OAuth tokens | Token rotation, refresh token revocation, short-lived access tokens |
| Extension persists after user revokes | Revocation immediately invalidates all tokens and webhook subscriptions |
| Admin in Business plan installs malicious extension for team | Audit log of admin actions, org members can see which extensions are active |

---

## 14. What NOT to Build Now

This spec is a **design document only**. The following should be deferred until the core product has meaningful user adoption:

| Deferred | Reason |
|---|---|
| Extension Marketplace / Storefront | Separate product; needs curation, review, trust infrastructure |
| Local device API | Requires daemon changes; cloud API covers all V1 use cases |
| Auto-generated SDKs | Ship API docs first; SDKs come when developer adoption warrants it |
| Extension analytics dashboard | Activity summaries in the app settings are sufficient for V1 |
| Paid extensions / revenue sharing | Requires marketplace infrastructure |
| Extension review/approval process | Not needed until marketplace exists |

---

## 15. Implementation Phases

### Phase A — Foundation (Build with Core Product)

Add `source`, `created_by_extension_id`, and `extension_metadata` fields to `FocusSession` and `BlockPolicy` data models now, even before the extension API exists. This prevents migration pain later.

### Phase B — Extension API v1 (Post-Pro Launch)

1. Extension registration on developer portal
2. OAuth 2.0 flow
3. Session Management endpoints
4. Schedule Management endpoints
5. Policy Management endpoints (read/write)
6. Device State endpoints (read-only)
7. Webhook registration and delivery
8. Rate limiting
9. App UI: Extensions management section

### Phase C — Business/Family Context

1. Organization-level extension management
2. Family-level extension management
3. Admin approval/deny workflows

### Phase D — Ecosystem Growth

1. Extension Marketplace
2. Auto-generated SDKs
3. Local device API
4. Extension analytics
5. Paid extension support

---

## 16. Decision Log

| # | Decision | Choice | Alternatives Considered |
|---|---|---|---|
| 1 | Timing | Design now, build later | Build in MVP; vision doc only |
| 2 | Extension model | External API (no in-app code execution) | In-app extensions; both |
| 3 | API location | Cloud-primary, optional local API later | Local only; cloud only |
| 4 | API surface | Sessions + Webhooks + Schedules + Devices + Policies | All surfaces; minimal |
| 5 | Authentication | OAuth 2.0 with scoped permissions | API keys; OAuth + API keys |
| 6 | Scopes | Granular resource-based | Tiered levels; grouped sets |
| 7 | Resource ownership | Strict isolation (own resources only) | Full access; opt-in elevation |
| 8 | Session conflicts | Coexist, union merge | User priority; most restrictive; reject overlap |
| 9 | Event delivery | HTTP webhooks (HMAC-signed) | WebSockets; SSE; both |
| 10 | Webhook events | 11 event types (session, schedule, device, bedtime) | Minimal; include violations |
| 11 | Plan availability | Pro and above | All plans; developer add-on |
| 12 | Lock Mode | Forbidden for extensions | Allowed; user-approved |
| 13 | Marketplace | Not now (self-hosted extensions) | Build marketplace; design model for later |
| 14 | UI attribution | Full attribution + Extensions management section | Minimal label; no attribution |
| 15 | Rate limits | Tiered by plan (60/300 req/min) | Per-extension; per-user-per-extension |
| 16 | Extension count | No hard limit, warning at 5+ | Hard limit; no limit |
| 17 | Logging | Activity summaries (privacy-first) | Full audit log; minimal |
| 18 | Business/Family | Supported with admin controls | Individual only; design for later |
| 19 | Commitment Shield | Read-only (no write) | No access; full access |
| 20 | Bedtime Wind-Down | Webhook events only (no create) | Full access; no access |
| 21 | API style | REST + OpenAPI spec | GraphQL; REST without spec |
| 22 | API versioning | URL-based (`/v1/`) | Header-based; additive-only |
| 23 | Developer portal | Web-based self-service | CLI; portal + docs site |
