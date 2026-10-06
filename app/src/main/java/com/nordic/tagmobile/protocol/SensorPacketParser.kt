package com.nordic.tagmobile.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class SensorCsvRow(
    val date: String,
    val time: String,
    val deviceId: String,
    val packetNo: Long,
    val sampleNumber: Long,
    val flags: Int,
    val timestampMs: Long,
    val accelX: Int,
    val accelY: Int,
    val accelZ: Int,
    val gyroX: Int,
    val gyroY: Int,
    val gyroZ: Int,
    val humidityX100: Int,
    val envTempX100: Int,
    val bodyTempX100: Int,
)

data class ParsedPacket(
    val packetId: Long,
    val deviceId: String,
    val rows: List<SensorCsvRow>,
)

object SensorPacketParser {
    const val START_BYTE = 0xA1
    const val STOP_BYTE = 0x5A
    const val VERSION = 8
    const val HEADER_SIZE = 18
    const val SAMPLE_WIRE_SIZE = 21
    const val MAX_SAMPLES = 10

    private const val ST_NA = 0
    private const val ST_OK = 1
    private const val ST_MISS = 2
    private const val ST_NC = 3

    const val BMI_SHIFT = 0
    const val BME_SHIFT = 2
    const val TMP_SHIFT = 4

    fun statusCode(flags: Int, shift: Int): Int =
        (flags shr shift) and 0x3

    fun statusOk(flags: Int, shift: Int): Boolean =
        statusCode(flags, shift) == ST_OK

    fun statusStr(flags: Int, shift: Int): String =
        when (statusCode(flags, shift)) {
            ST_OK -> "OK"
            ST_MISS -> "MISS"
            ST_NC -> "NC"
            else -> "Nil"
        }

    fun bmiOk(flags: Int) = statusOk(flags, BMI_SHIFT)
    fun bmeOk(flags: Int) = statusOk(flags, BME_SHIFT)
    fun tmpOk(flags: Int) = statusOk(flags, TMP_SHIFT)

    fun parsePacket(
        data: ByteArray,
        syncBaseUnixMs: Long,
        tagUptimeAtSync: Long?,
    ): ParsedPacket? {
        if (data.size < HEADER_SIZE + SAMPLE_WIRE_SIZE + 1) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val start = buf.get().toInt() and 0xFF
        if (start != START_BYTE) return null
        val version = buf.get().toInt() and 0xFF
        if (version != VERSION) return null
        val serial = buf.int.toLong() and 0xFFFFFFFFL
        val deviceId = "Tag_%08x".format(serial)
        val packetId = buf.int.toLong() and 0xFFFFFFFFL
        val firstSampleNumber = buf.int.toLong() and 0xFFFFFFFFL
        val baseTimestampMs = buf.int.toLong() and 0xFFFFFFFFL

        if ((data[data.size - 1].toInt() and 0xFF) != STOP_BYTE) return null

        val sampleCount = (data.size - HEADER_SIZE - 1) / SAMPLE_WIRE_SIZE
        if (sampleCount < 1 || sampleCount > MAX_SAMPLES) return null

        val rows = ArrayList<SensorCsvRow>(sampleCount)
        for (i in 0 until sampleCount) {
            val flags = buf.get().toInt() and 0xFF
            val accelX = buf.short.toInt()
            val accelY = buf.short.toInt()
            val accelZ = buf.short.toInt()
            val gyroX = buf.short.toInt()
            val gyroY = buf.short.toInt()
            val gyroZ = buf.short.toInt()
            val humidity = buf.short.toInt() and 0xFFFF
            val envTemp = buf.short.toInt()
            val bodyTemp = buf.short.toInt()
            val deltaMs = buf.short.toInt() and 0xFFFF

            val rawTimestampMs = baseTimestampMs + deltaMs
            val relativeMs = if (tagUptimeAtSync != null) {
                rawTimestampMs - tagUptimeAtSync
            } else {
                rawTimestampMs
            }
            val absMs = syncBaseUnixMs + relativeMs
            rows.add(
                SensorCsvRow(
                    date = CsvExporter.formatDate(absMs),
                    time = CsvExporter.formatTime(absMs),
                    deviceId = deviceId,
                    packetNo = packetId,
                    sampleNumber = firstSampleNumber + i,
                    flags = flags,
                    timestampMs = relativeMs,
                    accelX = accelX,
                    accelY = accelY,
                    accelZ = accelZ,
                    gyroX = gyroX,
                    gyroY = gyroY,
                    gyroZ = gyroZ,
                    humidityX100 = humidity,
                    envTempX100 = envTemp,
                    bodyTempX100 = bodyTemp,
                ),
            )
        }
        return ParsedPacket(packetId = packetId, deviceId = deviceId, rows = rows)
    }
}
