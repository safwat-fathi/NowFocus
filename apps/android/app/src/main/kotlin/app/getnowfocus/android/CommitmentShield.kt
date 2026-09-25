package app.getnowfocus.android

/**
 * The "Always-Blocked" 14-day lock (native_tech_stack_spec.md's "14-Day
 * Commitment Shield Mechanics"). Unlike a FocusSession there is no status
 * enum and no cancel action anywhere in the UI once created - presence in
 * storage plus [isOver] is the entire lifecycle. The mockup this was built
 * from only ever shows it already running; the one exit it has - the spec's
 * required confirmation grace period - is designed here, not copied from it.
 *
 * [canCancel] and [isOver] are boot-relative (elapsedRealtime + boot count),
 * not wall-clock, so the grace period and the 14-day duration can't be
 * gamed by changing the phone's date: winding it back can't reopen the
 * grace period forever, and winding it forward can't fake expiry (which
 * would otherwise let a throwaway shield be created over, and permanently
 * replace, a real one). A reboot resets elapsedRealtime, so both functions
 * fall back to wall-clock across one - the only place wall-clock trust is
 * unavoidable without network time, and it's a fallback, not the primary
 * check.
 *
 * Known limitation, disclosed rather than hidden: disabling Accessibility,
 * revoking VPN consent, or uninstalling the app all still defeat this.
 */
data class CommitmentShield(
    val startAt: Long,
    val endAt: Long,
    val domains: Set<String>,
    val packages: Set<String>,
    val createdAt: Long,
    val createdElapsedRealtime: Long,
    val createdBootCount: Int,
) {
    /** The only cancel window this ever gets - after this, it runs the full 14 days. Refuses across a reboot rather than guess. */
    fun canCancel(nowElapsedRealtime: Long, currentBootCount: Int): Boolean =
        currentBootCount == createdBootCount && (nowElapsedRealtime - createdElapsedRealtime) in 0 until GRACE_MS

    /** Whether the 14 days are genuinely up. Wall-clock only as a fallback once a reboot makes elapsed time meaningless. */
    fun isOver(wallNow: Long, nowElapsedRealtime: Long, currentBootCount: Int): Boolean =
        if (currentBootCount == createdBootCount) nowElapsedRealtime - createdElapsedRealtime >= DURATION_MS
        else wallNow >= endAt

    companion object {
        const val GRACE_MS = 60_000L
        const val DURATION_MS = 14L * 24 * 60 * 60 * 1000
    }
}
