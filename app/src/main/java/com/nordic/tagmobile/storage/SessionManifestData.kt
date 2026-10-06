package com.nordic.tagmobile.storage

import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Full session [manifest.json] payload (schema_version 2).
 * Unknown fields (battery, firmware, measured offset) are written as JSON null.
 */
data class SessionManifestData(
    val sessionId: String,
    val packetCount: Int,
    val sampleCount: Int,
    val parseFailures: Int,
    val statusDetail: String,
    val hasPossibleLoss: Boolean,
    val terminationReason: String = "USER_STOP",
    // user
    val localUserId: String,
    val userName: String,
    val userPhone: String = "",
    // pet
    val localPetId: String,
    val petName: String,
    val animalType: String,
    val breed: String,
    val sex: String,
    val age: String,
    val weightKg: String,
    // device
    val deviceId: String,
    val deviceAddress: String,
    val hardwareVersion: String? = null,
    val firmwareVersion: String? = null,
    // recording
    val startTimeMs: Long,
    val endTimeMs: Long,
    val videoWidth: Int?,
    val videoHeight: Int?,
    val videoFps: Int?,
    val orientationHint: Int?,
    val videoOrientationLabel: String?,
    val sensorSamplePeriodMs: Int?,
    val sensorSamplesPerPacket: Int?,
    // sync
    val phoneStartTimestampMs: Long,
    val collarUptimeAtSyncMs: Long?,
    val offsetMs: Long? = null,
    val syncMethod: String = "phone_unix_plus_relative_uptime",
    // battery (not available from GATT yet)
    val batteryStartPercent: Int? = null,
    val batteryEndPercent: Int? = null,
    // files / gallery / app
    val hasData: Boolean,
    val hasVideo: Boolean,
    val hasAudio: Boolean = false,
    val galleryUri: String? = null,
    val appVersionName: String,
    val appVersionCode: Int,
    val savedAtMs: Long = System.currentTimeMillis(),
    /** GOOD | WARNING | FAILED | SESSION_LOSS */
    val qualityLabel: String = "GOOD",
    val missingSamplePercent: Double = 0.0,
) {
    fun toJson(): JSONObject {
        val iso = isoFormatter()
        val durationSec = ((endTimeMs - startTimeMs).coerceAtLeast(0L) / 1000L).toInt()

        val user = JSONObject()
            .put("local_user_id", localUserId.ifBlank { JSONObject.NULL })
            .put("name", userName)
            .put("phone", userPhone.ifBlank { JSONObject.NULL })

        val pet = JSONObject()
            .put("local_pet_id", localPetId.ifBlank { JSONObject.NULL })
            .put("name", petName)
            .put("animal_type", animalType)
            .put("breed", breed)
            .put("sex", sex)
            .put("age", age)
            .put("weight_kg", weightKg)

        val device = JSONObject()
            .put("device_id", deviceId)
            .put("address", deviceAddress)
            .put("hardware_version", hardwareVersion ?: JSONObject.NULL)
            .put("firmware_version", firmwareVersion ?: JSONObject.NULL)

        val recording = JSONObject()
            .put("start_time", iso.format(Date(startTimeMs)))
            .put("end_time", iso.format(Date(endTimeMs)))
            .put("start_time_ms", startTimeMs)
            .put("end_time_ms", endTimeMs)
            .put("duration_sec", durationSec)
            .put("sensor_sample_period_ms", sensorSamplePeriodMs ?: JSONObject.NULL)
            .put("sensor_samples_per_packet", sensorSamplesPerPacket ?: JSONObject.NULL)
            .put("video_width", videoWidth ?: JSONObject.NULL)
            .put("video_height", videoHeight ?: JSONObject.NULL)
            .put("video_fps", videoFps ?: JSONObject.NULL)
            .put("orientation_hint", orientationHint ?: JSONObject.NULL)
            .put("video_orientation", videoOrientationLabel ?: JSONObject.NULL)

        val sync = JSONObject()
            .put("phone_start_timestamp_ms", phoneStartTimestampMs)
            .put("collar_uptime_at_sync_ms", collarUptimeAtSyncMs ?: JSONObject.NULL)
            .put("offset_ms", offsetMs ?: JSONObject.NULL)
            .put("sync_method", syncMethod)
            .put(
                "note",
                "CSV absolute time = phone_start_timestamp_ms + (sample_uptime_ms - collar_uptime_at_sync_ms). " +
                    "offset_ms is null until a measured phone↔collar calibration exists.",
            )

        val battery = JSONObject()
            .put("start_percent", batteryStartPercent ?: JSONObject.NULL)
            .put("end_percent", batteryEndPercent ?: JSONObject.NULL)

        val files = JSONObject()
            .put("sensor_data", if (hasData) RecordingStore.dataFileName(sessionId) else JSONObject.NULL)
            .put("video", if (hasVideo) RecordingStore.videoFileName(sessionId) else JSONObject.NULL)
            .put("audio", if (hasAudio) RecordingStore.audioFileName(sessionId) else JSONObject.NULL)
            .put("log", RecordingStore.logFileName(sessionId))

        val app = JSONObject()
            .put("version", appVersionName)
            .put("version_code", appVersionCode)

        val termination = JSONObject()
            .put("reason", terminationReason)

        val quality = JSONObject()
            .put("status", qualityLabel)
            .put("status_detail", statusDetail)
            .put("packet_count", packetCount)
            .put("sample_count", sampleCount)
            .put("parse_failures", parseFailures)
            .put("has_possible_loss", hasPossibleLoss)
            .put("missing_sample_percent", missingSamplePercent)
            .put("duration_sec", ((endTimeMs - startTimeMs).coerceAtLeast(0L) / 1000L).toInt())

        return JSONObject()
            .put("schema_version", 2)
            .put("session_id", sessionId)
            .put("saved_at_ms", savedAtMs)
            .put("gallery_uri", galleryUri ?: JSONObject.NULL)
            .put("user", user)
            .put("pet", pet)
            .put("device", device)
            .put("recording", recording)
            .put("synchronization", sync)
            .put("battery", battery)
            .put("files", files)
            .put("app", app)
            .put("termination", termination)
            .put("quality", quality)
    }

    fun writeTo(sessionDir: File) {
        RecordingStore.sessionManifestFile(sessionDir)
            .writeText(toJson().toString(2), Charsets.UTF_8)
    }

    companion object {
        private fun isoFormatter(): SimpleDateFormat =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
                timeZone = TimeZone.getDefault()
            }

        fun orientationLabel(hint: Int?): String? = when (hint) {
            null -> null
            0 -> "landscape"
            90, 270 -> "portrait"
            180 -> "landscape_upside_down"
            else -> "hint_$hint"
        }
    }
}
