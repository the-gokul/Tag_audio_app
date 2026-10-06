package com.nordic.tagmobile.storage

import android.content.Context
import com.nordic.tagmobile.log.LogCategory
import com.nordic.tagmobile.log.TagLogger
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class HistoryEntry(
    /** Session id (SESSION-…) for new recordings, or legacy flat base name. */
    val baseName: String,
    val dataFile: File,
    val logFile: File,
    val videoFile: File?,
    val audioFile: File? = null,
    val packetCount: Int,
    val sampleCount: Int,
    val status: String,
    val savedAtMs: Long,
    val galleryUri: String? = null,
    /** Non-null for new session-folder packages. */
    val sessionDir: File? = null,
)

/**
 * Local recording storage.
 *
 * New sessions (cloud-friendly, batch upload after recording):
 * ```
 * files/sessions/SESSION-yyyyMMdd-HHmmss-xxxx/
 *   ├── SESSION-yyyyMMdd-HHmmss-xxxx.xlsx
 *   ├── SESSION-yyyyMMdd-HHmmss-xxxx.mp4
 *   ├── SESSION-yyyyMMdd-HHmmss-xxxx.log
 *   └── manifest.json
 * ```
 *
 * Legacy flat layout (`data/`, `logs/`, `videos/`) and older fixed names
 * (`data.xlsx` / `video.mp4` / `session.log`) are still readable in History.
 */
object RecordingStore {
    const val FILE_MANIFEST = "manifest.json"
    /** @deprecated Old fixed names; kept for reading sessions saved before rename. */
    private const val LEGACY_FILE_DATA = "data.xlsx"
    private const val LEGACY_FILE_VIDEO = "video.mp4"
    private const val LEGACY_FILE_LOG = "session.log"

    private const val META_SUFFIX = ".meta.json"

    fun sessionsRoot(context: Context): File =
        File(context.filesDir, "sessions").also { it.mkdirs() }

    private fun dataDir(context: Context): File =
        File(context.filesDir, "data").also { it.mkdirs() }

    private fun logsDir(context: Context): File =
        File(context.filesDir, "logs").also { it.mkdirs() }

    private fun videosDir(context: Context): File =
        File(context.filesDir, "videos").also { it.mkdirs() }

    /** New cloud-style session id: SESSION-20260910-174530-a1b2 */
    fun makeSessionId(atMs: Long = System.currentTimeMillis()): String {
        val fmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        val short = UUID.randomUUID().toString().replace("-", "").take(4)
        return "SESSION-${fmt.format(Date(atMs))}-$short"
    }

    fun dataFileName(sessionId: String): String = "$sessionId.xlsx"
    fun videoFileName(sessionId: String): String = "$sessionId.mp4"
    fun audioFileName(sessionId: String): String = "$sessionId.wav"
    fun logFileName(sessionId: String): String = "$sessionId.log"

    /** @deprecated Prefer [makeSessionId] for new recordings. Kept for legacy callers. */
    fun makeBaseName(
        deviceName: String,
        atMs: Long = System.currentTimeMillis(),
        profilePrefix: String = "",
    ): String {
        val safe = deviceName.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "Tag" }
        val fmt = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        val datePart = fmt.format(Date(atMs))
        return if (profilePrefix.isNotBlank()) "${profilePrefix}_${safe}_${datePart}"
        else "${safe}_${datePart}"
    }

    fun sessionDir(context: Context, sessionId: String): File =
        File(sessionsRoot(context), sessionId)

    fun createSessionDir(context: Context, sessionId: String): File =
        sessionDir(context, sessionId).also { it.mkdirs() }

    fun sessionDataFile(sessionDir: File, sessionId: String = sessionDir.name): File =
        File(sessionDir, dataFileName(sessionId))

    fun sessionVideoFile(sessionDir: File, sessionId: String = sessionDir.name): File =
        File(sessionDir, videoFileName(sessionId))

    fun sessionAudioFile(sessionDir: File, sessionId: String = sessionDir.name): File =
        File(sessionDir, audioFileName(sessionId))

    fun sessionLogFile(sessionDir: File, sessionId: String = sessionDir.name): File =
        File(sessionDir, logFileName(sessionId))

    fun sessionManifestFile(sessionDir: File): File = File(sessionDir, FILE_MANIFEST)

    /** Resolve data file in a session dir (new name, then legacy fixed name). */
    fun resolveSessionDataFile(sessionDir: File): File? {
        val id = sessionDir.name
        val primary = sessionDataFile(sessionDir, id)
        if (primary.exists()) return primary
        val legacy = File(sessionDir, LEGACY_FILE_DATA)
        if (legacy.exists()) return legacy
        return null
    }

    fun resolveSessionVideoFile(sessionDir: File): File? {
        val id = sessionDir.name
        val primary = sessionVideoFile(sessionDir, id)
        if (primary.exists()) return primary
        val legacy = File(sessionDir, LEGACY_FILE_VIDEO)
        if (legacy.exists()) return legacy
        return null
    }

    fun resolveSessionAudioFile(sessionDir: File): File? {
        val wav = sessionAudioFile(sessionDir)
        return wav.takeIf { it.exists() }
    }

    fun resolveSessionLogFile(sessionDir: File): File {
        val id = sessionDir.name
        val primary = sessionLogFile(sessionDir, id)
        if (primary.exists()) return primary
        val legacy = File(sessionDir, LEGACY_FILE_LOG)
        if (legacy.exists()) return legacy
        return primary
    }

    /** Prefer session package path; fall back to legacy flat `data/`. */
    fun dataFile(context: Context, sessionIdOrBase: String): File {
        val session = sessionDir(context, sessionIdOrBase)
        if (session.isDirectory || sessionIdOrBase.startsWith("SESSION-")) {
            return sessionDataFile(createSessionDir(context, sessionIdOrBase), sessionIdOrBase)
        }
        return File(dataDir(context), "$sessionIdOrBase.xlsx")
    }

    fun findDataFile(context: Context, baseName: String): File? {
        val session = sessionDir(context, baseName)
        resolveSessionDataFile(session)?.let { return it }
        val xlsx = File(dataDir(context), "$baseName.xlsx")
        if (xlsx.exists()) return xlsx
        val csv = File(dataDir(context), "$baseName.csv")
        if (csv.exists()) return csv
        return null
    }

    fun logFile(context: Context, baseName: String): File {
        val session = sessionDir(context, baseName)
        if (session.isDirectory || baseName.startsWith("SESSION-")) {
            return sessionLogFile(createSessionDir(context, baseName), baseName)
        }
        return File(logsDir(context), "$baseName.log")
    }

    fun findVideoFile(context: Context, baseName: String): File? {
        resolveSessionVideoFile(sessionDir(context, baseName))?.let { return it }
        val dir = videosDir(context)
        val mp4 = File(dir, "$baseName.mp4")
        if (mp4.exists()) return mp4
        val webm = File(dir, "$baseName.webm")
        if (webm.exists()) return webm
        return null
    }

    /** Newest recorded video across session packages and legacy `videos/`. */
    fun latestVideoFile(context: Context): File? {
        val sessionVideos = sessionsRoot(context).listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { resolveSessionVideoFile(it) }
            ?.filter { it.isFile && it.length() > 0L }
            .orEmpty()
        val legacyVideos = videosDir(context).listFiles()
            ?.filter {
                it.isFile &&
                    (it.extension.equals("mp4", true) || it.extension.equals("webm", true)) &&
                    it.length() > 0L
            }
            .orEmpty()
        return (sessionVideos + legacyVideos).maxByOrNull { it.lastModified() }
    }

    private fun legacyMetaFile(context: Context, baseName: String): File =
        File(dataDir(context), "$baseName$META_SUFFIX")

    /**
     * Finalize a session package: write log + full [manifest.json].
     * Caller must already have written session-id `.xlsx` / `.mp4` into the session dir.
     */
    fun saveRecording(
        context: Context,
        baseName: String,
        logContent: String,
        packetCount: Int,
        sampleCount: Int,
        status: String,
        galleryUri: String? = null,
        manifest: SessionManifestData? = null,
    ): HistoryEntry {
        val savedAt = System.currentTimeMillis()
        val session = sessionDir(context, baseName)
        val isSessionPackage = baseName.startsWith("SESSION-") || session.isDirectory

        if (isSessionPackage) {
            session.mkdirs()
            val dataF = sessionDataFile(session, baseName)
            val logF = sessionLogFile(session, baseName)
            val vidF = resolveSessionVideoFile(session)
                ?: sessionVideoFile(session, baseName).takeIf { it.exists() }
            val audioF = resolveSessionAudioFile(session)
            // Prefer writing the new session-id log name
            logF.writeText(logContent, Charsets.UTF_8)
            val dataExists = dataF.exists() || resolveSessionDataFile(session) != null
            val videoExists = vidF != null
            val audioExists = audioF != null
            val full = (manifest ?: SessionManifestData(
                sessionId = baseName,
                packetCount = packetCount,
                sampleCount = sampleCount,
                parseFailures = 0,
                statusDetail = status,
                hasPossibleLoss = false,
                localUserId = "",
                userName = "",
                localPetId = "",
                petName = "",
                animalType = "",
                breed = "",
                sex = "",
                age = "",
                weightKg = "",
                deviceId = "",
                deviceAddress = "",
                startTimeMs = savedAt,
                endTimeMs = savedAt,
                videoWidth = null,
                videoHeight = null,
                videoFps = null,
                orientationHint = null,
                videoOrientationLabel = null,
                sensorSamplePeriodMs = null,
                sensorSamplesPerPacket = null,
                phoneStartTimestampMs = savedAt,
                collarUptimeAtSyncMs = null,
                hasData = dataExists,
                hasVideo = videoExists,
                galleryUri = galleryUri,
                appVersionName = "unknown",
                appVersionCode = 0,
                savedAtMs = savedAt,
            )).copy(
                sessionId = baseName,
                hasData = dataExists,
                hasVideo = videoExists,
                hasAudio = audioExists,
                galleryUri = galleryUri ?: manifest?.galleryUri,
                savedAtMs = savedAt,
            )
            full.writeTo(session)
            val resolvedData = resolveSessionDataFile(session) ?: dataF
            TagLogger.log(
                LogCategory.FILE,
                "AUTO_SAVE_OK",
                "session=$baseName data=${resolvedData.name} video=${vidF?.name ?: "none"} audio=${audioF?.name ?: "none"} quality=${full.qualityLabel}",
            )
            return HistoryEntry(
                baseName = baseName,
                dataFile = resolvedData,
                logFile = logF,
                videoFile = vidF,
                audioFile = audioF,
                packetCount = packetCount,
                sampleCount = sampleCount,
                status = full.qualityLabel,
                savedAtMs = savedAt,
                galleryUri = galleryUri,
                sessionDir = session,
            )
        }

        // Legacy flat layout
        val dataF = findDataFile(context, baseName) ?: dataFile(context, baseName)
        val logF = File(logsDir(context), "$baseName.log")
        val vidF = findVideoFile(context, baseName)
        logF.writeText(logContent, Charsets.UTF_8)
        legacyMetaFile(context, baseName).writeText(
            JSONObject()
                .put("baseName", baseName)
                .put("packetCount", packetCount)
                .put("sampleCount", sampleCount)
                .put("status", status)
                .put("savedAtMs", savedAt)
                .put("galleryUri", galleryUri ?: JSONObject.NULL)
                .toString(),
            Charsets.UTF_8,
        )
        TagLogger.log(
            LogCategory.FILE,
            "AUTO_SAVE_OK",
            "data=${dataF.name} log=${logF.name} packets=$packetCount samples=$sampleCount",
        )
        return HistoryEntry(
            baseName = baseName,
            dataFile = dataF,
            logFile = logF,
            videoFile = vidF,
            packetCount = packetCount,
            sampleCount = sampleCount,
            status = status,
            savedAtMs = savedAt,
            galleryUri = galleryUri,
            sessionDir = null,
        )
    }

    fun updateGalleryUri(context: Context, baseName: String, galleryUri: String?) {
        val session = sessionDir(context, baseName)
        val manifest = sessionManifestFile(session)
        if (manifest.exists()) {
            try {
                val o = JSONObject(manifest.readText(Charsets.UTF_8))
                o.put("gallery_uri", galleryUri ?: JSONObject.NULL)
                o.put("galleryUri", galleryUri ?: JSONObject.NULL)
                manifest.writeText(o.toString(2), Charsets.UTF_8)
            } catch (_: Exception) {
            }
            return
        }
        val meta = legacyMetaFile(context, baseName)
        if (!meta.exists()) return
        try {
            val o = JSONObject(meta.readText(Charsets.UTF_8))
            o.put("galleryUri", galleryUri ?: JSONObject.NULL)
            meta.writeText(o.toString(), Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    fun listHistory(context: Context): List<HistoryEntry> {
        val sessions = listSessionEntries(context)
        val legacy = listLegacyEntries(context)
        val sessionIds = sessions.map { it.baseName }.toSet()
        // Avoid duplicates if a legacy file shares a name (unlikely with SESSION- prefix)
        val merged = sessions + legacy.filter { it.baseName !in sessionIds }
        return merged.sortedByDescending { it.savedAtMs }
    }

    private fun listSessionEntries(context: Context): List<HistoryEntry> {
        val root = sessionsRoot(context)
        val dirs = root.listFiles()?.filter { it.isDirectory && it.name.startsWith("SESSION-") }
            ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val dataF = resolveSessionDataFile(dir)
            val vidF = resolveSessionVideoFile(dir)
            val audioF = resolveSessionAudioFile(dir)
            if (dataF == null && vidF == null && audioF == null) return@mapNotNull null
            val logF = resolveSessionLogFile(dir)
            val manifest = sessionManifestFile(dir)
            var galleryUri: String? = null
            val meta = if (manifest.exists()) {
                try {
                    val o = JSONObject(manifest.readText(Charsets.UTF_8))
                    galleryUri = o.optString("gallery_uri")
                        .takeIf { it.isNotBlank() && it != "null" }
                        ?: o.optString("galleryUri").takeIf { it.isNotBlank() && it != "null" }
                    val quality = o.optJSONObject("quality")
                    val packets = quality?.optInt("packet_count")
                        ?: o.optInt("packetCount", 0)
                    val samples = quality?.optInt("sample_count")
                        ?: o.optInt("sampleCount", 0)
                    val status = quality?.optString("status")
                        ?.takeIf { it.isNotBlank() }
                        ?: o.optString("status", "Saved")
                    Meta(
                        packets,
                        samples,
                        status,
                        o.optLong(
                            "saved_at_ms",
                            o.optLong(
                                "savedAtMs",
                                dataF?.lastModified() ?: dir.lastModified(),
                            ),
                        ),
                    )
                } catch (_: Exception) {
                    Meta(0, 0, "Saved", dir.lastModified())
                }
            } else {
                Meta(0, 0, "Saved", dir.lastModified())
            }
            HistoryEntry(
                baseName = dir.name,
                dataFile = dataF ?: sessionDataFile(dir),
                logFile = logF,
                videoFile = vidF,
                audioFile = audioF,
                packetCount = meta.packets,
                sampleCount = meta.samples,
                status = meta.status,
                savedAtMs = meta.savedAt,
                galleryUri = galleryUri,
                sessionDir = dir,
            )
        }
    }

    private fun listLegacyEntries(context: Context): List<HistoryEntry> {
        val dataRoot = dataDir(context)
        val logsRoot = logsDir(context)
        val bases = dataRoot.listFiles()
            ?.filter { it.isFile && (it.name.endsWith(".xlsx") || it.name.endsWith(".csv")) }
            ?.map {
                when {
                    it.name.endsWith(".xlsx") -> it.name.removeSuffix(".xlsx")
                    else -> it.name.removeSuffix(".csv")
                }
            }
            ?.distinct()
            ?: emptyList()

        return bases.mapNotNull { base ->
            val dataF = findDataFile(context, base) ?: return@mapNotNull null
            // Skip if this path is actually inside sessions (findDataFile prefers session)
            if (dataF.parentFile?.name?.startsWith("SESSION-") == true) return@mapNotNull null
            val logF = File(logsRoot, "$base.log")
            val metaFile = legacyMetaFile(context, base)
            val vidF = run {
                val dir = videosDir(context)
                val mp4 = File(dir, "$base.mp4")
                when {
                    mp4.exists() -> mp4
                    File(dir, "$base.webm").exists() -> File(dir, "$base.webm")
                    else -> null
                }
            }
            var galleryUri: String? = null
            val (packets, samples, status, savedAt) = if (metaFile.exists()) {
                try {
                    val o = JSONObject(metaFile.readText(Charsets.UTF_8))
                    galleryUri = o.optString("galleryUri").takeIf { it.isNotBlank() && it != "null" }
                    Meta(
                        o.optInt("packetCount", 0),
                        o.optInt("sampleCount", 0),
                        o.optString("status", "Saved"),
                        o.optLong("savedAtMs", dataF.lastModified()),
                    )
                } catch (_: Exception) {
                    Meta(0, 0, "Saved", dataF.lastModified())
                }
            } else {
                Meta(0, 0, "Saved", dataF.lastModified())
            }
            HistoryEntry(
                baseName = base,
                dataFile = dataF,
                logFile = logF,
                videoFile = vidF,
                packetCount = packets,
                sampleCount = samples,
                status = status,
                savedAtMs = savedAt,
                galleryUri = galleryUri,
                sessionDir = null,
            )
        }
    }

    private data class Meta(
        val packets: Int,
        val samples: Int,
        val status: String,
        val savedAt: Long,
    )

    fun deleteEntry(context: Context, entry: HistoryEntry) {
        val galleryNames = linkedSetOf(
            entry.videoFile?.name,
            "${entry.baseName}.mp4",
            LEGACY_FILE_VIDEO,
            "${entry.baseName}.webm",
            "${entry.baseName}_burn.mp4",
        ).filterNotNull().toMutableSet()

        GalleryPublisher.deletePublished(
            context = context,
            displayName = null,
            galleryUri = entry.galleryUri,
        )
        galleryNames.forEach { GalleryPublisher.deleteByDisplayName(context, it) }

        val session = entry.sessionDir ?: sessionDir(context, entry.baseName).takeIf { it.isDirectory }
        if (session != null && session.isDirectory) {
            session.listFiles()?.forEach { it.delete() }
            session.delete()
            TagLogger.log(LogCategory.FILE, "DELETE_PERMANENT", "session=${entry.baseName}")
            (context.applicationContext as? com.nordic.tagmobile.TagApp)
                ?.deleteSessionIndexAsync(entry.baseName)
            return
        }

        entry.dataFile.delete()
        File(dataDir(context), "${entry.baseName}.xlsx").delete()
        File(dataDir(context), "${entry.baseName}.csv").delete()
        entry.logFile.delete()
        entry.videoFile?.delete()
        File(videosDir(context), "${entry.baseName}.mp4").delete()
        File(videosDir(context), "${entry.baseName}.webm").delete()
        File(videosDir(context), "${entry.baseName}_burn.mp4").delete()
        legacyMetaFile(context, entry.baseName).delete()
        TagLogger.log(LogCategory.FILE, "DELETE_PERMANENT", entry.baseName)
        (context.applicationContext as? com.nordic.tagmobile.TagApp)
            ?.deleteSessionIndexAsync(entry.baseName)
    }
}
