package app.getnowfocus.android

enum class FocusSessionStatus { SCHEDULED, ACTIVE, COMPLETED, CANCELLED, EXPIRED, ERROR }

/** Mirrors macOS FocusSession.swift's EnforcementMode: how much friction stopping early costs. */
enum class EnforcementMode { NORMAL, STRICT, LOCKED }

/**
 * Where a session came from. Only USER and REMOTE sessions are synced across devices: Bedtime and schedules
 * run on every device from their own settings, so syncing them too would start them N times.
 */
enum class SessionOrigin { USER, SCHEDULE, REMOTE }

/** Mirrors macOS FocusSession.swift's SessionType. Bedtime wind-down runs as a
 * LOCKED focus session; Stats filters to FOCUS so nightly sessions don't inflate it. */
enum class SessionType { FOCUS, BEDTIME_WINDDOWN }

data class FocusSession(
    val id: String,
    val policyId: String,
    val startAt: Long,
    val endAt: Long,
    val status: FocusSessionStatus,
    val createdAt: Long,
    // Snapshot of the policy at start, like macOS startEnforcement(policy:):
    // editing or deleting the policy mid-session doesn't loosen the block.
    val domains: Set<String> = emptySet(),
    val packages: Set<String> = emptySet(),
    val partial: Set<PartialRule> = emptySet(),
    val enforcementMode: EnforcementMode = EnforcementMode.NORMAL,
    val sessionType: SessionType = SessionType.FOCUS,
    // Real elapsed time on a cancelled session (stats need this, not the scheduled duration).
    val cancelledAt: Long? = null,
    // STRICT only: a note to yourself that must be played through before the
    // session can be ended early (see SessionEngine.canCancel and VoiceNote).
    val voiceNotePath: String? = null,
    // Short per-app exceptions granted inside this session (see Passes). Device-local.
    val passes: List<AppPass> = emptyList(),
    val origin: SessionOrigin = SessionOrigin.USER,
    // REMOTE only: the name of the device it was started on, for the "joined" note.
    val startedOn: String? = null,
)
