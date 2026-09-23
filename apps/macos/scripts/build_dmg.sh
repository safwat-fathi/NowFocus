#!/bin/bash
set -e

# Build script for NowFocus (macOS)
# This script compiles the Xcode project, signs it, and generates a DMG.

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="${PROJECT_DIR}/build"
APP_NAME="NowFocus"
ARCHIVE_PATH="${BUILD_DIR}/${APP_NAME}.xcarchive"
EXPORT_OPTIONS="${PROJECT_DIR}/scripts/ExportOptions.plist"
DMG_PATH="${BUILD_DIR}/${APP_NAME}.dmg"

echo "Building ${APP_NAME}..."

# Clean build directory
rm -rf "${BUILD_DIR}"
mkdir -p "${BUILD_DIR}"

# 1. Archive the project
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
DAEMON_DIR="${BUILD_DIR}/NowFocus.app/Contents/Library/LaunchDaemons"
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

# 3. Create DMG
echo "Creating DMG..."
hdiutil create -volname "${APP_NAME}" -srcfolder "${BUILD_DIR}/NowFocus.app" -ov -format UDZO "${DMG_PATH}"

echo "Build complete: ${DMG_PATH}"
