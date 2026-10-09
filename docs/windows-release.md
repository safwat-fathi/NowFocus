# Windows release (public installer + in-app updates)

The installer is **not code-signed** (Windows SmartScreen warns on first run: More info, then Run anyway). Updates are separate: the Tauri updater verifies a free minisign signature, so the app only installs builds you signed with the updater key.

How it fits together:

- **GitHub Releases** (`safwat-fathi/NowFocus`) hold the installer, its `.sig` and `latest.json`.
- **`api.nowfocus.online`** reads `latest.json` (cached 5 min) and serves:
  - `GET /v1/downloads/windows` -> 302 to the newest `.exe`. The website's Download button points here.
  - `GET /v1/updates/windows/<current version>` -> 204 if up to date, else the update JSON. This is the endpoint in `apps/windows/src-tauri/tauri.conf.json`.
- The app checks at launch and every 6 hours, shows a banner, and installs from Settings, Updates. It refuses while a session is running (an update stops the blocking service).

## One-time: updater key

    cd apps/windows && pnpm exec tauri signer generate -w ~/.tauri/nowfocus.key

Back up the private key and its password off this machine (password manager). **If it is lost, no installed copy can ever update; users must reinstall.** Then:

1. Put the public key (`~/.tauri/nowfocus.key.pub`) in `plugins.updater.pubkey` in `apps/windows/src-tauri/tauri.conf.json`.
2. Add GitHub repository secrets `TAURI_SIGNING_PRIVATE_KEY` (the key file's contents) and `TAURI_SIGNING_PRIVATE_KEY_PASSWORD`.

## Each release

1. Bump the patch version in `apps/windows/src-tauri/tauri.conf.json`, `apps/windows/src-tauri/Cargo.toml` and `apps/windows/package.json` (e.g. `0.4.2` &rarr; `0.4.3`). Per [Versioning Policy](file:///Users/safwat/Coding/Projects/side-projects/now-focus/docs/versioning.md), only bump the patch number during Alpha; do not change the minor version. The release job fails if the tag and `tauri.conf.json` disagree.
2. Merge to main (the API must already be deployed once, so the endpoints exist).
3. `git tag windows-v<version> && git push origin windows-v<version>`
4. The **Windows** workflow's `release` job builds and publishes the GitHub Release. Check it has the `.exe`, the `.exe.sig` and `latest.json`.
5. `curl -I https://api.nowfocus.online/v1/downloads/windows` should redirect to the new `.exe`; installed copies pick it up within 6 hours.

Notes:

- Only a published (non-draft, non-pre-release) GitHub Release counts as "latest". The API looks at the repo's latest release, so a future non-Windows release would hide this one (see the `ponytail:` note in `services/api/src/updates/updates.service.ts`).
- The first build with the updater is the first one that can self-update; copies of an older build must be reinstalled once.
- SmartScreen reputation is per file, so the warning returns with each release until the installer is signed.
