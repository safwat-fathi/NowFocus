package app.getnowfocus.android

/**
 * Mirrors apps/macos/NowFocusCore/SessionEngine.swift: endAt (not a UI timer) decides
 * whether a session is active, and every recovery path recomputes state from
 * startAt/endAt/status plus the current time.
 */
object SessionEngine {

    fun evaluateState(session: FocusSession, now: Long = System.currentTimeMillis()): FocusSession =
        when (session.status) {
            FocusSessionStatus.SCHEDULED -> when {
                now >= session.startAt && now < session.endAt -> session.copy(status = FocusSessionStatus.ACTIVE)
                now >= session.endAt -> session.copy(status = FocusSessionStatus.EXPIRED)
                else -> session
            }
            FocusSessionStatus.ACTIVE ->
                if (now >= session.endAt) session.copy(status = FocusSessionStatus.COMPLETED) else session
            else -> session
        }

    fun isActive(session: FocusSession, now: Long = System.currentTimeMillis()): Boolean =
        session.status == FocusSessionStatus.ACTIVE && now >= session.startAt && now < session.endAt
}
