#!/bin/sh
# Headless run of the NowFocusCore logic checks. There is no XCTest target, so this compiles the
# dependency-free (Foundation-only) core files plus scripts/CoreChecks/main.swift and runs them.
# The GRDB-backed files stay out; keep new pure logic Foundation-only so it can be checked here.
set -e
cd "$(dirname "$0")/.."
OUT="${TMPDIR:-/tmp}/nowfocus-core-checks"
swiftc -D DEBUG -Onone \
  NowFocusCore/FocusSession.swift NowFocusCore/SessionEngine.swift NowFocusCore/SessionEvent.swift \
  NowFocusCore/HistoryStats.swift NowFocusCore/BedtimeSchedule.swift \
  NowFocusCore/People.swift NowFocusCore/Goals.swift NowFocusCore/VoiceNoteStore.swift NowFocusCore/FeedRules.swift NowFocusCore/FeedURLMatcher.swift NowFocusCore/CommitmentShield.swift NowFocusCore/BlockPolicy.swift NowFocusCore/DomainValidation.swift NowFocusCore/DNSSettings.swift \
  NowFocusCore/Countdown.swift NowFocusCore/Localization.swift NowFocusCore/BlockCopy.swift NowFocusCore/Sync/SyncModels.swift NowFocusCore/Sync/WireMapper.swift NowFocusCore/Sync/SyncLogic.swift \
  scripts/CoreChecks/main.swift -o "$OUT"
"$OUT"
