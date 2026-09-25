#!/bin/bash
set -e

# Build script for NowFocus (macOS)
# This script compiles the Xcode project, signs it, and generates a DMG.
#
# Signing (optional): set DEVELOPER_ID_APPLICATION to your "Developer ID
# Application" certificate name to properly sign the app for distribution
# (requires an Apple Developer Program membership). Unset, the script falls
# back to a full ad-hoc signature (still better than the previous unsigned
# export, which is likely why daemon registration was failing).
#
# Notarization (optional, requires DEVELOPER_ID_APPLICATION): set
# NOTARY_PROFILE to a profile name created once via:
#   xcrun notarytool store-credentials <profile-name>
# create-dmg then signs, notarizes, and staples the DMG itself.
#
# One-time setup: brew install create-dmg

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="${PROJECT_DIR}/build"
APP_NAME="NowFocus"
ARCHIVE_PATH="${BUILD_DIR}/${APP_NAME}.xcarchive"
APP_PATH="${BUILD_DIR}/${APP_NAME}.app"
DMG_PATH="${BUILD_DIR}/${APP_NAME}.dmg"
DMG_SRC="${BUILD_DIR}/dmg-src"
BACKGROUND_IMAGE="${PROJECT_DIR}/scripts/dmg-background.png"

if ! command -v create-dmg >/dev/null 2>&1; then
  echo "ERROR: create-dmg not found. Install it with: brew install create-dmg" >&2
  exit 1
fi

echo "Building ${APP_NAME}..."

# Clean build directory
rm -rf "${BUILD_DIR}"
mkdir -p "${BUILD_DIR}"

# 1. Archive the project
# Clear xcodebuild's default DSTROOT (/tmp/<project>.dst): if a prior run was
# interrupted, this directory is left behind untracked, and xcodebuild's
# `clean` step then refuses to delete it ("not created by the build system"),
# failing the whole archive before the DMG step ever runs.
rm -rf "/tmp/${APP_NAME}.dst"
echo "Archiving project..."
xcodebuild -project "${PROJECT_DIR}/NowFocus.xcodeproj" \
           -scheme NowFocus \
           -configuration Release \
           -archivePath "${ARCHIVE_PATH}" \
           clean archive \
           CODE_SIGN_IDENTITY="-" \
           CODE_SIGNING_REQUIRED=NO \
           CODE_SIGNING_ALLOWED=NO

# 2. Export Archive (Normally this would be signed with Developer ID)
# For local dev without certificates, we can just copy the app out of the archive
echo "Exporting app..."
cp -R "${ARCHIVE_PATH}/Products/Applications/NowFocus.app" "${BUILD_DIR}/"

# 2b. Ensure daemon binary is embedded
DAEMON_DIR="${APP_PATH}/Contents/Library/LaunchDaemons"
mkdir -p "${DAEMON_DIR}"
if [ ! -f "${DAEMON_DIR}/NowFocusDaemon" ]; then
  echo "Daemon binary missing from app bundle, copying from archive..."
  # Find the daemon binary in the archive's build products
  DAEMON_BIN=$(find "${ARCHIVE_PATH}" -name "NowFocusDaemon" -type f ! -name "*.plist" | head -1)
  if [ -n "$DAEMON_BIN" ]; then
    cp "$DAEMON_BIN" "${DAEMON_DIR}/NowFocusDaemon"
    echo "Daemon binary copied successfully."
  else
    echo "WARNING: Could not find NowFocusDaemon binary in archive!"
  fi
fi
if [ ! -f "${DAEMON_DIR}/com.getnowfocus.daemon.plist" ]; then
  cp "${PROJECT_DIR}/NowFocusDaemon/com.getnowfocus.daemon.plist" "${DAEMON_DIR}/"
fi
echo "App bundle contents:"
ls -la "${DAEMON_DIR}/"

# 3. Sign the app. The archive step above disables signing entirely
# (CODE_SIGNING_ALLOWED=NO), which leaves the bundle unsealed -- a likely
# cause of SMAppService daemon registration failures. Always sign here,
# inside-out, ad-hoc by default or with a Developer ID if provided.
SIGN_IDENTITY="${DEVELOPER_ID_APPLICATION:--}"
if [ -n "${DEVELOPER_ID_APPLICATION:-}" ]; then
  echo "Signing with Developer ID: ${DEVELOPER_ID_APPLICATION}"
  SIGN_FLAGS=(--options runtime --timestamp)
else
  echo "WARNING: DEVELOPER_ID_APPLICATION not set -- signing ad-hoc only."
  echo "         Gatekeeper will still warn on other Macs. Set DEVELOPER_ID_APPLICATION"
  echo "         for a real Developer ID signature (see header comment)."
  SIGN_FLAGS=(--timestamp=none)
fi

SPARKLE="${APP_PATH}/Contents/Frameworks/Sparkle.framework"
codesign --force --sign "${SIGN_IDENTITY}" "${SIGN_FLAGS[@]}" \
  "${SPARKLE}/Versions/B/XPCServices/Installer.xpc"
codesign --force --sign "${SIGN_IDENTITY}" "${SIGN_FLAGS[@]}" \
  --preserve-metadata=entitlements "${SPARKLE}/Versions/B/XPCServices/Downloader.xpc"
codesign --force --sign "${SIGN_IDENTITY}" "${SIGN_FLAGS[@]}" \
  "${SPARKLE}/Versions/B/Autoupdate"
codesign --force --sign "${SIGN_IDENTITY}" "${SIGN_FLAGS[@]}" \
  "${SPARKLE}/Versions/B/Updater.app"
codesign --force --sign "${SIGN_IDENTITY}" "${SIGN_FLAGS[@]}" \
  "${SPARKLE}"
codesign --force --sign "${SIGN_IDENTITY}" "${SIGN_FLAGS[@]}" \
  "${APP_PATH}/Contents/Frameworks/NowFocusCore.framework"
codesign --force --sign "${SIGN_IDENTITY}" "${SIGN_FLAGS[@]}" \
  "${DAEMON_DIR}/NowFocusDaemon"
codesign --force --sign "${SIGN_IDENTITY}" "${SIGN_FLAGS[@]}" \
  "${APP_PATH}"

# 4. Create DMG with the standard drag-to-Applications layout.
echo "Creating DMG..."
rm -rf "${DMG_SRC}"
mkdir -p "${DMG_SRC}"
cp -R "${APP_PATH}" "${DMG_SRC}/"

CREATE_DMG_ARGS=(
  --volname "${APP_NAME}"
  --window-size 500 320
  --icon-size 100
  --icon "${APP_NAME}.app" 125 160
  --app-drop-link 375 160
  --background "${BACKGROUND_IMAGE}"
  --bless
)
if [ -n "${DEVELOPER_ID_APPLICATION:-}" ]; then
  CREATE_DMG_ARGS+=(--codesign "${DEVELOPER_ID_APPLICATION}")
  if [ -n "${NOTARY_PROFILE:-}" ]; then
    CREATE_DMG_ARGS+=(--notarize "${NOTARY_PROFILE}")
  fi
fi

rm -f "${DMG_PATH}"
create-dmg "${CREATE_DMG_ARGS[@]}" "${DMG_PATH}" "${DMG_SRC}"

echo "Build complete: ${DMG_PATH}"
