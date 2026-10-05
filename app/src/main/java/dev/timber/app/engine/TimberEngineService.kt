package dev.timber.app.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.timber.app.R

/**
 * Keeps audio/MIDI engines alive while the screen is off during a session.
 * Bound wiring comes next; started from the session layer when transport runs.
 */
class TimberEngineService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "timber_engine"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(channelId, "Timber Engine", NotificationManager.IMPORTANCE_LOW),
        )
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Session engine running")
            .setSmallIcon(R.drawable.ic_launcher)
            .build()
        startForeground(1, notification)
        return START_STICKY
    }
}
