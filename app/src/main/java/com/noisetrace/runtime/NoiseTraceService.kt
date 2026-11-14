package com.noisetrace.runtime

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.noisetrace.MainActivity
import com.noisetrace.NoiseTraceApp
import com.noisetrace.R

/**
 * Keeps the app in the foreground so the microphone memory and the detector keep running while the navigation is on
 * screen or the screen is restricted during driving. Android only lets a foreground service of type microphone
 * record in the background, and only once RECORD_AUDIO is granted, so the type is picked when (re)started.
 * The notification says plainly that the microphone is in use.
 */
class NoiseTraceService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val tracking = (application as NoiseTraceApp).controller.state.value.trackingOn
        val mic = tracking && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notification = buildNotification(mic)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or (if (mic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    private fun buildNotification(mic: Boolean): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(if (mic) R.string.notification_tracking else R.string.notification_idle))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "noisetrace_tracking"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, NoiseTraceService::class.java))
        }
    }
}
