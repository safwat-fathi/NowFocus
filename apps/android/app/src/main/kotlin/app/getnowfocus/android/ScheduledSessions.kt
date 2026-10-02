package app.getnowfocus.android

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.UUID

/**
 * Starts the session a recurring [Schedule] says should be running now, and arms the alarm for the next
 * one. Safe to call often and from anywhere, like [reconcileBedtimeSession] (which calls it, so every
 * existing Bedtime re-arm path is a schedules re-arm path too): every guard no-ops when it doesn't apply.
 */
suspend fun reconcileScheduledSessions(context: Context) {
    val repo = SessionRepository(context)
    val now = System.currentTimeMillis()
    val zone = ZoneId.systemDefault()
    val schedules = repo.schedulesFlow.first()
    val cheat = repo.cheatDayFlow.first()?.takeIf { it.isActive(now) }

    // Nothing starts by itself on a cheat day; the alarm below is also set for when it ends.
    if (cheat == null) startDueSchedule(context, repo, schedules, now, zone)
    ScheduleScheduler.arm(context, listOfNotNull(Schedules.nextStart(schedules, now, zone), cheat?.endAt).minOrNull())
}

private suspend fun startDueSchedule(context: Context, repo: SessionRepository, schedules: List<Schedule>, now: Long, zone: ZoneId) {
    val due = Schedules.current(schedules, now, zone) ?: return
    // Already begun this window (even if it was ended early): don't start it again.
    if (repo.scheduleRunsFlow.first()[due.schedule.id] == due.start) return
    // One session at a time, like Bedtime: a running one holds, and this is re-checked when it ends.
    val existing = repo.sessionFlow.first()
    if (existing != null && SessionEngine.isActive(SessionEngine.evaluateState(existing, now), now)) return
    val policy = repo.policiesFlow.first().find { it.id == due.schedule.policyId } ?: return

    repo.noteScheduleRun(due.schedule.id, due.start)
    repo.save(SessionEngine.evaluateState(
        FocusSession(
            id = UUID.randomUUID().toString(),
            policyId = policy.id,
            startAt = now, // an alarm can be minutes late, or the phone was off: the block starts when we notice
            endAt = due.end,
            status = FocusSessionStatus.SCHEDULED,
            createdAt = now,
            domains = policy.domains.toSet(),
            packages = policy.apps.map { it.packageName }.toSet(),
            partial = policy.partial,
            enforcementMode = due.schedule.mode,
            origin = SessionOrigin.SCHEDULE,
        ),
        now,
    ))
    Enforcement.start(context)
}

object ScheduleScheduler {
    private const val REQUEST_CODE = 3

    /** One alarm for the next start (or the end of a cheat day); null cancels it. Inexact, like Bedtime's. */
    fun arm(context: Context, triggerAt: Long?) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(
            context, REQUEST_CODE, Intent(context, ScheduleAlarmReceiver::class.java).setAction(ScheduleAlarmReceiver.ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (triggerAt == null) am.cancel(pending) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
    }
}

class ScheduleAlarmReceiver : BroadcastReceiver() {
    companion object { const val ACTION = "app.getnowfocus.android.SCHEDULE_START" }

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val settings = SessionRepository(context).bedtimeSettingsFlow.first()
                reconcileQuietNotifications(context, settings) // a cheat day just ended
                reconcileBedtimeSession(context, settings)     // also reconciles schedules and re-arms this alarm
            } finally {
                pending.finish()
            }
        }
    }
}
