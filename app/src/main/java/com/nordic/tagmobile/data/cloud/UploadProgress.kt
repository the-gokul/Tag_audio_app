package com.nordic.tagmobile.data.cloud

import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Live upload progress per session, reported by the cloud backend.
 * History observes [state] for the row text; [com.nordic.tagmobile.work.UploadSessionWorker]
 * listens to keep its notification current.
 */
object UploadProgress {
    enum class Phase { PREPARING, UPLOADING }

    data class Snapshot(
        val sessionId: String,
        val phase: Phase,
        val sentBytes: Long,
        val totalBytes: Long,
        /** Smoothed upload speed; 0 until measured. */
        val bytesPerSecond: Double,
    ) {
        val percent: Int get() = if (totalBytes > 0) (sentBytes * 100 / totalBytes).toInt() else 0

        /** Estimated seconds left, or null while the speed is still unknown. */
        val secondsLeft: Long?
            get() = if (bytesPerSecond > 1.0) ((totalBytes - sentBytes) / bytesPerSecond).toLong() else null

        /** e.g. "42% · 2.1 GB of 5.0 GB · 3.4 MB/s · about 14 min left" */
        fun describe(): String = when (phase) {
            Phase.PREPARING -> "Preparing upload…"
            Phase.UPLOADING -> buildString {
                append("$percent% · ${NetworkProbe.formatBytes(sentBytes)} of ${NetworkProbe.formatBytes(totalBytes)}")
                if (bytesPerSecond > 1.0) append(" · ${NetworkProbe.formatBytes(bytesPerSecond.toLong())}/s")
                secondsLeft?.let { append(" · ${formatTimeLeft(it)}") }
            }
        }
    }

    fun interface Listener {
        fun onProgress(snapshot: Snapshot)
    }

    private val _state = MutableStateFlow<Map<String, Snapshot>>(emptyMap())
    val state: StateFlow<Map<String, Snapshot>> = _state

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val lastSample = HashMap<String, Pair<Long, Long>>() // sessionId → (elapsedMs, bytes)

    fun add(listener: Listener) {
        listeners.add(listener)
    }

    fun remove(listener: Listener) {
        listeners.remove(listener)
    }

    /** Before bytes flow (hashing a large video can take a while). */
    fun preparing(sessionId: String, totalBytes: Long) {
        publish(Snapshot(sessionId, Phase.PREPARING, 0, totalBytes, 0.0))
    }

    fun report(sessionId: String, sentBytes: Long, totalBytes: Long) {
        val now = SystemClock.elapsedRealtime()
        val previous = _state.value[sessionId]
        var speed = previous?.bytesPerSecond ?: 0.0
        synchronized(lastSample) {
            val last = lastSample[sessionId]
            if (last != null && now > last.first && sentBytes > last.second) {
                val instant = (sentBytes - last.second) * 1000.0 / (now - last.first)
                // Exponential smoothing so one slow chunk does not swing the estimate.
                speed = if (speed <= 0.0) instant else speed * 0.8 + instant * 0.2
            }
            lastSample[sessionId] = now to sentBytes
        }
        publish(Snapshot(sessionId, Phase.UPLOADING, sentBytes, totalBytes, speed))
    }

    fun finished(sessionId: String) {
        synchronized(lastSample) { lastSample.remove(sessionId) }
        _state.update { it - sessionId }
    }

    private fun publish(snapshot: Snapshot) {
        _state.update { it + (snapshot.sessionId to snapshot) }
        listeners.forEach { it.onProgress(snapshot) }
    }

    fun formatTimeLeft(seconds: Long): String = when {
        seconds < 60 -> "less than a minute left"
        seconds < 3600 -> "about ${(seconds + 59) / 60} min left"
        else -> {
            val minutes = (seconds + 59) / 60
            "about ${minutes / 60} h ${minutes % 60} min left"
        }
    }
}
