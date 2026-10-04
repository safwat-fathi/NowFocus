package app.getnowfocus.android

/** Why NowFocus closed an app. The block screen and the blocked-site notice both word themselves from this. */
enum class BlockReason { FOCUS_SESSION, ALLOWLIST_SESSION, BEDTIME, DAILY_LIMIT, COMMITMENT_SHIELD }

/**
 * What the user is told when NowFocus closes an app: who did it (always NowFocus, always by name) and
 * which rule it was. Returns [UiText] (the wording lives in strings.xml; tests pin which sentence is chosen);
 * macOS and Windows use the same sentences.
 */
object BlockCopy {
    fun title(appName: String?): UiText = uiText(R.string.block_title, appName ?: uiText(R.string.block_this_app))

    /** [until] is already formatted for the user. [limitMinutes] is only read for [BlockReason.DAILY_LIMIT]. */
    fun reason(reason: BlockReason, until: String, appName: String?, limitMinutes: Int = 0): UiText = when (reason) {
        BlockReason.FOCUS_SESSION -> uiText(R.string.block_reason_session, until)
        BlockReason.ALLOWLIST_SESSION -> uiText(R.string.block_reason_allowlist, until)
        BlockReason.BEDTIME -> uiText(R.string.block_reason_bedtime, until)
        BlockReason.COMMITMENT_SHIELD -> uiText(R.string.block_reason_shield, until)
        BlockReason.DAILY_LIMIT -> UiText.Plural(
            R.plurals.block_reason_limit, limitMinutes, listOf(limitMinutes, appName ?: uiText(R.string.block_this_app)),
        )
    }

    /** The label beside the countdown on the block screen. */
    fun timeLabel(reason: BlockReason): UiText = uiText(
        when (reason) {
            BlockReason.FOCUS_SESSION, BlockReason.ALLOWLIST_SESSION -> R.string.block_time_session
            BlockReason.BEDTIME -> R.string.block_time_bedtime
            BlockReason.COMMITMENT_SHIELD -> R.string.block_time_shield
            BlockReason.DAILY_LIMIT -> R.string.block_time_limit
        },
    )

    /** The blocked-site notification line. Names the cause, never the site (see [BlockNotifier]). */
    fun siteNotice(reason: BlockReason): UiText = uiText(
        when (reason) {
            // An allowlist never blocks a site, so this is only here to keep the `when` exhaustive.
            BlockReason.FOCUS_SESSION, BlockReason.ALLOWLIST_SESSION -> R.string.block_site_session
            BlockReason.BEDTIME -> R.string.block_site_bedtime
            BlockReason.COMMITMENT_SHIELD -> R.string.block_site_shield
            BlockReason.DAILY_LIMIT -> R.string.block_site_limit
        },
    )
}
