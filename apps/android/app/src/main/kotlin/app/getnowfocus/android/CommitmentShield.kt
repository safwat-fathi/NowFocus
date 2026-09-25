package app.getnowfocus.android

/**
 * The "Always-Blocked" 14-day lock (native_tech_stack_spec.md's "14-Day
 * Commitment Shield Mechanics"). Unlike a FocusSession there is no status
 * enum and no cancel action anywhere in the UI once created - presence in
 * storage plus [endAt] is the entire lifecycle. The mockup this was built
 * from only ever shows it already running; the one exit it has - the spec's
 * required confirmation grace period - is designed here, not copied from it.
 *
 * Known limitation, disclosed rather than hidden: disabling Accessibility,
 * revoking VPN consent, or uninstalling the app all still defeat this. There
 * is no network-time verification in this build, so "Tamper-proof clock"
 * from the mockup's copy does not apply - a phone clock set backwards would
 * extend it, not shorten it, since endAt is compared against wall-clock time.
 */
data class CommitmentShield(
    val startAt: Long,
    val endAt: Long,
    val domains: Set<String>,
    val packages: Set<String>,
    val createdAt: Long,
) {
    /** The only cancel window this ever gets - after this, it runs the full 14 days. */
    fun canCancel(now: Long): Boolean = now < createdAt + GRACE_MS

    companion object {
        const val GRACE_MS = 60_000L
        const val DURATION_MS = 14L * 24 * 60 * 60 * 1000
    }
}
