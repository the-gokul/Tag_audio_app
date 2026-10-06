package com.nordic.tagmobile.work

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import android.os.Build
import com.nordic.tagmobile.R
import com.nordic.tagmobile.TagApp
import com.nordic.tagmobile.data.cloud.NetworkProbe
import com.nordic.tagmobile.data.cloud.SyncFeedback
import com.nordic.tagmobile.data.cloud.UploadProgress
import com.nordic.tagmobile.domain.model.UploadResult
import com.nordic.tagmobile.log.LogCategory
import com.nordic.tagmobile.log.TagLogger
import java.util.concurrent.TimeUnit

class UploadSessionWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val requireWifi = inputData.getBoolean(KEY_REQUIRE_WIFI, false)
        val net = NetworkProbe.summary(applicationContext)
        if (requireWifi && !NetworkProbe.isWifiConnected(applicationContext)) {
            TagLogger.log(
                LogCategory.APP,
                "CLOUD_WAIT_WIFI",
                "Wi‑Fi not available yet; retry later. $net",
            )
            return Result.retry()
        }

        val repo = TagApp.instance.cloudRepository
        val sessionId = inputData.getString(KEY_SESSION_ID)
        val target = if (sessionId.isNullOrBlank()) "ALL_PENDING" else sessionId
        TagLogger.log(
            LogCategory.APP,
            "CLOUD_UPLOAD_START",
            "target=$target attempt=$runAttemptCount requireWifi=$requireWifi $net",
        )
        // Foreground = no 10-minute cap, so an hour-long video keeps uploading with the screen off.
        // Android 12+ refuses this when the app is in the background; the upload then still
        // runs and, if stopped, resumes from the server's byte count on the next run.
        try {
            setForeground(foregroundInfo(getString(R.string.notify_upload_progress_starting), 0, 0))
        } catch (e: Exception) {
            TagLogger.log(LogCategory.APP, "CLOUD_UPLOAD_NO_FOREGROUND", e.message ?: e.javaClass.simpleName)
        }
        var lastShown = ""
        val progressListener = UploadProgress.Listener { snap ->
            val text = getString(R.string.notify_upload_progress, snap.sessionId, snap.describe())
            // describe() changes at most once per chunk (~8 MB), so this does not spam updates.
            if (text != lastShown) {
                lastShown = text
                val max = if (snap.phase == UploadProgress.Phase.UPLOADING) 100 else 0
                runCatching { setForegroundAsync(foregroundInfo(text, snap.percent, max)) }
            }
        }
        UploadProgress.add(progressListener)
        val t0 = System.currentTimeMillis()
        val results = try {
            if (sessionId.isNullOrBlank()) {
                repo.uploadPendingSessions()
            } else {
                listOf(repo.uploadSession(sessionId))
            }
        } finally {
            UploadProgress.remove(progressListener)
        }

        val failed = results.filterIsInstance<UploadResult.Failed>()
        val ok = results.count { it is UploadResult.Success || it is UploadResult.AlreadySynced }
        val ms = System.currentTimeMillis() - t0
        TagLogger.log(
            LogCategory.APP,
            "CLOUD_UPLOAD_WORKER",
            "ok=$ok failed=${failed.size} ms=$ms $net detail=${
                results.joinToString { r ->
                    when (r) {
                        is UploadResult.Success -> "Success:${r.sessionId}->${r.remotePath}"
                        is UploadResult.AlreadySynced -> "AlreadySynced:${r.sessionId}"
                        is UploadResult.Failed -> "Failed:${r.sessionId}:${r.message}"
                    }
                }
            }",
        )
        if (failed.isNotEmpty()) {
            TagLogger.log(
                LogCategory.ERRORS,
                "CLOUD_UPLOAD_FAIL",
                failed.joinToString { "${it.sessionId}: ${it.message}" },
            )
        }
        val remaining = try {
            com.nordic.tagmobile.data.local.db.TagDatabase.get(applicationContext)
                .sessionDao()
                .getPendingOrFailed()
                .size
        } catch (_: Exception) {
            failed.size
        }
        SyncFeedback.notifyUploadFinished(
            applicationContext,
            successCount = ok,
            failedCount = failed.size,
            firstError = failed.firstOrNull()?.message,
            remainingUnsynced = remaining,
            sessionId = sessionId?.takeIf { it.isNotBlank() && results.size == 1 },
        )
        return if (failed.isNotEmpty() && ok == 0) Result.retry() else Result.success()
    }

    /** Used for expedited work on Android 11 and older, which runs as a foreground service. */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(applicationContext.getString(R.string.notify_upload_progress_starting), 0, 0)

    private fun getString(resId: Int, vararg args: Any): String =
        applicationContext.getString(resId, *args)

    private fun foregroundInfo(text: String, progress: Int, max: Int): ForegroundInfo {
        SyncFeedback.ensureChannel(applicationContext)
        val notification = NotificationCompat.Builder(applicationContext, SyncFeedback.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_share)
            .setContentTitle(applicationContext.getString(R.string.notify_upload_progress_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setProgress(max, progress, max == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        val notificationId = FOREGROUND_NOTIFICATION_BASE + (id.hashCode() and 0xfff)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    companion object {
        private const val FOREGROUND_NOTIFICATION_BASE = 2000
        const val UNIQUE_ALL = "upload-sessions-all"
        const val UNIQUE_PERIODIC = "upload-sessions-periodic"
        const val KEY_SESSION_ID = "session_id"
        const val KEY_REQUIRE_WIFI = "require_wifi"

        /**
         * Use CONNECTED (not UNMETERED). On Android 9–11 the default network is often cellular
         * while Wi‑Fi is up, so UNMETERED never becomes true. We enforce Wi‑Fi in [doWork] via
         * [KEY_REQUIRE_WIFI] + [NetworkProbe.isWifiConnected].
         */
        private fun onlineConstraints(): Constraints =
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

        /** Manual Sync from History — any network (Wi‑Fi or mobile). */
        fun enqueueAll(context: Context, requireWifi: Boolean = false) {
            val net = NetworkProbe.summary(context)
            TagLogger.log(
                LogCategory.APP,
                "CLOUD_ENQUEUE_ALL",
                "requireWifi=$requireWifi policy=REPLACE $net",
            )
            val req = OneTimeWorkRequestBuilder<UploadSessionWorker>()
                .setInputData(workDataOf(KEY_REQUIRE_WIFI to requireWifi))
                .setConstraints(onlineConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_ALL,
                ExistingWorkPolicy.REPLACE,
                req,
            )
        }

        /** Manual single-session Sync — any network. */
        fun enqueueSession(context: Context, sessionId: String, requireWifi: Boolean = false) {
            val net = NetworkProbe.summary(context)
            TagLogger.log(
                LogCategory.APP,
                "CLOUD_ENQUEUE_SESSION",
                "session=$sessionId requireWifi=$requireWifi policy=REPLACE $net",
            )
            val req = OneTimeWorkRequestBuilder<UploadSessionWorker>()
                .setInputData(
                    workDataOf(
                        KEY_SESSION_ID to sessionId,
                        KEY_REQUIRE_WIFI to requireWifi,
                    ),
                )
                .setConstraints(onlineConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "upload-session-$sessionId",
                ExistingWorkPolicy.REPLACE,
                req,
            )
        }

        /**
         * After Stop/save: auto-upload when Wi‑Fi is available (checked in worker).
         * KEEP avoids canceling an in-flight upload.
         */
        fun enqueueAfterSave(context: Context, sessionId: String) {
            val net = NetworkProbe.summary(context)
            TagLogger.log(
                LogCategory.APP,
                "CLOUD_AUTO_ENQUEUE",
                "session=$sessionId requireWifi=true policy=KEEP $net",
            )
            val reqBuilder = OneTimeWorkRequestBuilder<UploadSessionWorker>()
                .setInputData(
                    workDataOf(
                        KEY_SESSION_ID to sessionId,
                        KEY_REQUIRE_WIFI to true,
                    ),
                )
                .setConstraints(onlineConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            // Expedited helps Android 12–14 run sooner while app is still foreground.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                reqBuilder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            val req = reqBuilder.build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "upload-session-$sessionId",
                ExistingWorkPolicy.KEEP,
                req,
            )
        }

        /** Cancel one-shot upload work so it cannot race an in-process direct upload. */
        fun cancelForSession(context: Context, sessionId: String) {
            WorkManager.getInstance(context).cancelUniqueWork("upload-session-$sessionId")
            TagLogger.log(LogCategory.APP, "CLOUD_CANCEL_SESSION_WORK", sessionId)
        }

        /** On app start: drain PENDING/FAILED when Wi‑Fi is available. */
        fun enqueuePendingOnWifi(context: Context) {
            TagLogger.log(
                LogCategory.APP,
                "CLOUD_ENQUEUE_PENDING_WIFI",
                NetworkProbe.summary(context),
            )
            val req = OneTimeWorkRequestBuilder<UploadSessionWorker>()
                .setInputData(workDataOf(KEY_REQUIRE_WIFI to true))
                .setConstraints(onlineConstraints())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_ALL,
                ExistingWorkPolicy.KEEP,
                req,
            )
        }

        /** Background retry on Wi‑Fi. Testing: 15 min. */
        fun schedulePeriodicWifiRetry(context: Context) {
            val intervalMinutes = 15L
            TagLogger.log(
                LogCategory.APP,
                "CLOUD_PERIODIC_SCHEDULE",
                "every=${intervalMinutes}min requireWifi=true",
            )
            val req = PeriodicWorkRequestBuilder<UploadSessionWorker>(intervalMinutes, TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_REQUIRE_WIFI to true))
                .setConstraints(onlineConstraints())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                req,
            )
        }
    }
}
