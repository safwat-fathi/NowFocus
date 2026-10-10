#!/bin/sh
# Live check of the sync client against a real services/api. Uses scratch databases only, never the app's own.
#
#   (cd services/api && DATABASE_URL=postgres://localhost:5432/nowfocus_test JWT_SECRET=<32+ chars> \
#      PORT=3996 TRUST_PROXY=0 node dist/main.js)
#   scripts/live-sync-check.sh http://127.0.0.1:3996
#
# Auth routes allow 10 requests a minute per IP, so a full run is two parts with a server restart between them:
#   LIVE_PART=1 scripts/live-sync-check.sh URL     (LIVE_IDLE=80 adds the idle-socket ping/pong check)
#   LIVE_PART=2 scripts/live-sync-check.sh URL
# NF_DB_COPY=/path/to/a-copy-of-NowFocus.sqlite also checks migration v5 on real-shaped data.
set -e
cd "$(dirname "$0")/.."
URL="${1:?usage: live-sync-check.sh <api base url>}"
DD="$(mktemp -d)"
trap 'rm -rf "$DD"' EXIT   # no stray NowFocus.app left behind

# A Debug framework (testability on) so the script can `@testable import` the internal sync types.
xcodebuild -project NowFocus.xcodeproj -scheme NowFocus -configuration Debug -derivedDataPath "$DD" -quiet build \
  CODE_SIGN_IDENTITY="-" CODE_SIGNING_REQUIRED=NO CODE_SIGNING_ALLOWED=NO > "$DD/build.log" 2>&1 || { tail -20 "$DD/build.log"; exit 1; }
P="$DD/Build/Products/Debug"
swiftc -F "$P" -I "$P" -Xcc -fmodule-map-file="$DD/SourcePackages/checkouts/GRDB.swift/Sources/CSQLite/module.modulemap" \
  -Xlinker -rpath -Xlinker "$P" -framework NowFocusCore -o "$DD/live-sync" scripts/LiveSync/main.swift

code=0
SYNC_IT_URL="$URL" SYNC_IT_DATABASE_URL="${SYNC_IT_DATABASE_URL:?set SYNC_IT_DATABASE_URL (the scratch DB behind URL)}" perl -e 'alarm 300; exec @ARGV' "$DD/live-sync" > "$DD/out.txt" 2>&1 || code=$?
grep -E '^(PASS|FAIL|==|DONE|   \()' "$DD/out.txt" || true
exit $code
