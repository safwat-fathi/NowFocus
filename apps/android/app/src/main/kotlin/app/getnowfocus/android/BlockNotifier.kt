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
 * shows as a bare "can't reach this site". Names the rule that blocked it (the Shield when it blocks
 * the domain too, else the session or bedtime) but never the site: no domain goes in the shade.
 * Hidden on the lock screen for the same reason.
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

    fun blockedSite(context: Context, reason: BlockReason) {
        val now = System.currentTimeMillis()
        if (!HistoryStats.shouldLogBlockEvent(now, lastPostedAt, THROTTLE_MS)) return
        if (context.getSystemService(PowerManager::class.java)?.isInteractive != true) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        lastPostedAt = now

        val t = context.localized()
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, t.getString(R.string.notif_blocked_channel), NotificationManager.IMPORTANCE_LOW)
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
                .setContentTitle(t.getString(R.string.notif_blocked_title))
                .setContentText(BlockCopy.siteNotice(reason).resolve(t))
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setAutoCancel(true)
                .setTimeoutAfter(TIMEOUT_MS)
                .setContentIntent(open)
                .build()
        )
    }
}
