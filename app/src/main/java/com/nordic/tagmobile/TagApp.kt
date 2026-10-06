package com.nordic.tagmobile

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.nordic.tagmobile.ble.TagBleManager
import com.nordic.tagmobile.ble.TagBleScanner
import com.nordic.tagmobile.data.cloud.CloudRepository
import com.nordic.tagmobile.data.cloud.CloudRepositoryImpl
import com.nordic.tagmobile.data.cloud.StubCloudBackend
import com.nordic.tagmobile.data.cloud.NetworkProbe
import com.nordic.tagmobile.data.cloud.SyncFeedback
import com.nordic.tagmobile.data.cloud.server.TagServerCloudBackend
import com.nordic.tagmobile.data.cloud.supabase.SupabaseCloudBackend
import com.nordic.tagmobile.data.local.SessionIndexer
import com.nordic.tagmobile.data.local.db.TagDatabase
import com.nordic.tagmobile.domain.model.UploadResult
import com.nordic.tagmobile.log.LogCategory
import com.nordic.tagmobile.log.TagLogger
import com.nordic.tagmobile.model.RecordingState
import com.nordic.tagmobile.work.UploadSessionWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TagApp : Application() {
    lateinit var bleManager: TagBleManager
        private set
    lateinit var bleScanner: TagBleScanner
        private set
    lateinit var cloudRepository: CloudRepository
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uploadMutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Keeps Home / session state in sync when BLE drops outside Record screen. */
    private val globalBleListener = object : TagBleManager.Listener {
        override fun onReady(device: android.bluetooth.BluetoothDevice) = Unit
        override fun onPacket(data: ByteArray) = Unit
        override fun onError(message: String) = Unit

        override fun onDisconnected() {
            mainHandler.post {
                val recording = TagSession.recordingState == RecordingState.RECEIVING
                val recovering = try {
                    bleManager.isReconnecting
                } catch (_: Exception) {
                    false
                }
                TagLogger.log(
                    LogCategory.BLE,
                    "BLE_DROP_GLOBAL",
                    "recording=$recording recovering=$recovering hadDevice=${TagSession.connectedDevice != null}",
                )
                // Reconnect window (Home/idle only): keep session until give-up.
                // Mid-recording skips reconnect (Option B) — DeviceActivity saves SESSION_LOSS.
                if (recovering) return@post
                // While recording, DeviceActivity saves SESSION_LOSS then clears.
                if (!recording && TagSession.connectedDevice != null) {
                    TagSession.clearConnection()
                    Toast.makeText(
                        this@TagApp,
                        R.string.tag_disconnected_toast,
                        Toast.LENGTH_SHORT,
                    ).show()
                } else if (!recording) {
                    BleUiBridge.notifyConnectionChanged(false)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        TagLogger.init(this)
        TagLogger.log(LogCategory.APP, "APP_START", "Tag mobile started")
        bleManager = TagBleManager(this)
        bleManager.addListener(globalBleListener)
        bleScanner = TagBleScanner(this)
        TagDatabase.get(this)

        SyncFeedback.ensureChannel(this)

        val backend = try {
            if (BuildConfig.TAG_SERVER_URL.isNotBlank() && BuildConfig.TAG_ENROLL_KEY.isNotBlank()) {
                TagLogger.log(LogCategory.APP, "CLOUD_BACKEND", "TagServerCloudBackend ${BuildConfig.TAG_SERVER_URL}")
                TagServerCloudBackend(this)
            } else if (BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()) {
                TagLogger.log(LogCategory.APP, "CLOUD_BACKEND", "SupabaseCloudBackend")
                SupabaseCloudBackend(this)
            } else {
                TagLogger.log(LogCategory.APP, "CLOUD_BACKEND", "StubCloudBackend (no Supabase keys)")
                StubCloudBackend()
            }
        } catch (e: Exception) {
            TagLogger.log(LogCategory.APP, "CLOUD_BACKEND", "StubCloudBackend (${e.message})")
            StubCloudBackend()
        }
        cloudRepository = CloudRepositoryImpl(this, backend)

        UploadSessionWorker.schedulePeriodicWifiRetry(this)
        appScope.launch {
            runCatching { SessionIndexer.backfillFromDisk(this@TagApp) }
                .onFailure {
                    TagLogger.log(LogCategory.APP, "SESSION_INDEX_BACKFILL_FAIL", it.message ?: "error")
                }
            UploadSessionWorker.enqueuePendingOnWifi(this@TagApp)
        }
    }

    fun indexSessionAsync(entry: com.nordic.tagmobile.storage.HistoryEntry) {
        appScope.launch {
            runCatching {
                SessionIndexer.upsertFromHistoryEntry(this@TagApp, entry)

                val onWifi = NetworkProbe.isWifiConnected(this@TagApp)
                TagLogger.log(
                    LogCategory.APP,
                    "CLOUD_AFTER_SAVE",
                    "session=${entry.baseName} ${NetworkProbe.summary(this@TagApp)}",
                )
                if (onWifi && isLargeSession(entry.baseName)) {
                    // Long recording: the foreground worker keeps going with the screen off.
                    UploadSessionWorker.enqueueSession(this@TagApp, entry.baseName, requireWifi = true)
                } else if (onWifi) {
                    // Immediate upload only — do NOT also start WorkManager (race left
                    // status stuck on UPLOADING on Android 9–14). WM enqueued on failure.
                    UploadSessionWorker.cancelForSession(this@TagApp, entry.baseName)
                    uploadSessionNow(entry.baseName)
                } else {
                    UploadSessionWorker.enqueueAfterSave(this@TagApp, entry.baseName)
                    SyncFeedback.notifySessionSavedNeedsSync(
                        this@TagApp,
                        entry.baseName,
                        waitingForWifi = true,
                    )
                }
            }.onFailure {
                TagLogger.log(LogCategory.ERRORS, "CLOUD_AFTER_SAVE_FAIL", it.message ?: "error")
            }
        }
    }

    /** Manual Sync from History / notification — prefers direct upload on Wi‑Fi. */
    fun syncSessionFromUi(sessionId: String) {
        appScope.launch {
            val onWifi = NetworkProbe.isWifiConnected(this@TagApp)
            if (isLargeSession(sessionId)) {
                UploadSessionWorker.enqueueSession(this@TagApp, sessionId)
            } else if (onWifi) {
                UploadSessionWorker.cancelForSession(this@TagApp, sessionId)
                uploadSessionNow(sessionId)
            } else {
                UploadSessionWorker.enqueueAfterSave(this@TagApp, sessionId)
                SyncFeedback.notifySessionSavedNeedsSync(
                    this@TagApp,
                    sessionId,
                    waitingForWifi = true,
                )
            }
        }
    }

    fun syncAllFromUi() {
        appScope.launch {
            val onWifi = NetworkProbe.isWifiConnected(this@TagApp)
            val anyLarge = runCatching {
                TagDatabase.get(this@TagApp).sessionDao().getPendingOrFailed().any { isLargeSession(it.sessionId) }
            }.getOrDefault(false)
            if (anyLarge) {
                UploadSessionWorker.enqueueAll(this@TagApp)
            } else if (onWifi) {
                TagLogger.log(LogCategory.APP, "CLOUD_SYNC_ALL_DIRECT", NetworkProbe.summary(this@TagApp))
                val results = cloudRepository.uploadPendingSessions()
                val ok = results.count { it is UploadResult.Success || it is UploadResult.AlreadySynced }
                val fail = results.count { it is UploadResult.Failed }
                val remaining = try {
                    TagDatabase.get(this@TagApp).sessionDao().getPendingOrFailed().size
                } catch (_: Exception) {
                    fail
                }
                SyncFeedback.notifyUploadFinished(
                    this@TagApp,
                    successCount = ok,
                    failedCount = fail,
                    firstError = results.filterIsInstance<UploadResult.Failed>().firstOrNull()?.message,
                    remainingUnsynced = remaining,
                )
            } else {
                UploadSessionWorker.enqueueAll(this@TagApp)
                SyncFeedback.notifySessionSavedNeedsSync(
                    this@TagApp,
                    "pending",
                    waitingForWifi = true,
                )
            }
        }
    }

    /** Long recordings (several GB) upload through the foreground worker, not the direct path. */
    private suspend fun isLargeSession(sessionId: String): Boolean {
        val folder = TagDatabase.get(this).sessionDao().get(sessionId)?.folderPath
            ?.let { java.io.File(it) }
            ?.takeIf { it.isDirectory }
            ?: com.nordic.tagmobile.storage.RecordingStore.sessionDir(this, sessionId)
        val bytes = folder.listFiles()?.sumOf { it.length() } ?: 0L
        return bytes > LARGE_SESSION_BYTES
    }

    /** Direct upload (not WorkManager). Safe to call from IO scope. */
    private suspend fun uploadSessionNow(sessionId: String) {
        uploadMutex.withLock {
            TagLogger.log(LogCategory.APP, "CLOUD_DIRECT_UPLOAD_START", sessionId)
            val result = cloudRepository.uploadSession(sessionId)
            val remaining = try {
                TagDatabase.get(this).sessionDao().getPendingOrFailed().size
            } catch (_: Exception) {
                0
            }
            when (result) {
                is UploadResult.Success, is UploadResult.AlreadySynced -> {
                    TagLogger.log(
                        LogCategory.APP,
                        "CLOUD_DIRECT_UPLOAD_OK",
                        "session=$sessionId remainingPending=$remaining",
                    )
                    SyncFeedback.notifyUploadFinished(
                        this,
                        successCount = 1,
                        failedCount = 0,
                        remainingUnsynced = remaining,
                        sessionId = sessionId,
                    )
                }
                is UploadResult.Failed -> {
                    TagLogger.log(
                        LogCategory.ERRORS,
                        "CLOUD_DIRECT_UPLOAD_FAIL",
                        "${result.sessionId}: ${result.message}",
                    )
                    SyncFeedback.notifyUploadFinished(
                        this,
                        successCount = 0,
                        failedCount = 1,
                        firstError = result.message,
                        remainingUnsynced = remaining.coerceAtLeast(1),
                        sessionId = sessionId,
                    )
                    UploadSessionWorker.enqueueAfterSave(this, sessionId)
                }
            }
        }
    }

    fun deleteSessionIndexAsync(sessionId: String) {
        appScope.launch {
            runCatching { SessionIndexer.delete(this@TagApp, sessionId) }
        }
    }

    /** Upsert collector to cloud on login/save (optional early sync). */
    fun pushUserAsync(user: com.nordic.tagmobile.model.AppUser) {
        appScope.launch {
            runCatching {
                cloudRepository.ensureSignedIn()
                TagLogger.log(LogCategory.APP, "CLOUD_USER_PUSH_OK", user.id)
            }.onFailure {
                TagLogger.log(LogCategory.APP, "CLOUD_USER_PUSH_FAIL", it.message ?: "error")
            }
        }
    }

    /** Upsert pet to cloud when added/edited. */
    fun pushPetAsync(pet: com.nordic.tagmobile.model.UserProfile) {
        appScope.launch {
            runCatching {
                cloudRepository.upsertPetProfile(pet)
                TagLogger.log(LogCategory.APP, "CLOUD_PET_PUSH_OK", pet.id)
            }.onFailure {
                TagLogger.log(LogCategory.APP, "CLOUD_PET_PUSH_FAIL", it.message ?: "error")
            }
        }
    }

    /** Local Room cleanup when pet deleted (cloud row kept for history). */
    fun deletePetIndexAsync(localPetId: String) {
        appScope.launch {
            runCatching {
                TagDatabase.get(this@TagApp).petDao().get(localPetId) ?: return@runCatching
                // Soft local cleanup only — keep Supabase pet for past sessions.
                TagLogger.log(LogCategory.APP, "CLOUD_PET_LOCAL_DELETE", localPetId)
            }.onFailure {
                TagLogger.log(LogCategory.APP, "CLOUD_PET_DELETE_FAIL", it.message ?: "error")
            }
        }
    }

    companion object {
        /** ~10 min of 720p video. */
        private const val LARGE_SESSION_BYTES = 500L * 1024 * 1024

        lateinit var instance: TagApp
            private set
    }
}
