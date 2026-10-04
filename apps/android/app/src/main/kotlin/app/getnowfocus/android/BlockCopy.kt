package app.getnowfocus.android

/** Why NowFocus closed an app. The block screen and the blocked-site notice both word themselves from this. */
enum class BlockReason { FOCUS_SESSION, BEDTIME, DAILY_LIMIT, COMMITMENT_SHIELD }

/**
 * What the user is told when NowFocus closes an app: who did it (always NowFocus, always by name) and
 * which rule it was. Pure strings so the wording is tested once; macOS and Windows use the same sentences.
 */
object BlockCopy {
    fun title(appName: String?) = "NowFocus closed ${appName ?: "this app"}"

    /** [until] is already formatted for the user. [limitMinutes] is only read for [BlockReason.DAILY_LIMIT]. */
    fun reason(reason: BlockReason, until: String, appName: String?, limitMinutes: Int = 0): String = when (reason) {
        BlockReason.FOCUS_SESSION -> "You're in a focus session until $until."
        BlockReason.BEDTIME -> "It's bedtime wind-down until $until."
        BlockReason.COMMITMENT_SHIELD -> "Locked by your Commitment Shield until $until."
        BlockReason.DAILY_LIMIT -> "You've used your $limitMinutes minutes of ${appName ?: "this app"} today. It's back at midnight."
    }

    /** The label beside the countdown on the block screen. */
    fun timeLabel(reason: BlockReason) = when (reason) {
        BlockReason.FOCUS_SESSION -> "Left in session"
        BlockReason.BEDTIME -> "Left in bedtime"
        BlockReason.COMMITMENT_SHIELD -> "Locked for"
        BlockReason.DAILY_LIMIT -> "Back in"
    }

    /** The blocked-site notification line. Names the cause, never the site (see [BlockNotifier]). */
    fun siteNotice(reason: BlockReason) = when (reason) {
        BlockReason.FOCUS_SESSION -> "Your focus session is on. It will load again when it ends."
        BlockReason.BEDTIME -> "Bedtime wind-down is on. It will load again when it ends."
        BlockReason.COMMITMENT_SHIELD -> "Your Commitment Shield is on. It will load again when it ends."
        BlockReason.DAILY_LIMIT -> "Your daily limit is used up. It will load again at midnight."
    }
}
