# NowFocus — Product Plans & Monetization Strategy

## 1. Overview

NowFocus is a cross-platform digital-focus and distraction-blocking application designed to help people intentionally control their digital environment.

The application allows users to:

- Block distracting websites.
- Block distracting applications.
- Silence or restrict notifications.
- Create focus sessions.
- Schedule recurring focus periods.
- Synchronize focus sessions across devices.
- Track focus behavior and productivity.
- Create structured routines for work, study, and digital detox.
- Permanently block harmful and distracting websites and apps (e.g., adult content, gambling) via the 14-Day Always-Blocked Commitment Shield.

The product should be built around a simple principle:

> **The core ability to focus should be accessible for free. Advanced control, automation, synchronization, analytics, and organizational features are monetized.**

The application should not depend on selling personal data.

---

# 2. Product Vision

The long-term goal is not to become another generic website blocker.

The product should evolve into a:

> **Personal digital environment manager.**

Instead of simply blocking websites, the application should understand that users have different contexts:

- Work
- Study
- Sleep
- Reading
- Deep work
- Meetings
- Exercise
- Family time
- Digital detox

Each context can have its own rules.

For example:

```text
WORK
├── Block YouTube
├── Block Reddit
├── Block Facebook
├── Block Instagram
├── Allow Slack
├── Allow Gmail
├── Allow GitHub
└── Notifications restricted

STUDY
├── Block social media
├── Block entertainment
├── Allow educational websites
└── Phone notifications restricted
```

---

# 3. Business Model

The primary business model is:

```text
                    NowFocus
                       |
        ┌──────────────┼──────────────┐
        |              |              |
       Free           Pro           Business
        |              |              |
     Acquisition    Individual      Teams
     & retention    monetization    organizations
```

A Family plan sits between Pro and Business.

## Revenue streams

1. Pro subscriptions
2. Family subscriptions
3. Business/team subscriptions
4. AI features
5. Paid focus programs
6. Marketplace commissions
7. Optional hardware integrations
8. Carefully selected affiliate partnerships

Advertising should not be a core revenue source.

Selling user data should not be part of the business model.

---

# 4. Free Plan

## Purpose

The Free plan is primarily an acquisition and retention mechanism.

The user should be able to install the application and immediately experience its primary value.

## Free features

### Blocking

- Website blocking
- Application blocking
- Basic URL/domain blocklists
- Basic application blocklists
- Manual blocking
- Basic focus sessions

### Focus Sessions

Users can create a focus session:

```text
Focus for 60 minutes
        ↓
Start Session
        ↓
Distracting apps/sites blocked
        ↓
Session completed
```

### Scheduling

Allow basic schedules such as:

```text
Monday-Friday
09:00 → 11:00
```

However, advanced scheduling should be reserved for Pro.

### Statistics

Provide basic statistics:

- Focus sessions completed
- Total focus time
- Sessions interrupted
- Most blocked applications
- Most blocked websites

### Devices

Initially:

- 1 primary device

The exact device limitation should be validated through experimentation.

---

# 5. Pro Plan

## Target customer

Individuals who already use the application and want significantly more control.

## Proposed pricing

Initial pricing can be tested around:

```text
Monthly:
$4–8/month

Annual:
$30–60/year
```

Pricing should ultimately be determined through market testing rather than assumptions.

Localized pricing should be considered for markets such as Egypt and other price-sensitive regions.

## Pro features

### Unlimited Blocking

- Unlimited websites
- Unlimited applications
- Unlimited blocklists
- Multiple rule groups

### Advanced Scheduling

Example:

```text
Work
Monday-Friday
08:30 → 17:00

Study
Saturday
10:00 → 14:00

Sleep
Every day
23:00 → 07:00
```

### Recurring Focus Routines

Users can define routines:

```text
Morning Deep Work
09:00 → 11:00

Lunch
11:00 → 12:00

Deep Work
12:00 → 14:00
```

### Cross-device synchronization

A focus session started on one device can affect other devices.

Example:

```text
Mac
  |
  | Start Focus
  ↓
Backend
  |
  ├── iPhone
  ├── Windows
  └── Mac
```

### Advanced Statistics

Users can see:

- Daily focus time
- Weekly focus time
- Monthly focus time
- Focus consistency
- Blocked distraction attempts
- Most distracting websites
- Most distracting applications
- Focus trends
- Session completion rate

### Custom Rules

Example:

```text
Allow:
github.com
stackoverflow.com

Block:
youtube.com
reddit.com
facebook.com
instagram.com
```

### Lock Mode

Users can optionally prevent themselves from easily disabling a session.

Possible mechanisms include:

- Countdown before disabling
- Confirmation dialogs
- Session commitment
- Restricted settings during sessions

The product should not attempt to bypass OS security mechanisms or create unsafe device restrictions.

### Bedtime Wind-Down & Sleep Guard (Blue Light Reduction)

A dedicated evening and bedtime focus automation specifically engineered to improve sleep hygiene by restricting phone usage before sleep.

#### The Problem
Late-night smartphone usage and blue light exposure disrupt the natural circadian rhythm, delay melatonin secretion, and encourage compulsive bedtime "doomscrolling," leading to severe sleep debt and grogginess.

#### The Pro Solution
- **Automated Wind-Down Buffer:** Users configure a pre-bedtime wind-down window (e.g., 30 to 120 minutes before their scheduled bedtime, such as 21:30 to 23:00, continuing through wake-up at 07:00).
- **Proactive Phone Restriction:** Automatically locks down high-stimulation, high-blue-light mobile apps (social media, short-form video feeds, streaming, games, and web browsers) during the wind-down period.
- **Essential Nighttime Allowlist:** Keeps essential night utilities strictly accessible without opening the door to distraction:
  - Phone calls and emergency contacts.
  - Alarms, timers, and clock app.
  - Sleep-supporting audio (meditation, white noise, audiobooks, and health/sleep tracking apps like Calm or Headspace).
- **Strict Sleep Lock:** Optional late-night lock mode preventing impulsive disabling or session cancellations when willpower and cognitive resistance are lowest.
- **Cross-Device Bedtime Synchronization:** Pro synchronization ensures that when Bedtime Wind-Down begins, it simultaneously triggers across the user's phone, tablet, and desktop—preventing the common habit of putting down the phone only to pick up a laptop in bed.
- **Circadian Display Coordination:** Integrates with system Do Not Disturb / Sleep Focus modes and prompts switching screens to Grayscale or Night Shift to actively eliminate stimulating blue light cues.

### Always-Blocked Commitment Shield (14-Day Strict Lock)

A permanent, 24/7 protection mode specifically designed for adult content blocking, addiction recovery, and continuous elimination of destructive digital habits. Unlike temporary focus sessions, Always-Blocked rules run continuously in the background at the OS layer.

#### Key Mechanics

1. **Strict 14-Day Lock-In Commitment:**
   - When a website, app, or curated category is locked into the Always-Blocked list, it **cannot be disabled, unblocked, or removed until a full 14 days (336 hours) have passed**.
   - No early bypass, emergency toggles, or support backdoor overrides exist.

2. **Per-Item & Per-Batch Countdown Timers:**
   - Every blocked item maintains its own independent 14-day lock expiration timestamp (`locked_until = now() + 14 days`).
   - If a group/batch of items is activated together (e.g., enabling the curated Adult Websites category), they share the same 14-day expiration window.
   - Any single website or application added later initiates its own independent 14-day lock period from the exact moment of addition.

3. **Permanent Retention After Expiration:**
   - Once an item's 14-day lock period elapses, the item **remains blocked permanently by default**.
   - The user is now permitted to unlock or remove the item if desired, or re-commit it for another 14-day lock period.

4. **Curated Categories & Custom Entries:**
   - **One-Click Curated Presets:** Built-in curated category filters (such as "Adult Websites & Explicit Content" and "Online Gambling") powered by regularly updated local DNS sinkhole blocklists.
   - **Custom Domain Rules:** Support for user-specified domains (e.g., `distracting-domain.com`) with automatic canonicalization and subdomain matching.
   - **Custom Application Rules:** Support for native desktop executables and mobile bundle IDs (e.g., gaming clients, social media apps).

5. **Accidental Lockout Safeguard (60-Second Grace Period):**
   - Adding an item prompts an explicit pre-commitment warning modal detailing that the selection is irreversible for 14 days.
   - Immediately upon activation, an active **60-second cancellation grace period** begins. If an erroneous item was added by mistake, the user can cancel it within this 60-second window.
   - Once the 60-second window expires, the 14-day lock becomes strictly irrevocable.

6. **Full Anti-Tamper Hardening:**
   - **Monotonic Network Time (NTP / Server Sync):** Prevents users from bypassing the lock by manually changing the system clock forward on macOS, Windows, iOS, or Android. Expiration is verified against trusted server/NTP time.
   - **Daemon-Level Uninstallation Lock:** While any active 14-day lock is running, the macOS LaunchDaemon / Windows Background Service enforces process protection and administrative uninstallation blocks.

7. **Commitment Shield User Experience:**
   - **Websites:** Navigating to an always-blocked domain in any browser resolves via the local DNS proxy to a local HTTP sinkhole serving a clean, minimalist "Commitment Shield" screen displaying:
     - "Always Blocked • Commitment Shield"
     - Remaining lock duration: *"Locked until [Date] — [X] days remaining"*
     - A calming mindfulness prompt.
   - **Applications:** Attempting to launch a blocked application results in instantaneous process termination by the daemon, paired with a native OS desktop banner notification.

8. **Plan Availability & Cross-Device Synchronization:**
   - Available across all paid tiers (**Standard**, **Pro**, and **Family**).
   - In Family plans, parents can apply the 14-day Always-Blocked Commitment Shield to child devices to guarantee safe browsing.
   - Seamless cross-device synchronization ensures that adding a blocked item on one device immediately commits it across all linked devices with the identical authoritative server lock expiration timestamp.

### Developer Extensions & Public API

The Pro plan unlocks programmatic access to NowFocus via a public developer API, enabling external integrations and personal workflow automation without executing untrusted third-party code in-app:

- **OAuth 2.0 Authorization:** Securely connect external tools (e.g., Slack, Muslim prayer times/Salah apps, smart home lighting) using standard OAuth 2.0 with granular resource scopes.
- **REST Endpoints (`/v1/`):** Full control over focus sessions (`/v1/sessions`), block policies (`/v1/policies`), schedules (`/v1/schedules`), and device status (`/v1/devices`).
- **Realtime Signed Webhooks:** HMAC-SHA256 signed event delivery for session lifecycles (`session.started`, `session.completed`, `session.cancelled`) and bedtime notifications (`bedtime.started`, `bedtime.ended`).
- **Fair-Use Rate Limiting:** 60 requests/minute (120 req/min burst for 10s) per user.
- **User Safety Guarantees:** Extensions operate under strict isolation (can only edit their own resources), cannot initiate un-cancellable lock mode sessions, and cannot modify the Always-Blocked Commitment Shield.
- **In-App Management:** Attribution badges for extension sessions, plus self-service authorization revocation in Settings > Extensions.

For architectural details, see [extension_system_architecture.md](file:///Users/safwat/Coding/Projects/side-projects/now-focus/extension_system_architecture.md).

---

# 6. Family Plan

## Target customer

Parents and families.

## Proposed pricing

Approximately:

```text
$8–15/month
```

or an equivalent annual price.

## Features

- Multiple family members
- Multiple devices
- Individual profiles
- Child schedules
- Study schedules
- Bedtime schedules (utilizing the Bedtime Wind-Down & Sleep Guard engine for children's devices)
- Always-Blocked Commitment Shield for child devices (locking out adult websites, explicit content, and addictive apps with tamper-proof parent enforcement)
- Website restrictions
- Application restrictions
- Family dashboard
- Family extension controls (parents approve and oversee third-party extensions on child profiles)
- Device management

Example:

```text
Family
│
├── Parent
│
├── Child 1
│   ├── Study: 17:00–20:00
│   └── Sleep: 22:00–07:00
│
└── Child 2
    ├── Study: 16:00–19:00
    └── Sleep: 21:30–07:00
```

Privacy should remain a core principle.

Parents should receive useful control without turning the product into invasive employee-style surveillance.

---

# 7. Business / Team Plan

## Target customer

Companies that want to reduce digital distractions without monitoring employee activity.

This is an important distinction.

The product should position itself around:

> **Focus without surveillance.**

## Potential customers

- Software companies
- Agencies
- Startups
- Remote teams
- Education companies
- Call centers
- Research teams
- Professional services companies

## Features

### Organization Management

- Organization account
- Team management
- User invitations
- Roles and permissions

### Focus Policies

Administrators can create policies:

```text
Engineering
09:00–12:00
Deep Work

Marketing
10:00–12:00
Focus Period
```

### Team Focus Sessions

A manager could create:

```text
Team Deep Work

Tuesday
10:00–12:00
```

All participating employees enter focus mode.

### Team Analytics

Only aggregated and privacy-preserving statistics should be exposed.

For example:

```text
Team Focus Time
████████████████  84%

Sessions Completed
██████████████    71%

Average Session
78 minutes
```

Avoid exposing:

- Individual browsing history
- Private websites
- Personal activity
- Detailed surveillance data

unless explicitly designed and consented to for a legitimate organizational requirement.

### Organization-Level Extension Governance

IT and team administrators maintain centralized governance over third-party integrations:

- **Admin Approval Workflows:** Configure allowlists or blocklists of approved external extensions before members can authorize them.
- **Team-Wide Pre-Installation:** Automatically connect essential enterprise extensions (e.g., Slack status sync or team calendar focus triggers) across all team member accounts.
- **Aggregated Integration Audits:** View organization-wide extension activity summaries (sessions generated, event rates, error counts) while preserving employee privacy.
- **Enterprise Rate Limits:** 300 requests/minute (600 req/min burst for 10s) across the organization's API quota.

---

# 8. AI Features

AI should be an enhancement rather than the core value proposition.

## AI Focus Planner

User:

> "I need to finish my project today and have six hours."

AI generates:

```text
09:00–10:30
Deep Work

10:30–10:45
Break

10:45–12:15
Deep Work

12:15–13:00
Lunch

13:00–14:30
Deep Work

14:30–14:45
Break

14:45–16:15
Deep Work
```

The system automatically creates the required focus rules.

## Distraction Analysis

AI can analyze aggregated user behavior and identify patterns such as:

> "Your longest distraction periods usually occur between 14:00 and 16:00."

## Focus Recommendations

Examples:

- Recommend a shorter session
- Recommend removing a distracting website
- Recommend changing a schedule
- Recommend additional breaks

## AI Weekly Review

Example:

```text
This week:

Focus time: 18h 42m
Completed sessions: 27
Interrupted sessions: 5

Your strongest period:
09:00–12:00

Most frequent distraction:
YouTube

Recommendation:
Move your most important work to the morning.
```

AI features can be included in Pro or sold as an optional AI add-on.

---

# 9. Focus Programs

The application can eventually sell structured programs.

Examples:

## Deep Work — 30 Days

```text
Week 1
30–45 minute sessions

Week 2
60 minute sessions

Week 3
90 minute sessions

Week 4
120 minute sessions
```

## Digital Detox — 14 Days

Gradually reduce exposure to distracting applications.

## Exam Mode

Designed for students:

- Study schedules
- Website restrictions
- Breaks
- Daily goals
- Progress tracking

## Coding Focus Program

Designed specifically for developers:

- IDE-friendly rules
- GitHub allowed
- Documentation allowed
- Social media blocked
- YouTube optionally blocked
- Deep-work sessions

Programs can be:

- Free
- Premium
- Creator-generated

---

# 10. Marketplace

A later-stage opportunity is a Focus Program marketplace.

Creators could publish:

- Study programs
- Productivity systems
- Deep-work routines
- Digital detox programs
- Exam preparation plans
- Professional focus routines

Example:

```text
Focus Marketplace

30-Day Deep Work
$9

IELTS Study Program
$12

Coding Interview Focus
$15

Digital Detox
$7
```

The platform takes a percentage of transactions.

This should only be implemented after the core product has meaningful user adoption.

---

# 11. Hardware Opportunities

Long-term, the platform could integrate with physical focus devices.

For example:

```text
          Physical Focus Button
                  |
                  ↓
             Focus API
                  |
        ┌─────────┼─────────┐
        ↓         ↓         ↓
       Mac      iPhone    Windows
        |         |         |
      Block     Block     Block
      Sites     Apps      Sites
```

A physical focus button could start a synchronized focus session.

This is a later-stage opportunity, not an MVP requirement.

---

# 12. Affiliate Revenue

Affiliate partnerships can provide supplementary revenue.

Potential categories:

- Books
- Productivity courses
- Headphones
- Desks
- Productivity hardware
- Learning platforms

Recommendations should be clearly identified as commercial recommendations.

Affiliate revenue should never influence the blocking experience.

---

# 13. Advertising Strategy

Traditional advertising should not be the primary business model.

Reasons:

1. Ads create distraction.
2. Ads reduce trust.
3. Ads conflict with the product's purpose.
4. Ads generate relatively little revenue per user compared with subscriptions.
5. Privacy-focused users may perceive advertising as surveillance.

If advertisements are ever introduced, they should be:

- Limited
- Clearly labeled
- Contextually relevant
- Separate from focus sessions
- Never personalized using sensitive activity data

---

# 14. Privacy as a Product Feature

Privacy should not merely be a legal requirement.

It should become part of the product positioning.

Potential positioning:

> "Your focus data belongs to you."

The company should avoid selling personal data.

Recommended principles:

- Minimal data collection
- Encryption in transit
- Encryption at rest
- Transparent privacy policy
- Data export
- Account deletion
- Clear telemetry controls
- No selling browsing history
- No selling application usage history

For analytics, prefer aggregated data wherever possible.

---

# 15. Recommended Monetization Roadmap

## Phase 1 — MVP

Build:

- Free blocking
- Focus sessions
- Basic schedules
- Basic statistics
- Account system
- macOS
- iOS
- Android
- Windows

Do not build:

- Marketplace
- Hardware
- AI
- Business administration
- Complex family management

Goal:

> Prove that users repeatedly use the product.

---

# Phase 2 — Pro

Add:

- Advanced schedules
- Unlimited rules
- Cross-device synchronization
- Advanced analytics
- Lock mode
- Custom focus routines
- Bedtime Wind-Down & Sleep Guard (phone restriction & blue light reduction)

Introduce Pro subscription.

Primary goal:

> Determine whether users will pay for advanced control.

---

# Phase 3 — Family

Add:

- Family accounts
- Multiple users
- Child profiles
- Study schedules
- Bedtime schedules
- Family dashboard

Primary goal:

> Expand the addressable market beyond individual productivity users.

---

# Phase 4 — AI

Add:

- AI Focus Planner
- AI weekly review
- Distraction analysis
- Personalized recommendations

Primary goal:

> Increase retention and premium conversion.

---

# Phase 5 — Business

Add:

- Organizations
- Teams
- Admin dashboard
- Team focus sessions
- Aggregated analytics
- Organization policies
- Billing

Primary goal:

> Establish higher-value B2B revenue.

---

# Phase 6 — Marketplace & Developer Ecosystem

Add:

- Focus programs
- Creator accounts
- Program publishing
- Purchases & revenue sharing
- Developer Portal & third-party extension directory
- Verified extensions catalog
- Auto-generated client SDKs (TypeScript, Python, Swift, Kotlin)

Primary goal:

> Create an active ecosystem of creators, developers, and integrations around the focus platform (see `extension_system_architecture.md`).

---

# Phase 7 — Hardware

Explore:

- Focus button
- Desktop hardware
- Dedicated focus devices
- Third-party integrations

Primary goal:

> Extend the product beyond software.

---

# 16. Recommended Business Model

The recommended long-term model is:

```text
                    REVENUE
                       |
       ┌───────────────┼────────────────┐
       │               │                │
   Consumer          Family           Business
       │               │                │
      Pro           Family Plan       Teams
       │
       └────── AI / Programs ──────┐
                                   │
                              Marketplace
```

The product should remain free enough that users can experience the core value without paying.

The premium product should sell:

> **Control + automation + synchronization + insight**

rather than:

> **the ability to block websites.**

That distinction is important for growth.

---

# 17. Key Business Metrics

## Acquisition

- Downloads
- Website visitors
- Install conversion
- Account creation rate

## Activation

Measure whether users reach the first meaningful value event.

For example:

> User completes their first 20-minute focus session.

Track:

- First session creation
- First session started
- First session completed
- Time to first completed session

## Engagement

- DAU
- WAU
- MAU
- Focus sessions/user
- Focus minutes/user
- Sessions completed/user

## Retention

- Day 1
- Day 7
- Day 30
- Month 3

The most important question is:

> Do users continue using the blocker after the initial novelty disappears?

## Monetization

- Free → Pro conversion
- Trial → paid conversion
- Monthly recurring revenue
- Annual recurring revenue
- Average revenue per paying user
- Churn
- Lifetime value

## Product quality

- Crash rate
- Blocking success rate
- False-positive block rate
- Sync failures
- Session interruption rate
- Battery impact
- CPU/memory usage

For this product, technical reliability directly affects monetization.

If blocking fails, users lose trust.

---

# 18. Critical Product Principle

Do not build a giant productivity platform immediately.

The initial product should answer one question:

> **Can we reliably help users stay away from distractions when they intentionally want to focus?**

Everything else comes later.

The initial product should therefore prioritize:

1. Reliable blocking
2. Excellent UX
3. Fast focus-session creation
4. Cross-platform consistency
5. Low battery/resource usage
6. Privacy
7. Reliability
8. Simple analytics

AI, marketplaces, teams, hardware, and complex monetization should come after product-market validation.
