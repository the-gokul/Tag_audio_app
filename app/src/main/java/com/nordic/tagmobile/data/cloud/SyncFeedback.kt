package com.nordic.tagmobile.data.cloud

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.nordic.tagmobile.HistoryActivity
import com.nordic.tagmobile.R
import com.nordic.tagmobile.log.LogCategory
import com.nordic.tagmobile.log.TagLogger
import com.nordic.tagmobile.work.SyncActionReceiver

/** User-visible feedback when cloud sync finishes or data is still waiting. */
object SyncFeedback {
    const val CHANNEL_ID = "cloud_sync"
    private const val NOTIFICATION_ID = 1001
    private const val REMINDER_ID = 1002

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Cloud sync",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Session upload success, failure, and unsynced reminders"
        }
        mgr.createNotificationChannel(channel)
    }

    fun notifyUploadFinished(
        context: Context,
        successCount: Int,
        failedCount: Int,
        firstError: String? = null,
        remainingUnsynced: Int = 0,
        /** Set when a single session was uploaded (auto after Stop / one Sync tap). */
        sessionId: String? = null,
    ) {
        if (successCount == 0 && failedCount == 0 && remainingUnsynced == 0) return
        ensureChannel(context)

        val title: String
        val text: String
        val stillPending = remainingUnsynced > 0 || failedCount > 0
        when {
            // Truly finished: nothing left pending on phone.
            failedCount == 0 && remainingUnsynced == 0 -> {
                if (!sessionId.isNullOrBlank() && successCount == 1) {
                    title = context.getString(R.string.notify_sync_one_done_title)
                    text = context.getString(R.string.notify_sync_one_done_all_clear, sessionId)
                } else {
                    title = context.getString(R.string.notify_sync_complete_title)
                    text = if (successCount == 1) {
                        context.getString(R.string.notify_sync_complete_one)
                    } else {
                        context.getString(R.string.notify_sync_complete_many, successCount)
                    }
                }
                cancelUnsyncedReminder(context)
            }
            // One session OK, but others still waiting — do NOT say "sync finished" overall.
            failedCount == 0 && remainingUnsynced > 0 && !sessionId.isNullOrBlank() && successCount == 1 -> {
                title = context.getString(R.string.notify_sync_one_done_title)
                text = context.getString(
                    R.string.notify_sync_one_done_more_pending,
                    sessionId,
                    remainingUnsynced,
                )
                notifyUnsyncedReminder(context, remainingUnsynced, text)
            }
            failedCount == 0 && remainingUnsynced > 0 -> {
                title = context.getString(R.string.notify_sync_batch_more_pending_title)
                text = context.getString(
                    R.string.notify_sync_batch_more_pending,
                    successCount,
                    remainingUnsynced,
                )
                notifyUnsyncedReminder(context, remainingUnsynced, text)
            }
            successCount == 0 && failedCount > 0 -> {
                title = context.getString(R.string.notify_sync_failed_title)
                val err = firstError?.take(80).orEmpty()
                text = if (err.isNotBlank()) {
                    context.getString(R.string.notify_sync_failed_detail, err)
                } else {
                    context.getString(R.string.notify_sync_failed_body)
                }
                notifyUnsyncedReminder(context, failedCount.coerceAtLeast(remainingUnsynced).coerceAtLeast(1), text)
            }
            else -> {
                title = context.getString(R.string.notify_sync_partial_title)
                text = context.getString(
                    R.string.notify_sync_partial_body,
                    successCount,
                    failedCount.coerceAtLeast(remainingUnsynced),
                )
                notifyUnsyncedReminder(
                    context,
                    (remainingUnsynced + failedCount).coerceAtLeast(1),
                    text,
                )
            }
        }

        showMainNotification(context, title, text, showSyncAll = stillPending)
        TagLogger.log(LogCategory.APP, "SYNC_FEEDBACK", "$title | $text")
    }

    /**
     * Session saved on phone but not uploaded yet (e.g. waiting for Wi‑Fi / upload failed).
     */
    fun notifySessionSavedNeedsSync(
        context: Context,
        sessionId: String,
        waitingForWifi: Boolean,
    ) {
        ensureChannel(context)
        val title = context.getString(R.string.notify_saved_local_title)
        val text = if (waitingForWifi) {
            context.getString(R.string.notify_saved_waiting_wifi, sessionId)
        } else {
            context.getString(R.string.notify_saved_please_sync, sessionId)
        }
        showMainNotification(context, title, text, showSyncAll = true)
        TagLogger.log(LogCategory.APP, "SYNC_FEEDBACK", "$title | $text")
    }

    fun notifyUnsyncedReminder(context: Context, count: Int, reason: String? = null) {
        if (count <= 0) {
            cancelUnsyncedReminder(context)
            return
        }
        ensureChannel(context)
        val title = context.getString(R.string.notify_unsynced_title)
        val text = reason?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.notify_unsynced_body, count)

        val openHistory = historyPendingIntent(context, 21)
        val syncAll = syncAllPendingIntent(context, 22)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_share)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    text + "\n\n" + context.getString(R.string.notify_unsynced_hint),
                ),
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(false)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(openHistory)
            .addAction(0, context.getString(R.string.notify_action_sync_all), syncAll)
            .addAction(0, context.getString(R.string.notify_action_open_history), openHistory)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(REMINDER_ID, notification)
        } catch (e: SecurityException) {
            TagLogger.log(LogCategory.APP, "SYNC_NOTIFY_DENIED", e.message ?: "no permission")
        }
        TagLogger.log(LogCategory.APP, "SYNC_UNSYNCED_REMINDER", "count=$count $text")
    }

    fun cancelUnsyncedReminder(context: Context) {
        NotificationManagerCompat.from(context).cancel(REMINDER_ID)
    }

    private fun showMainNotification(
        context: Context,
        title: String,
        text: String,
        showSyncAll: Boolean,
    ) {
        val openHistory = historyPendingIntent(context, 11)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_share)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openHistory)

        if (showSyncAll) {
            builder.addAction(
                0,
                context.getString(R.string.notify_action_sync_all),
                syncAllPendingIntent(context, 12),
            )
        }

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            TagLogger.log(LogCategory.APP, "SYNC_NOTIFY_DENIED", e.message ?: "no permission")
        }
    }

    private fun historyPendingIntent(context: Context, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, HistoryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun syncAllPendingIntent(context: Context, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, SyncActionReceiver::class.java).setAction(SyncActionReceiver.ACTION_SYNC_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
