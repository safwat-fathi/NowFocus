package app.getnowfocus.android

enum class FocusSessionStatus { SCHEDULED, ACTIVE, COMPLETED, CANCELLED, EXPIRED, ERROR }

/** Mirrors macOS FocusSession.swift's EnforcementMode: how much friction stopping early costs. */
enum class EnforcementMode { NORMAL, STRICT, LOCKED }

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
    val enforcementMode: EnforcementMode = EnforcementMode.NORMAL,
    // Real elapsed time on a cancelled session (stats need this, not the scheduled duration).
    val cancelledAt: Long? = null,
)
