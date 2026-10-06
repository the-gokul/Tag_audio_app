package com.nordic.tagmobile.protocol

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object CsvExporter {
    private const val HEADER =
        "date,time,device_id,packet_no,sample_no,flags,timestamp_ms," +
            "r_accel_x,r_accel_y,r_accel_z,r_gyro_x,r_gyro_y,r_gyro_z," +
            "r_humidity_x100,r_env_temp_x100,r_body_temp_x100," +
            "accel_x,accel_y,accel_z,gyro_x,gyro_y,gyro_z," +
            "humidity_x100,env_temp_x100,body_temp_x100"

    fun header(): String = HEADER

    /**
     * Always raw + SI.
     * OK → numbers; Nil / MISS / NC → status text (matches tag SENSOR_STR_* / DK).
     */
    fun row(row: SensorCsvRow): String {
        val f = row.flags
        val bmi = SensorPacketParser.statusStr(f, SensorPacketParser.BMI_SHIFT)
        val bme = SensorPacketParser.statusStr(f, SensorPacketParser.BME_SHIFT)
        val tmp = SensorPacketParser.statusStr(f, SensorPacketParser.TMP_SHIFT)
        val bmiOk = SensorPacketParser.bmiOk(f)
        val bmeOk = SensorPacketParser.bmeOk(f)
        val tmpOk = SensorPacketParser.tmpOk(f)

        fun rawI(ok: Boolean, v: Int, st: String) = if (ok) v.toString() else st
        fun si3(ok: Boolean, v: Int, st: String) =
            if (ok) String.format(Locale.US, "%.2f", v / 1000.0) else st
        fun siC(ok: Boolean, v: Int, st: String) =
            if (ok) String.format(Locale.US, "%.2f", v / 100.0) else st

        return listOf(
            row.date,
            row.time,
            row.deviceId,
            row.packetNo.toString(),
            row.sampleNumber.toString(),
            "0x%02X".format(row.flags),
            row.timestampMs.toString(),
            rawI(bmiOk, row.accelX, bmi),
            rawI(bmiOk, row.accelY, bmi),
            rawI(bmiOk, row.accelZ, bmi),
            rawI(bmiOk, row.gyroX, bmi),
            rawI(bmiOk, row.gyroY, bmi),
            rawI(bmiOk, row.gyroZ, bmi),
            rawI(bmeOk, row.humidityX100, bme),
            rawI(bmeOk, row.envTempX100, bme),
            rawI(tmpOk, row.bodyTempX100, tmp),
            si3(bmiOk, row.accelX, bmi),
            si3(bmiOk, row.accelY, bmi),
            si3(bmiOk, row.accelZ, bmi),
            si3(bmiOk, row.gyroX, bmi),
            si3(bmiOk, row.gyroY, bmi),
            si3(bmiOk, row.gyroZ, bmi),
            siC(bmeOk, row.humidityX100, bme),
            siC(bmeOk, row.envTempX100, bme),
            siC(tmpOk, row.bodyTempX100, tmp),
        ).joinToString(",")
    }

    fun build(rows: List<SensorCsvRow>): String {
        val lines = ArrayList<String>(rows.size + 1)
        lines.add(header())
        rows.forEach { lines.add(row(it)) }
        return lines.joinToString("\n")
    }

    fun formatDate(epochMs: Long): String {
        val fmt = SimpleDateFormat("dd-MM-yyyy", Locale.US)
        fmt.timeZone = TimeZone.getDefault()
        return fmt.format(Date(epochMs))
    }

    fun formatTime(epochMs: Long): String {
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        fmt.timeZone = TimeZone.getDefault()
        return fmt.format(Date(epochMs))
    }

    fun formatDateTime(epochMs: Long): String =
        "${formatDate(epochMs)} ${formatTime(epochMs)}"

    fun formatFileSize(bytes: Int): String =
        when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
        }
}
