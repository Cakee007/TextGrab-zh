package dev.noah.textgrab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Minimal foreground service whose only purpose is to keep this process out
 * of the OEM's freezer.
 *
 * ## Why it exists
 *
 * ColorOS (and most other skins) freeze apps that have been idle for a while.
 * When TextGrab is frozen the system drops its accessibility service, which is
 * why the permission had to be re-enabled by hand every time the app was used
 * after a long pause. A foreground service exempts the process from that
 * freezer, so the accessibility binding survives.
 *
 * ## Cost
 *
 * One ongoing notification and a few tens of kilobytes of memory. The service
 * has no timer, no wakelock, no network and no polling — it simply exists, so
 * the battery impact is on the order of a couple of mAh per day.
 */
class KeepAliveService : Service() {

    companion object {
        private const val TAG = "TextGrabKeepAlive"
        private const val CHANNEL_ID = "textgrab_ready"
        private const val NOTIFICATION_ID = 4201

        /** Starts the service, or does nothing if it is already running. */
        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Log.w(TAG, "could not start", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, KeepAliveService::class.java)) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        Log.d(TAG, "keep-alive running")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Not sticky: if the process dies there is nothing to resume, and a
        // stale foreground service would only show a dead notification.
        return START_NOT_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.keepalive_channel),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = getString(R.string.keepalive_channel_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ready)
            .setContentTitle(getString(R.string.keepalive_title))
            .setContentText(getString(R.string.keepalive_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setShowWhen(false)
            .build()
    }
}
