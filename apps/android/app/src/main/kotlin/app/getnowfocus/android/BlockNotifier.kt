package app.getnowfocus.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Tells the user why a site just failed to load: a blocked lookup gets NXDOMAIN, which a browser
 * shows as a bare "can't reach this site". Deliberately names neither the site nor a session:
 * session and Commitment Shield domains are matched together, and a Shield block must not put a
 * domain in the shade or call itself "your session". Hidden on the lock screen for the same reason.
 *
 * ponytail: a DNS lookup isn't a user attempt (a background app can trigger one with the screen
 * on), so this is throttled to one per minute rather than exact. Needs POST_NOTIFICATIONS, else silent.
 */
object BlockNotifier {
    private const val CHANNEL = "blocked_site"
    private const val ID = 2
    private const val THROTTLE_MS = 60_000L
    private const val TIMEOUT_MS = 15_000L

    @Volatile private var lastPostedAt: Long? = null

    fun blockedSite(context: Context) {
        val now = System.currentTimeMillis()
        if (!HistoryStats.shouldLogBlockEvent(now, lastPostedAt, THROTTLE_MS)) return
        if (context.getSystemService(PowerManager::class.java)?.isInteractive != true) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        lastPostedAt = now

        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Blocked sites", NotificationManager.IMPORTANCE_LOW)
                .apply { lockscreenVisibility = Notification.VISIBILITY_SECRET }
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(
            ID,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_nowfocus_active)
                .setContentTitle("NowFocus blocked a site")
                .setContentText("It will load again when the block ends.")
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setAutoCancel(true)
                .setTimeoutAfter(TIMEOUT_MS)
                .setContentIntent(open)
                .build()
        )
    }
}
