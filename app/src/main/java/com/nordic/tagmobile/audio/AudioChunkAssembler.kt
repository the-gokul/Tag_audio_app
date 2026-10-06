package com.nordic.tagmobile.audio

import com.nordic.tagmobile.protocol.AudioFragment
import com.nordic.tagmobile.protocol.AudioPacketParser

data class AssembledChunk(
    val packetId: Long,
    val sampleId: Long,
    val pcm: ShortArray,
)

data class AudioAssemblerStats(
    var receivedNotifies: Int = 0,
    var completedChunks: Int = 0,
    var missingPackets: Int = 0,
    var duplicatePackets: Int = 0,
    var invalidPackets: Int = 0,
    var outOfOrderPackets: Int = 0,
    var missingSamples: Int = 0,
    var droppedIncomplete: Int = 0,
)

/**
 * Rebuilds 80-sample logical chunks from ble_2 NUS fragments.
 *
 * Incomplete chunks: dropped (no silence fill).
 * Missing packet IDs: counted, no padding in the WAV.
 * Duplicate complete packet_id: keep first.
 * Late/old packet_id: counted out-of-order and dropped.
 * sample_id mismatch vs packet_id: drop.
 */
class AudioChunkAssembler(
    private val fragmentTimeoutMs: Long = 200L,
    private val onChunk: (AssembledChunk) -> Unit,
) {
    var stats = AudioAssemblerStats()
        private set

    private val inflight = linkedMapOf<Long, Inflight>()
    private val completed = linkedSetOf<Long>()
    private var expectedPacketId: Long? = null
    private var expectedSampleId: Long? = null
    private var started = false

    fun reset() {
        inflight.clear()
        completed.clear()
        expectedPacketId = 0L
        expectedSampleId = 0L
        started = true
        stats = AudioAssemblerStats()
    }

    fun stopAccepting() {
        flushTimeouts(Long.MAX_VALUE)
        started = false
        inflight.clear()
    }

    fun offer(frag: AudioFragment, nowMs: Long) {
        if (!started) return
        stats.receivedNotifies++

        if (frag.sampleOffset + frag.sampleCount > AudioPacketParser.CHUNK_SAMPLES) {
            stats.invalidPackets++
            return
        }

        val pid = frag.packetId
        if (pid in completed) {
            stats.duplicatePackets++
            return
        }

        val exp = expectedPacketId
        if (exp != null) {
            val ahead = seqDelta(exp, pid)
            val behind = seqDelta(pid, exp)
            if (behind in 1L..32L) {
                stats.outOfOrderPackets++
                return
            }
            if (ahead in 1L..1024L) {
                stats.missingPackets += ahead.toInt()
                expectedPacketId = pid
            }
        }

        val wantSid = if (expectedPacketId == pid && expectedSampleId != null) {
            expectedSampleId
        } else {
            sampleIdFor(pid, expectedPacketId, expectedSampleId)
        }
        if (wantSid != null && frag.sampleId != wantSid) {
            stats.invalidPackets++
            return
        }

        val slot = inflight.getOrPut(pid) {
            Inflight(pid, frag.sampleId, nowMs)
        }
        if (slot.sampleId != frag.sampleId) {
            stats.invalidPackets++
            return
        }

        for (i in 0 until frag.sampleCount) {
            val idx = frag.sampleOffset + i
            if (slot.filled[idx]) continue
            slot.pcm[idx] = frag.pcm[i]
            slot.filled[idx] = true
            slot.got++
        }
        slot.lastMs = nowMs

        if (slot.got >= AudioPacketParser.CHUNK_SAMPLES) {
            complete(slot)
        }

        flushTimeouts(nowMs)
        trimCompleted()
    }

    fun tick(nowMs: Long) {
        if (started) flushTimeouts(nowMs)
    }

    private fun complete(slot: Inflight) {
        inflight.remove(slot.packetId)
        if (slot.packetId in completed) {
            stats.duplicatePackets++
            return
        }
        completed.add(slot.packetId)
        stats.completedChunks++
        val nextPid = (slot.packetId + 1L) and 0xFFFFFFFFL
        val nextSid = (slot.sampleId + AudioPacketParser.CHUNK_SAMPLES) and 0xFFFFFFFFL
        expectedPacketId = nextPid
        expectedSampleId = nextSid
        onChunk(AssembledChunk(slot.packetId, slot.sampleId, slot.pcm.copyOf()))
    }

    private fun flushTimeouts(nowMs: Long) {
        val dead = inflight.values.filter { nowMs - it.lastMs >= fragmentTimeoutMs }
        for (slot in dead) {
            inflight.remove(slot.packetId)
            stats.droppedIncomplete++
            stats.missingSamples += (AudioPacketParser.CHUNK_SAMPLES - slot.got).coerceAtLeast(0)
        }
    }

    private fun trimCompleted() {
        while (completed.size > 256) {
            val first = completed.first()
            completed.remove(first)
        }
    }

    private fun sampleIdFor(pid: Long, expPid: Long?, expSid: Long?): Long? {
        if (expPid == null || expSid == null) return if (pid == 0L) 0L else null
        val d = seqDelta(expPid, pid)
        if (d > 1024L) return null
        return (expSid + d * AudioPacketParser.CHUNK_SAMPLES) and 0xFFFFFFFFL
    }

    /** Unsigned 32-bit (to - from) wrapping. */
    private fun seqDelta(from: Long, to: Long): Long =
        (to - from) and 0xFFFFFFFFL

    private class Inflight(
        val packetId: Long,
        val sampleId: Long,
        var lastMs: Long,
    ) {
        val pcm = ShortArray(AudioPacketParser.CHUNK_SAMPLES)
        val filled = BooleanArray(AudioPacketParser.CHUNK_SAMPLES)
        var got = 0
    }
}
