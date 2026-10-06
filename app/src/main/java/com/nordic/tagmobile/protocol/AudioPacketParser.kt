package com.nordic.tagmobile.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class AudioFragment(
    val packetId: Long,
    val sampleId: Long,
    val sampleOffset: Int,
    val sampleCount: Int,
    val pcm: ShortArray,
)

object AudioPacketParser {
    const val HDR_SIZE = 10
    const val CHUNK_SAMPLES = 80
    const val CHUNK_PCM_BYTES = 160

    /**
     * Valid ble_2 audio notify: header + PCM, not an ASCII control line.
     */
    fun parse(data: ByteArray): AudioFragment? {
        if (data.size < HDR_SIZE + 2) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val packetId = buf.int.toLong() and 0xFFFFFFFFL
        val sampleId = buf.int.toLong() and 0xFFFFFFFFL
        val offset = buf.get().toInt() and 0xFF
        val count = buf.get().toInt() and 0xFF
        if (count !in 1..CHUNK_SAMPLES) return null
        if (offset > CHUNK_SAMPLES - 1) return null
        if (offset + count > CHUNK_SAMPLES) return null
        if (data.size != HDR_SIZE + count * 2) return null
        val pcm = ShortArray(count)
        for (i in 0 until count) {
            pcm[i] = buf.short
        }
        return AudioFragment(packetId, sampleId, offset, count, pcm)
    }

    fun looksLikeAudio(data: ByteArray): Boolean = parse(data) != null
}
