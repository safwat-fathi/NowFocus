# Graph Report - apps  (2026-10-07)

## Corpus Check
- 347 files · ~236,312 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 4306 nodes · 10310 edges · 209 communities (184 shown, 25 thin omitted)
- Extraction: 92% EXTRACTED · 8% INFERRED · 0% AMBIGUOUS · INFERRED: 812 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- Community 0
- Community 1
- Community 2
- Community 3
- Community 4
- Community 5
- Community 6
- Community 7
- Community 8
- Community 9
- Community 10
- Community 11
- Community 12
- Community 13
- Community 14
- Community 15
- Community 16
- Community 17
- Community 18
- Community 19
- Community 20
- Community 21
- Community 22
- Community 23
- Community 24
- Community 25
- Community 26
- Community 27
- Community 28
- Community 29
- Community 30
- Community 31
- Community 32
- Community 33
- Community 34
- Community 35
- Community 36
- Community 37
- Community 38
- Community 39
- Community 40
- Community 41
- Community 42
- Community 43
- Community 44
- Community 45
- Community 46
- Community 47
- Community 48
- Community 49
- Community 50
- Community 51
- Community 52
- Community 53
- Community 54
- Community 55
- Community 56
- Community 57
- Community 58
- Community 59
- Community 60
- Community 61
- Community 62
- Community 63
- Community 64
- Community 65
- Community 66
- Community 67
- Community 68
- Community 69
- Community 70
- Community 71
- Community 72
- Community 73
- Community 74
- Community 75
- Community 76
- Community 77
- Community 78
- Community 79
- Community 80
- Community 81
- Community 82
- Community 83
- Community 84
- Community 85
- Community 86
- Community 87
- Community 88
- Community 89
- Community 90
- Community 91
- Community 92
- Community 93
- Community 94
- Community 95
- Community 96
- Community 97
- Community 98
- Community 99
- Community 100
- Community 101
- Community 102
- Community 103
- Community 104
- Community 105
- Community 106
- Community 107
- Community 108
- Community 109
- Community 110
- Community 111
- Community 112
- Community 113
- Community 114
- Community 115
- Community 116
- Community 117
- Community 118
- Community 119
- Community 120
- Community 121
- Community 122
- Community 123
- Community 124
- Community 125
- Community 126
- Community 127
- Community 128
- Community 129
- Community 130
- Community 131
- Community 132
- Community 133
- Community 134
- Community 135
- Community 136
- Community 137
- Community 138
- Community 139
- Community 140
- Community 141
- Community 142
- Community 143
- Community 144
- Community 145
- Community 146
- Community 147
- Community 148
- Community 149
- Community 150
- Community 151
- Community 152
- Community 153
- Community 154
- Community 155
- Community 156
- Community 157
- Community 158
- Community 159
- Community 160
- Community 161
- Community 162
- Community 163
- Community 164
- Community 165
- Community 166
- Community 167
- Community 168
- Community 169
- Community 170
- Community 171
- Community 172
- Community 173
- Community 174
- Community 175
- Community 176
- Community 177
- Community 178
- Community 179
- Community 180
- Community 181
- Community 182
- Community 183
- Community 184
- Community 185
- Community 186
- Community 187
- Community 188
- Community 189
- Community 190
- Community 191
- Community 192
- Community 193
- Community 195
- Community 196
- Community 197
- Community 198
- Community 199

## God Nodes (most connected - your core abstractions)
1. `AppState` - 88 edges
2. `SessionRepository` - 60 edges
3. `SessionViewModel` - 53 edges
4. `AppStateDto` - 51 edges
5. `AppModel` - 47 edges
6. `NowFocusRule` - 47 edges
7. `SyncLogicTest` - 43 edges
8. `headingStyle()` - 41 edges
9. `Database` - 41 edges
10. `GhostButton()` - 37 edges

## Surprising Connections (you probably didn't know these)
- `AppModel` --calls--> `SessionEngine`  [INFERRED]
  ios/NowFocusIOS/AppModel.swift → macos/NowFocusCore/SessionEngine.swift
- `.body` --calls--> `NowFocusRule`  [INFERRED]
  ios/NowFocusIOS/UI/Components.swift → macos/NowFocus/NowFocusTokens.swift
- `.body` --calls--> `NowFocusRule`  [INFERRED]
  ios/NowFocusIOS/UI/Components.swift → macos/NowFocus/NowFocusTokens.swift
- `.body` --calls--> `NowFocusPrimaryButton`  [INFERRED]
  ios/NowFocusIOS/UI/FocusViews.swift → macos/NowFocus/NowFocusTokens.swift
- `.body` --calls--> `NowFocusRule`  [INFERRED]
  ios/NowFocusIOS/UI/FocusViews.swift → macos/NowFocus/NowFocusTokens.swift

## Import Cycles
- None detected.

## Communities (209 total, 25 thin omitted)

### Community 0 - "Community 0"
Cohesion: 0.05
Nodes (82): A, Fn, From, H, Pc, Stored, a_410_on_pull_restarts_from_cursor_zero_and_reconciles(), a_mode_this_build_does_not_know_is_kept_but_never_imported() (+74 more)

### Community 1 - "Community 1"
Cohesion: 0.08
Nodes (80): SharedState, add_application(), add_domain(), begin_unlock(), blocking(), cancel_cheat_day(), cancel_unlock(), changed() (+72 more)

### Community 2 - "Community 2"
Cohesion: 0.06
Nodes (73): Contacts, ContactsUI, BodyText, .body, BottomTabBar, .body, Chip, .body (+65 more)

### Community 3 - "Community 3"
Cohesion: 0.08
Nodes (19): Applied, BedtimeSettings, BlockPolicy, Meta, SyncState, Local, Outgoing, PushOutcome (+11 more)

### Community 4 - "Community 4"
Cohesion: 0.06
Nodes (13): AppUrge, BlockEventRow, HistoryConverters, HistoryDatabase, HistoryStats, Context, FocusSessionStatus, SessionType (+5 more)

### Community 5 - "Community 5"
Cohesion: 0.05
Nodes (52): c_void, HWINEVENTHOOK, HWND, NamedPipeClient, NamedPipeServer, Receiver, policy_json_containing_special_characters_survives_framing(), read_message() (+44 more)

### Community 6 - "Community 6"
Cohesion: 0.07
Nodes (23): LeaveGate, LeaveStep, BACK, HOME, WAIT, MatchLogGate, NodeSnapshot, PartialAction (+15 more)

### Community 7 - "Community 7"
Cohesion: 0.07
Nodes (28): AccountSession, ApiException, AuthExpired, AuthStore, await(), Callback, DeviceInfo, JSONObject (+20 more)

### Community 8 - "Community 8"
Cohesion: 0.07
Nodes (57): Applied, apply_bedtime_record(), apply_policy_record(), apply_pulled(), apply_push_results(), bedtime_dirty(), fingerprint(), finish_initial_pull() (+49 more)

### Community 9 - "Community 9"
Cohesion: 0.07
Nodes (14): SiteLimits, arr(), copy(), JSONArray, JSONObject, obj(), objects(), AppLimit (+6 more)

### Community 10 - "Community 10"
Cohesion: 0.08
Nodes (25): DatabaseMigrator, DatabaseQueue, ReachOut, NowFocusRule, .body, PeopleEditorView, .addRow, .body (+17 more)

### Community 11 - "Community 11"
Cohesion: 0.08
Nodes (25): AppModel, .bedtime, .running, .shieldLive, BedtimeSettings, BlockPolicy, Bool, CommitmentShield (+17 more)

### Community 12 - "Community 12"
Cohesion: 0.08
Nodes (24): DaemonClient, BlockPolicy, Bool, Error, NSXPCConnection, String, TimeInterval, Void (+16 more)

### Community 13 - "Community 13"
Cohesion: 0.09
Nodes (27): a_subdomain_counts_toward_the_parent_and_the_longest_wins(), at(), credit_ms(), DailyLimit, day_key(), ig(), limit_for_exe(), limit_for_host() (+19 more)

### Community 14 - "Community 14"
Cohesion: 0.13
Nodes (47): a_session_cancelled_or_extended_elsewhere_changes_the_same_session_here(), a_started_session_goes_out_with_a_snapshot_and_a_joined_one_keeps_the_other_devices_extras(), bedtime_and_schedule_sessions_never_leave_the_device(), bookkeeping_survives_json_and_keeps_only_what_matters(), decide(), Decision, feed_names(), fingerprint() (+39 more)

### Community 15 - "Community 15"
Cohesion: 0.09
Nodes (29): EnforcementStatus, EnforcementFix, MenuBarView, .body, .enforcementFix, .enforcementTagText, .footer, .idleSession (+21 more)

### Community 16 - "Community 16"
Cohesion: 0.08
Nodes (33): Font, ArabicSafeTracking, Color, NowFocusColors, NowFocusFonts, .body, .body, NowFocusSecondaryButton (+25 more)

### Community 17 - "Community 17"
Cohesion: 0.09
Nodes (37): a_blocklist_is_never_decided_here(), a_lookalike_outside_the_windows_directory_is_not_exempt(), always_allowed(), an_allowlist_closes_what_it_does_not_list_and_keeps_what_it_does(), an_allowlist_with_no_windows_app_enforces_nothing(), blocked_domains(), closes(), display_name() (+29 more)

### Community 18 - "Community 18"
Cohesion: 0.12
Nodes (43): a_cheat_day_runs_midnight_to_midnight(), at(), can_schedule(), cancel(), cancelling_before_it_starts_frees_the_week_ending_a_live_one_keeps_it_counted(), CheatDay, date_of(), day_start() (+35 more)

### Community 19 - "Community 19"
Cohesion: 0.12
Nodes (12): a_pull_never_changes_or_deletes_the_profile_a_running_session_uses(), AppState, own_exe(), parse_block_target(), FocusSession, HashSet, Local, Option (+4 more)

### Community 20 - "Community 20"
Cohesion: 0.12
Nodes (25): Agent, Display, Formatter, AccountSession, Api, ApiError, ApiPort, DeviceInfo (+17 more)

### Community 21 - "Community 21"
Cohesion: 0.07
Nodes (16): currentBootCount(), android, BlockPolicy, CheatDay, CommitmentShield, EnforcementMode, FocusSession, GoalPriority (+8 more)

### Community 22 - "Community 22"
Cohesion: 0.11
Nodes (40): CommitmentState, apply(), boot_id(), clear_if_in_grace(), CommitmentStore, expire_if_needed(), lock_down_permissions(), now_uptime_secs() (+32 more)

### Community 23 - "Community 23"
Cohesion: 0.09
Nodes (9): AppPass, FocusSession, LimitPasses, LimitPassState, Passes, LimitPassesTest, EnforcementMode, SessionType (+1 more)

### Community 24 - "Community 24"
Cohesion: 0.13
Nodes (20): Error, AccountSession, ApiError, AuthExpired, DeviceInfo, NetworkError, PullPage, AuthStore (+12 more)

### Community 25 - "Community 25"
Cohesion: 0.13
Nodes (24): Equatable, MetaEdit, keep, remove, set, BedtimeSettings, BlockPolicy, Bool (+16 more)

### Community 26 - "Community 26"
Cohesion: 0.08
Nodes (13): Keys, BedtimeSettings, BlockPolicy, CheatDay, CommitmentShield, Flow, FocusSession, R (+5 more)

### Community 27 - "Community 27"
Cohesion: 0.20
Nodes (34): AboutScreen(), LockedAccount(), GoalsEditor(), GoalsScreen(), DomainDialog(), FrictionAppsScreen(), LimitsScreen(), MinutesDialog() (+26 more)

### Community 28 - "Community 28"
Cohesion: 0.05
Nodes (37): react, react-dom, @tauri-apps/api, @tauri-apps/cli, @tauri-apps/plugin-dialog, @tauri-apps/plugin-opener, @tauri-apps/plugin-updater, @types/react (+29 more)

### Community 29 - "Community 29"
Cohesion: 0.10
Nodes (33): formatClock(), ActiveScreen(), App(), appLabelFor(), BedtimeScreen(), BottomTabBar(), CheatDay, CommitmentDetail() (+25 more)

### Community 30 - "Community 30"
Cohesion: 0.17
Nodes (23): AtomicUsize, a_cheat_day_that_starts_mid_session_lifts_enforcement_on_the_next_tick(), a_due_schedule_starts_a_session_once_and_not_again_after_it_is_ended(), a_pass_lets_a_blocked_app_stay_open_twice_per_session(), a_restart_mid_session_reapplies_it(), a_schedule_needs_a_name_a_day_and_a_real_profile(), a_session_started_on_a_cheat_day_runs_paused_and_applies_nothing(), all_day_schedule() (+15 more)

### Community 31 - "Community 31"
Cohesion: 0.12
Nodes (29): ChronoDuration, CommitmentStatusWire, a_joined_whitelist_session_needs_an_app_for_this_pc(), a_pass_reopens_an_app_a_whitelist_closed(), a_running_whitelist_can_shrink_but_not_grow_or_empty(), a_whitelist_never_closes_nowfocus_or_the_windows_directory(), a_whitelist_session_closes_unlisted_apps_and_keeps_listed_ones(), a_whitelist_with_no_app_cannot_start_and_closes_nothing() (+21 more)

### Community 32 - "Community 32"
Cohesion: 0.12
Nodes (19): String, .appVersion, .computerName, AsyncStream, AuthStore, Bool, Date, DeviceInfo (+11 more)

### Community 33 - "Community 33"
Cohesion: 0.12
Nodes (27): BlockEvent, cancelled_after(), completed_count(), completion_rate(), current_streak_days(), focus_score(), focus_score_is_none_for_an_empty_week_and_full_marks_for_a_perfect_one(), focused_minutes() (+19 more)

### Community 34 - "Community 34"
Cohesion: 0.14
Nodes (11): Database, profile_round_trips_with_feed_rules(), CheatDay, FeedRule, Option, Profile, Result, Schedule (+3 more)

### Community 35 - "Community 35"
Cohesion: 0.15
Nodes (22): TrayLabelsSync(), UpdateBanner(), LangPref, useI18n(), api, endNormalAfterAsking(), Updater, useUpdater() (+14 more)

### Community 36 - "Community 36"
Cohesion: 0.08
Nodes (24): DomainValidation, EnforcementMode, LOCKED, NORMAL, STRICT, FocusSession, FocusSessionStatus, ACTIVE (+16 more)

### Community 37 - "Community 37"
Cohesion: 0.11
Nodes (6): AppRule, BlockPolicy, PolicyMode, ALLOWLIST, BLOCKLIST, WireMapperTest

### Community 38 - "Community 38"
Cohesion: 0.06
Nodes (30): https://api.nowfocus.online/v1/updates/windows/{{current_version}}, icons/128x128@2x.png, icons/128x128.png, icons/32x32.png, icons/icon.icns, icons/icon.ico, app, security (+22 more)

### Community 39 - "Community 39"
Cohesion: 0.15
Nodes (4): ActiveRules, BlockReason, RuleWindow, ActiveRulesTest

### Community 40 - "Community 40"
Cohesion: 0.10
Nodes (21): T, canStart(), profileSummary(), Bedtime(), minutesToTime(), timeToMinutes(), Duration, DURATIONS (+13 more)

### Community 41 - "Community 41"
Cohesion: 0.16
Nodes (15): ISO8601DateFormatter, Outgoing, PushOutcome, JSONKit, Outgoing, PushOutcome, ServerRecord, Any (+7 more)

### Community 42 - "Community 42"
Cohesion: 0.12
Nodes (10): AppKit, AVFoundation, Cocoa, CoreText, AppSync, NowFocusCore, ServiceManagement, Sparkle (+2 more)

### Community 43 - "Community 43"
Cohesion: 0.12
Nodes (26): attempt(), check(), date(), goal(), J(), linked(), person(), pol() (+18 more)

### Community 44 - "Community 44"
Cohesion: 0.13
Nodes (19): App(), ar, en, Key, Message, applyDocument(), Ctx, dictionaries (+11 more)

### Community 45 - "Community 45"
Cohesion: 0.18
Nodes (12): AppHost, DateTime, FnMut, FocusSession, Local, Option, Profile, R (+4 more)

### Community 46 - "Community 46"
Cohesion: 0.14
Nodes (13): BlockedActivity, Context, Intent, Modifier, ReachButton(), ReachOutCard(), ShieldScreen(), SideRow() (+5 more)

### Community 47 - "Community 47"
Cohesion: 0.13
Nodes (24): NowFocusPrimaryButton, SettingsView, .about, .body, .language, .report, .sessionActive, .version (+16 more)

### Community 48 - "Community 48"
Cohesion: 0.15
Nodes (23): appsText(), loc(), locr(), sitesAppsSummary(), sitesText(), Int, LocalizedStringResource, String (+15 more)

### Community 49 - "Community 49"
Cohesion: 0.15
Nodes (19): dateToMinutes(), minutesToDate(), OnboardingView, .body, .brandRail, .goalsStep, .navButtons, .nextLabel (+11 more)

### Community 50 - "Community 50"
Cohesion: 0.19
Nodes (22): BedtimeBox, check(), checkAsync(), checkWait(), Phone, .bedtimeNow, runController(), runSync() (+14 more)

### Community 51 - "Community 51"
Cohesion: 0.10
Nodes (25): SessionNotificationMode, bedtime_settings_round_trip_and_default(), delete_profile_removes_its_rules_too(), device_id_is_generated_once_and_then_stable(), enforcement_mode_to_str(), mode_to_str(), notification_mode_to_str(), origin_to_str() (+17 more)

### Community 53 - "Community 53"
Cohesion: 0.28
Nodes (6): Device, FakeServer, PushOutcome, Rec, SyncEngineTest, Clock

### Community 54 - "Community 54"
Cohesion: 0.14
Nodes (13): AppBlocker, BlockReason, Bool, Date, Notification, SessionType, Set, String (+5 more)

### Community 55 - "Community 55"
Cohesion: 0.17
Nodes (11): BedtimeWire, PolicyWire, ApplicationRule, BedtimeSettings, BlockPolicy, Bool, Date, DomainRule (+3 more)

### Community 56 - "Community 56"
Cohesion: 0.18
Nodes (8): AccessibilityEvent, AccessibilityService, FocusAccessibilityService, CheatDay, Intent, Job, View, IntArray

### Community 57 - "Community 57"
Cohesion: 0.12
Nodes (11): AccountSession, AuthStore, BlockPolicy, StoredAuth, SyncEngine, LiveSyncTest, MemoryAuth, Phone (+3 more)

### Community 58 - "Community 58"
Cohesion: 0.16
Nodes (10): HistoryStats, Calendar, Date, Double, Int, String, Urge, SessionEvent (+2 more)

### Community 59 - "Community 59"
Cohesion: 0.20
Nodes (9): FocusVpnService, ConnectivityManager, ByteArray, Intent, Job, TunLoop, InetAddress, LinkProperties (+1 more)

### Community 60 - "Community 60"
Cohesion: 0.16
Nodes (9): FocusSession, Outgoing, PushOutcome, R, RemoteSession, ServerRecord, RepositorySessionSync, SessionSyncPort (+1 more)

### Community 61 - "Community 61"
Cohesion: 0.18
Nodes (5): FocusSessionStatus, ServerRecord, SessionOrigin, SessionType, SessionWireTest

### Community 62 - "Community 62"
Cohesion: 0.09
Nodes (22): RFC-3339, DAY_LETTERS, Stats(), copyWeek(), weekText(), ApplicationRule, CheatDay, Commitment (+14 more)

### Community 63 - "Community 63"
Cohesion: 0.12
Nodes (21): .displayName, EnforcementMode, locked, normal, strict, FocusSession, NotificationMode, normal (+13 more)

### Community 64 - "Community 64"
Cohesion: 0.10
Nodes (17): CloseIcon(), MaximizeIcon(), MinimizeIcon(), Sidebar(), TitleBar(), win, tOr(), useT() (+9 more)

### Community 65 - "Community 65"
Cohesion: 0.12
Nodes (18): UnlockDialog(), confirm(), startWait(), errorText(), Account(), deleteAccount(), submit(), ago() (+10 more)

### Community 66 - "Community 66"
Cohesion: 0.18
Nodes (7): BedtimeWire, BedtimeSettings, BlockPolicy, JSONArray, JSONObject, PolicyMode, PolicyWire

### Community 67 - "Community 67"
Cohesion: 0.16
Nodes (21): AppState, lock_workstation(), make_enforcer(), periodic_tick(), refresh_tray(), App, AppHandle, Box (+13 more)

### Community 68 - "Community 68"
Cohesion: 0.14
Nodes (12): AVAudioPlayerDelegate, AVAudioRecorder, AVAudioRecorderDelegate, PermissionStatus, denied, granted, unknown, AVAudioPlayer (+4 more)

### Community 69 - "Community 69"
Cohesion: 0.21
Nodes (21): at(), BedtimeSettings, current_window(), disabled_has_no_current_window(), dt(), in_window_before_and_after_midnight(), is_at_sleep_moment(), Default (+13 more)

### Community 70 - "Community 70"
Cohesion: 0.12
Nodes (17): EnforcementStatus, PlatformCapabilities, EnforcementMode, FocusSession, FocusSessionStatus, NotificationMode, DateTime, Into (+9 more)

### Community 71 - "Community 71"
Cohesion: 0.19
Nodes (4): remove_application_rejected_during_active_session(), remove_domain_rejected_during_active_session(), Profile, Result

### Community 72 - "Community 72"
Cohesion: 0.20
Nodes (13): AlarmManager, BedtimeAlarmReceiver, BedtimeScheduler, BedtimeSettings, BroadcastReceiver, Context, Intent, reconcileBedtimeSession() (+5 more)

### Community 73 - "Community 73"
Cohesion: 0.19
Nodes (19): CheatDayScreen(), CheatDay, SetupScreen(), BlockPolicy, Schedule, ScheduleEditor(), SchedulesScreen(), archivoWeight() (+11 more)

### Community 74 - "Community 74"
Cohesion: 0.14
Nodes (13): Defer, EndLocal, Extend, Ignore, Join, BlockPolicy, FocusSession, FocusSessionStatus (+5 more)

### Community 75 - "Community 75"
Cohesion: 0.10
Nodes (9): CryptoKit, Foundation, GRDB, DomainValidation, String, FeedRules, Notification.Name, Observation (+1 more)

### Community 76 - "Community 76"
Cohesion: 0.20
Nodes (16): Identifiable, ApplicationRule, BlockPolicy, .isUntouchedSeed, DomainRule, PolicyMode, allowlist, blocklist (+8 more)

### Community 77 - "Community 77"
Cohesion: 0.19
Nodes (16): .body, NowFocusGhostButton, Void, AccountView, .body, .canSubmit, .deleteForm, .signedIn (+8 more)

### Community 78 - "Community 78"
Cohesion: 0.16
Nodes (14): Connection, SocketEvent, changes, closed, revoked, AsyncStream, Data, Int (+6 more)

### Community 79 - "Community 79"
Cohesion: 0.09
Nodes (21): DOM, ES2020, src, compilerOptions, allowImportingTsExtensions, isolatedModules, jsx, lib (+13 more)

### Community 80 - "Community 80"
Cohesion: 0.19
Nodes (16): a_live_commitment_cannot_be_replaced_even_during_grace(), a_reboot_refuses_cancel_even_within_grace_seconds(), across_a_reboot_it_falls_back_to_wall_clock_end_at(), can_replace(), cancellable_only_inside_the_grace_window(), CommitmentState, expires_by_uptime_when_boot_matches(), fresh() (+8 more)

### Community 81 - "Community 81"
Cohesion: 0.20
Nodes (11): FetchableRecord, Bool, Int, Int64, String, SyncState, SyncRecordRow, .meta (+3 more)

### Community 82 - "Community 82"
Cohesion: 0.20
Nodes (12): IndexSet, PolicyListRowView, .body, PolicyListView, .body, .detail, .list, BlockPolicy (+4 more)

### Community 83 - "Community 83"
Cohesion: 0.16
Nodes (16): BedtimeView, .body, .enabledBinding, .lockAtSleepBinding, .policyBinding, BedtimeSettings, Binding, BlockPolicy (+8 more)

### Community 84 - "Community 84"
Cohesion: 0.10
Nodes (20): About, Account, Active, Bedtime, Commitment, Devices, EditPolicy, Friction (+12 more)

### Community 85 - "Community 85"
Cohesion: 0.21
Nodes (5): Job, R, StateFlow, SyncController, SyncStatus

### Community 86 - "Community 86"
Cohesion: 0.12
Nodes (14): byPath, canon, decode(), descs, errors, fail(), nodesOf(), norm() (+6 more)

### Community 87 - "Community 87"
Cohesion: 0.22
Nodes (7): Context, FocusSession, VoiceNote, VoiceNotePlayer, VoiceRecorder, MediaPlayer, MediaRecorder

### Community 88 - "Community 88"
Cohesion: 0.11
Nodes (16): CaseIterable, LayoutDirection, AppLanguage, .choice, .isArabic, .layoutDirection, .locale, AppLanguageChoice (+8 more)

### Community 89 - "Community 89"
Cohesion: 0.16
Nodes (10): BedtimeLockAvailability, BedtimeScheduler, Bool, Date, Int, String, Timer, ReachKind (+2 more)

### Community 90 - "Community 90"
Cohesion: 0.20
Nodes (8): FocusSession, AppDelegate, Notification, Timer, SessionEngine, .strictUnlockSentence, NSApplication, NSApplicationDelegate

### Community 91 - "Community 91"
Cohesion: 0.20
Nodes (9): DaemonXPCDelegate, Bool, Data, Error, NSXPCConnection, String, Void, NSXPCListener (+1 more)

### Community 92 - "Community 92"
Cohesion: 0.11
Nodes (18): core:menu:default, core:tray:default, core:window:allow-hide, core:window:allow-is-maximized, core:window:allow-minimize, core:window:allow-show, core:window:allow-start-dragging, core:window:allow-toggle-maximize (+10 more)

### Community 93 - "Community 93"
Cohesion: 0.21
Nodes (11): Sender, SyncStatusDto, ApiHandle, nudge(), AppHandle, Arc, FnOnce, HashSet (+3 more)

### Community 94 - "Community 94"
Cohesion: 0.25
Nodes (13): ArrowRightIcon(), base(), BedtimeIcon(), CheckIcon(), CommitmentIcon(), DevicesIcon(), FocusIcon(), GlobeIcon() (+5 more)

### Community 95 - "Community 95"
Cohesion: 0.21
Nodes (8): BedtimeSchedule, BedtimeSettings, parseClock(), QuietDecision, NONE, RESTORE_ALL, SET_PRIORITY, LongRange

### Community 98 - "Community 98"
Cohesion: 0.21
Nodes (9): CheckedContinuation, SyncAPIPort, AsyncLock, Int64, Never, SyncReport, Void, SyncEngine (+1 more)

### Community 100 - "Community 100"
Cohesion: 0.23
Nodes (6): SyncSocket, AuthStore, StoredAuth, LiveControllerTest, MemoryAuth, Phone

### Community 101 - "Community 101"
Cohesion: 0.20
Nodes (6): DataStoreAuthStore, KeystoreSecretBox, AuthStore, StoredAuth, SecretBox, SecretKey

### Community 102 - "Community 102"
Cohesion: 0.21
Nodes (10): CNContactPickerDelegate, CNContactPickerViewController, CNContactProperty, ContactPicker, Coordinator, Context, String, Void (+2 more)

### Community 103 - "Community 103"
Cohesion: 0.22
Nodes (8): GoalPriority, GoalsEditorView, .body, GoalsView, .body, GoalPriority, .detail, UserGoal

### Community 104 - "Community 104"
Cohesion: 0.15
Nodes (13): DevicesView, .body, .daemonLayerState, .overallDegraded, LayerState, checking, idle, .label (+5 more)

### Community 105 - "Community 105"
Cohesion: 0.14
Nodes (10): SessionStopGate, immediate, locked, requiresUnlock, Bool, Date, EnforcementMode, FocusSession (+2 more)

### Community 106 - "Community 106"
Cohesion: 0.30
Nodes (4): NetworkEnforcer, BlockPolicy, DomainRule, String

### Community 107 - "Community 107"
Cohesion: 0.21
Nodes (12): a_cheat_day_can_only_be_planned_a_day_ahead(), a_cheat_day_pauses_limits(), a_limited_app_is_closed_once_its_time_is_used_up_and_not_before(), a_simulated_block_has_nothing_to_pass(), add_domain_reapplies_during_active_session(), begin_unlock_clears_shield(), finish_session_clears_shield(), loosening_a_limit_waits_for_midnight_and_removing_it_does_too() (+4 more)

### Community 108 - "Community 108"
Cohesion: 0.18
Nodes (11): ActivityAttributes, ActivityKit, Hashable, LiveActivityController, Date, FocusSession, String, ContentState (+3 more)

### Community 109 - "Community 109"
Cohesion: 0.24
Nodes (9): Chip(), choiceFor(), Context, PeopleEditor(), PeopleScreen(), readPickedPhone(), PeopleRotation, Person (+1 more)

### Community 110 - "Community 110"
Cohesion: 0.27
Nodes (8): Decoder, BedtimeSchedule, BedtimeSettings, Bool, Calendar, Date, Int, String

### Community 111 - "Community 111"
Cohesion: 0.14
Nodes (14): Screen, active, bedtime, commitment, devices, editPolicy, goals, home (+6 more)

### Community 112 - "Community 112"
Cohesion: 0.21
Nodes (12): BlockedSet, .isEmpty, EnforcementLayer, .id, Enforcer, NoopEnforcer, .layers, Bool (+4 more)

### Community 113 - "Community 113"
Cohesion: 0.18
Nodes (9): BlockPolicy, BlockPolicyRecord, BlockPolicy, Data, Date, Int, NotificationMode, PolicyMode (+1 more)

### Community 114 - "Community 114"
Cohesion: 0.21
Nodes (9): AuthStore, KeychainAuthStore, .query, KeychainError, StoredAuth, Any, String, OSStatus (+1 more)

### Community 115 - "Community 115"
Cohesion: 0.25
Nodes (10): clear(), CredentialAuth, entry(), load(), AuthStore, Option, Result, StoredAuth (+2 more)

### Community 116 - "Community 116"
Cohesion: 0.35
Nodes (5): BackgroundSync, BackgroundSyncReceiver, BroadcastReceiver, Context, Intent

### Community 117 - "Community 117"
Cohesion: 0.21
Nodes (9): SessionController, SessionStatus, .remainingText, BlockPolicy, Bool, Date, SessionType, String (+1 more)

### Community 118 - "Community 118"
Cohesion: 0.29
Nodes (11): active_session_past_end_at_becomes_completed(), active_session_within_window_stays_active(), can_quit(), evaluate_state(), is_active(), DateTime, FocusSession, Option (+3 more)

### Community 119 - "Community 119"
Cohesion: 0.23
Nodes (6): accountLocked(), askFingerprint(), BiometricPrompt, fingerprintAvailable(), Context, AccountLockTest

### Community 120 - "Community 120"
Cohesion: 0.21
Nodes (7): BlockCopy, BlockReason, ALLOWLIST_SESSION, BEDTIME, COMMITMENT_SHIELD, DAILY_LIMIT, FOCUS_SESSION

### Community 121 - "Community 121"
Cohesion: 0.41
Nodes (3): DnsPacket, ByteArray, Query

### Community 122 - "Community 122"
Cohesion: 0.29
Nodes (3): Enforcement, Context, Flow

### Community 123 - "Community 123"
Cohesion: 0.30
Nodes (3): Occurrence, Schedule, Schedules

### Community 126 - "Community 126"
Cohesion: 0.21
Nodes (11): App, MenuBarIcon, .body, NowFocusApp, .body, Scene, MainWindowView, .body (+3 more)

### Community 127 - "Community 127"
Cohesion: 0.23
Nodes (11): HDC, HMONITOR, LPARAM, RECT, hide_shield(), monitor_enum_proc(), monitor_work_areas(), AppHandle (+3 more)

### Community 128 - "Community 128"
Cohesion: 0.21
Nodes (11): Countdown, .body, NowFocusLiveActivityBundle, .body, SessionLiveActivity, .body, Date, Widget (+3 more)

### Community 129 - "Community 129"
Cohesion: 0.27
Nodes (5): AVAudioPlayer, Bool, Error, URL, VoiceNotePlayer

### Community 130 - "Community 130"
Cohesion: 0.23
Nodes (8): BlockCopy, BlockReason, bedtime, commitmentShield, dailyLimit, focusSession, Int, String

### Community 131 - "Community 131"
Cohesion: 0.41
Nodes (6): CommitmentShield, ApplicationRule, Bool, Date, String, TimeInterval

### Community 132 - "Community 132"
Cohesion: 0.23
Nodes (8): add_column_if_missing(), an_older_database_without_the_origin_column_is_upgraded_in_place(), cheat_day_round_trips_and_clears(), kv_round_trips_and_deletes(), limit_usage_adds_within_a_day_and_restarts_on_the_next(), limits_round_trip_and_delete_with_their_usage(), Path, Self

### Community 133 - "Community 133"
Cohesion: 0.27
Nodes (9): cheat_to_dto(), local_midnight_utc(), local_naive_to_utc(), CheatDay, DateTime, NaiveDate, NaiveDateTime, Utc (+1 more)

### Community 134 - "Community 134"
Cohesion: 0.27
Nodes (7): AppLanguage, AR, EN, SYSTEM, Context, locale(), localized()

### Community 135 - "Community 135"
Cohesion: 0.20
Nodes (7): DurationFormat, shortName(), Joined, Plural, Raw, Res, UiText

### Community 136 - "Community 136"
Cohesion: 0.22
Nodes (4): BlockSource, COMMITMENT_SHIELD, SESSION, BlockCopyTest

### Community 137 - "Community 137"
Cohesion: 0.35
Nodes (8): BroadcastReceiver, Context, Intent, Schedule, reconcileScheduledSessions(), ScheduleAlarmReceiver, ScheduleScheduler, startDueSchedule()

### Community 139 - "Community 139"
Cohesion: 0.18
Nodes (8): G, GoalPriority, high, .label, low, medium, Goals, Date

### Community 140 - "Community 140"
Cohesion: 0.27
Nodes (9): BlockOverlayContent, BlockOverlayView, .body, BlockReason, Date, Int, String, Void (+1 more)

### Community 141 - "Community 141"
Cohesion: 0.18
Nodes (11): Section, bedtime, commitment, devices, goals, .id, people, profiles (+3 more)

### Community 142 - "Community 142"
Cohesion: 0.29
Nodes (7): PolicyDetailView, .body, BlockPolicy, Bool, LocalizedStringResource, String, Void

### Community 143 - "Community 143"
Cohesion: 0.25
Nodes (7): DatabaseSyncStore, BedtimeSettings, BlockPolicy, Data, Set, Void, SyncStore

### Community 144 - "Community 144"
Cohesion: 0.38
Nodes (10): OsString, ServiceStatusHandle, main(), report_running(), report_stopped(), Result, Vec, run() (+2 more)

### Community 145 - "Community 145"
Cohesion: 0.18
Nodes (10): binaries/now-focus-service, nsis, bundle, externalBin, targets, windows, installerHooks, installMode (+2 more)

### Community 146 - "Community 146"
Cohesion: 0.27
Nodes (8): DevicesScreen(), DotIndicator(), Modifier, SyncStatus, LayerCell(), NowFocusSpace, Dp, VpnService

### Community 148 - "Community 148"
Cohesion: 0.22
Nodes (6): Goal, GoalPriority, HIGH, LOW, MEDIUM, Goals

### Community 149 - "Community 149"
Cohesion: 0.24
Nodes (8): sitesAppsText(), BedtimeSettings, BlockPolicy, CommitmentShield, PolicyListScreen(), profileSummary(), ProtectionEntry, items()

### Community 150 - "Community 150"
Cohesion: 0.29
Nodes (5): BedtimeSettings, R, SyncEngine, SyncReport, SyncStore

### Community 151 - "Community 151"
Cohesion: 0.24
Nodes (8): Codable, EnforcementStatus, active, degraded, unavailable, unknown, PlatformCapabilities, Bool

### Community 152 - "Community 152"
Cohesion: 0.29
Nodes (6): Bool, FocusSession, Notification, UnlockWindowController, NSWindow, NSWindowDelegate

### Community 153 - "Community 153"
Cohesion: 0.24
Nodes (4): Notification, R, SyncSignals, AccountSession

### Community 154 - "Community 154"
Cohesion: 0.38
Nodes (4): parse_time(), DateTime, FocusSession, Utc

### Community 155 - "Community 155"
Cohesion: 0.29
Nodes (6): focus_sessions_between_excludes_bedtime(), policy_round_trips_with_its_rules(), BlockPolicy, session_origin_round_trips_and_an_extension_is_kept(), sessions_since_and_event_counts_are_real_numbers(), the_full_loop_start_persist_recover_expire()

### Community 160 - "Community 160"
Cohesion: 0.25
Nodes (7): DaemonRegistrationStatus, State, failed, registered, requiresApproval, unknown, String

### Community 161 - "Community 161"
Cohesion: 0.22
Nodes (8): core:window:allow-close, shield-overlay-*, description, identifier, core:default, permissions, $schema, windows

### Community 162 - "Community 162"
Cohesion: 0.22
Nodes (8): vite.config.ts, compilerOptions, allowSyntheticDefaultImports, composite, module, moduleResolution, skipLibCheck, include

### Community 163 - "Community 163"
Cohesion: 0.54
Nodes (7): AccountScreen(), ago(), DeleteAccountDialog(), SyncController, SyncStatus, SignedIn(), SignedOut()

### Community 164 - "Community 164"
Cohesion: 0.57
Nodes (7): appLocale(), format(), formatDay(), formatDayTime(), formatTime(), Context, timePattern()

### Community 166 - "Community 166"
Cohesion: 0.46
Nodes (3): BedtimeSettingsStore, .settings, BedtimeSettings

### Community 167 - "Community 167"
Cohesion: 0.46
Nodes (6): a_session_clear_leaves_a_live_commitment_intact(), empty_body_removes_the_region_without_empty_markers(), reapplying_a_region_does_not_stack_markers(), remove_region(), String, with_region()

### Community 168 - "Community 168"
Cohesion: 0.25
Nodes (5): ApiError, DeviceInfo, Outgoing, PushOutcome, Vec

### Community 169 - "Community 169"
Cohesion: 0.33
Nodes (3): EnforcementMode, FocusSession, SessionEngine

### Community 170 - "Community 170"
Cohesion: 0.29
Nodes (7): FocusSessionStatus, active, cancelled, completed, error, expired, scheduled

### Community 171 - "Community 171"
Cohesion: 0.29
Nodes (6): EventLog, .closed, .cursor, .sawRevoked, Sendable, SocketEvent

### Community 172 - "Community 172"
Cohesion: 0.53
Nodes (4): BootReceiver, BroadcastReceiver, Context, Intent

### Community 173 - "Community 173"
Cohesion: 0.60
Nodes (4): Context, resolve(), resolveArgs(), text()

### Community 174 - "Community 174"
Cohesion: 0.47
Nodes (3): AuthStore, MemoryAuth, StoredAuth

### Community 175 - "Community 175"
Cohesion: 0.33
Nodes (4): Combine, URL, SyncConfig, .baseURL

### Community 176 - "Community 176"
Cohesion: 0.33
Nodes (5): FirstMouseHostingView, Bool, Content, NSEvent, NSHostingView

### Community 177 - "Community 177"
Cohesion: 0.33
Nodes (5): Sync, AuthStore, Box, Self, Send

### Community 179 - "Community 179"
Cohesion: 0.50
Nodes (3): BlockNotifier, BlockReason, Context

### Community 181 - "Community 181"
Cohesion: 0.40
Nodes (3): Outgoing, SyncApiPort, SyncApiPort

### Community 182 - "Community 182"
Cohesion: 0.40
Nodes (5): RecorderState, idle, playing, recorded, recording

### Community 183 - "Community 183"
Cohesion: 0.40
Nodes (4): BlockReason, Date, String, NSRect

### Community 184 - "Community 184"
Cohesion: 0.40
Nodes (3): Countdown, String, TimeInterval

### Community 185 - "Community 185"
Cohesion: 0.60
Nodes (5): now-focus-core, now-focus-ipc, now-focus-service, now-focus-sync, NowFocus

### Community 190 - "Community 190"
Cohesion: 0.83
Nodes (3): gradlew script, die(), warn()

### Community 191 - "Community 191"
Cohesion: 0.67
Nodes (3): check_and_start(), RegistrationState, String

### Community 193 - "Community 193"
Cohesion: 0.67
Nodes (3): ParseError, PersistenceError, Error

## Knowledge Gaps
- **353 isolated node(s):** `SYSTEM`, `EN`, `AR`, `SET_PRIORITY`, `RESTORE_ALL` (+348 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **25 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Database` connect `Community 34` to `Community 132`, `Community 19`, `Community 51`, `Community 154`, `Community 155`, `Community 30`?**
  _High betweenness centrality (0.217) - this node is a cross-community bridge._
- **Why does `AppLanguage` connect `Community 88` to `Community 42`?**
  _High betweenness centrality (0.172) - this node is a cross-community bridge._
- **Why does `AppState` connect `Community 19` to `Community 1`, `Community 34`, `Community 133`, `Community 71`, `Community 107`, `Community 13`, `Community 93`, `Community 30`, `Community 31`?**
  _High betweenness centrality (0.166) - this node is a cross-community bridge._
- **Are the 8 inferred relationships involving `SessionRepository` (e.g. with `.onReceive()` and `reconcileQuietNotifications()`) actually correct?**
  _`SessionRepository` has 8 INFERRED edges - model-reasoned connections that need verification._
- **Are the 2 inferred relationships involving `SessionViewModel` (e.g. with `.run()` and `SessionRepository`) actually correct?**
  _`SessionViewModel` has 2 INFERRED edges - model-reasoned connections that need verification._
- **What connects `SYSTEM`, `EN`, `AR` to the rest of the system?**
  _353 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Community 0` be split into smaller, more focused modules?**
  _Cohesion score 0.05120101137800253 - nodes in this community are weakly interconnected._