package com.nordic.tagmobile.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.nordic.tagmobile.MainActivity
import com.nordic.tagmobile.R
import com.nordic.tagmobile.log.LogCategory
import com.nordic.tagmobile.log.TagLogger

/**
 * Keeps the process eligible while a Tag is linked (commercial BLE hold).
 * Ongoing notification + partial wake lock reduce OEM background kills.
 */
class TagBleForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelfSafe()
                return START_NOT_STICKY
            }
            else -> {
                ensureChannel()
                val notification = buildNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                acquireWakeLock()
                TagLogger.log(LogCategory.BLE, "BLE_FGS_START", "Tag link held in foreground")
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        TagLogger.log(LogCategory.BLE, "BLE_FGS_STOP", "Tag link foreground ended")
        super.onDestroy()
    }

    private fun stopSelfSafe() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
            // ignore
        }
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Tag:BleHold").apply {
            setReferenceCounted(false)
            acquire(4 * 60 * 60 * 1000L) // capped 4h; refreshed when service restarts
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
            // ignore
        }
        wakeLock = null
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.ble_fgs_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.ble_fgs_channel_desc)
            setShowBadge(false)
        }
        mgr.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bluetooth)
            .setContentTitle(getString(R.string.ble_fgs_title))
            .setContentText(getString(R.string.ble_fgs_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "tag_ble_link"
        private const val NOTIFICATION_ID = 2101
        private const val ACTION_STOP = "com.nordic.tagmobile.ble.STOP_FGS"

        fun start(context: Context) {
            val intent = Intent(context, TagBleForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TagBleForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {
                context.stopService(Intent(context, TagBleForegroundService::class.java))
            }
        }
    }
}
