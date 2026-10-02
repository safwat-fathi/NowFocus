package app.getnowfocus.android

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.first

/**
 * The ongoing "session in progress" notification. Derived from the stored
 * session, so any start/end path just calls [sync]. The countdown is the
 * notification's own chronometer and [NotificationCompat.Builder.setTimeoutAfter]
 * removes it at endAt - nothing fires at endAt on Android, so that's what
 * clears it on a natural finish; early cancel calls [sync] explicitly.
 *
 * ponytail: not a foreground service, so Android 14+ lets the user swipe it
 * away; promote to a foreground service if that matters.
 */
object SessionNotifier {
    private const val CHANNEL = "session"
    private const val ID = 1

    suspend fun sync(context: Context) {
        val now = System.currentTimeMillis()
        val session = SessionRepository(context).sessionFlow.first()
            ?.let { SessionEngine.evaluateState(it, now) }
            ?.takeIf { SessionEngine.isActive(it, now) }
        val nm = context.getSystemService(NotificationManager::class.java)
        if (session == null) {
            nm.cancel(ID)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Focus session", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(
            ID,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_nowfocus_active)
                .setContentTitle("Focus session in progress")
                .setContentText("Blocked sites won't load until the timer ends.")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .setWhen(session.endAt)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setTimeoutAfter(session.endAt - now)
                .build()
        )
    }
}
