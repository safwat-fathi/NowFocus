# Versioning Policy & Release Guidelines

## Project Status: Alpha

All NowFocus applications and services are currently in **Alpha** (pre-Beta).
They are under active daily development and experimentation. Features, local storage schemas, synchronization contracts, and system extension architectures are subject to refinement and change.

---

## Semantic Versioning (SemVer) Format

NowFocus follows a three-part Semantic Versioning format across all client applications and services:

$$\text{MAJOR} . \text{MINOR} . \text{PATCH}$$

For Alpha releases, the **MAJOR** version is fixed at `0`:

$$0 . \text{MINOR} . \text{PATCH}$$

---

## Version Bump Rules

### 1. Routine Releases & Updates Bump the PATCH Version
During the Alpha stage, **routine updates, bug fixes and UI improvements increment the `PATCH` number**. The `MINOR` version number stays unchanged.

Examples:
- If current version is `0.2.0`, the next release is `0.2.1`, followed by `0.2.2`, `0.2.3`, etc.
- If current version is `0.4.2`, the next release is `0.4.3`.
- If current version is `0.8.1`, the next release is `0.8.2`.

> [!IMPORTANT]
> Do NOT bump the minor semantic version for regular releases during Alpha. Minor bumps are for notable feature releases only (rule 2).

### 2. Minor Bumps for Notable Feature Releases
The **MINOR** version is bumped (and PATCH reset to `0`) for a notable user-facing feature release or a major milestone (advancing to Beta, architectural migrations, protocol overhauls). A minor bump requires explicit maintainer consensus.

Example: `0.9.0` (Android), `0.5.0` (Windows) and `0.4.0` (macOS) shipped profile group save and the DNS screen with always-on DNS.

---

## Current Platform Baselines

Platform versions are tracked independently while adhering to the 3-part SemVer scheme:

| Platform | Current Version | Next Release | Config / Manifest Location |
| :--- | :--- | :--- | :--- |
| **Windows** | `0.5.0` | `0.5.1` | `apps/windows/src-tauri/tauri.conf.json`, `apps/windows/src-tauri/Cargo.toml`, `apps/windows/package.json` |
| **Android** | `0.9.0` | `0.9.1` | `apps/android/app/build.gradle.kts` (`versionName`, bump `versionCode`) |
| **macOS** | `0.4.0` | `0.4.1` | `apps/macos/project.yml` (`MARKETING_VERSION`, bump `CURRENT_PROJECT_VERSION`) |
| **iOS** | `0.1.0` | `0.1.1` | `apps/ios/project.yml` (`MARKETING_VERSION`, bump `CURRENT_PROJECT_VERSION`) |
| **API** | `0.0.2` | `0.0.3` | `services/api/package.json` |

---

## Git Tagging Convention

Releases are tagged individually per platform using platform prefixes and the standard `v0.minor.patch` format:

- **Windows**: `windows-v0.5.x` (e.g., `windows-v0.5.0`)
- **Android**: `android-v0.9.x` (e.g., `android-v0.9.0`)
- **macOS**: `macos-v0.4.x` (e.g., `macos-v0.4.0`)
- **iOS**: `ios-v0.1.x` (e.g., `ios-v0.1.1`)
- **API**: `api-v0.0.x` (e.g., `api-v0.0.2`)

Platform-specific release documents:
- [Windows Release Guide](file:///Users/safwat/Coding/Projects/side-projects/now-focus/docs/windows-release.md)
- [Android Release Guide](file:///Users/safwat/Coding/Projects/side-projects/now-focus/docs/android-release.md)
