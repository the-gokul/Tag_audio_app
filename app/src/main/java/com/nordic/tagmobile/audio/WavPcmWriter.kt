package com.nordic.tagmobile.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Streaming 16-bit PCM WAV (header patched on [finish]). */
class WavPcmWriter(
    private val file: File,
    private val sampleRate: Int,
    private val channels: Int,
    private val bitsPerSample: Int,
) {
    private var raf: RandomAccessFile? = null
    private var dataBytes = 0L

    fun open() {
        file.parentFile?.mkdirs()
        val f = RandomAccessFile(file, "rw")
        raf = f
        f.setLength(0)
        f.write(ByteArray(44))
        dataBytes = 0L
    }

    fun writePcmLe(samples: ShortArray) {
        val f = raf ?: return
        val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) buf.putShort(s)
        val bytes = buf.array()
        f.write(bytes)
        dataBytes += bytes.size
    }

    fun finish() {
        val f = raf ?: return
        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        val blockAlign = (channels * (bitsPerSample / 8)).toShort()
        val dataSize = dataBytes.toInt()
        val riffSize = 36 + dataSize
        f.seek(0)
        f.write("RIFF".toByteArray(Charsets.US_ASCII))
        f.write(leInt(riffSize))
        f.write("WAVE".toByteArray(Charsets.US_ASCII))
        f.write("fmt ".toByteArray(Charsets.US_ASCII))
        f.write(leInt(16))
        f.write(leShort(1)) // PCM
        f.write(leShort(channels.toShort()))
        f.write(leInt(sampleRate))
        f.write(leInt(byteRate))
        f.write(leShort(blockAlign))
        f.write(leShort(bitsPerSample.toShort()))
        f.write("data".toByteArray(Charsets.US_ASCII))
        f.write(leInt(dataSize))
        f.close()
        raf = null
    }

    fun abort() {
        try {
            raf?.close()
        } catch (_: Exception) {
        }
        raf = null
    }

    private fun leInt(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    private fun leShort(v: Short): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v).array()
}
