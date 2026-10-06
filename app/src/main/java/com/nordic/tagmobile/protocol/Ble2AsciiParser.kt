package com.nordic.tagmobile.protocol

data class Ble2GainState(
    val currentDb: Int,
    val minDb: Int,
    val maxDb: Int,
    val defaultDb: Int,
    val q8: Int = 0,
)

data class Ble2AudioConfig(
    val pcmHz: Int,
    val bits: Int,
    val channels: Int,
    val frameMs: Int,
    val chunkSamples: Int,
    val chunkPcm: Int,
    val hdr: Int,
    val gainDb: Int? = null,
    val gainQ8: Int? = null,
    val gainMin: Int? = null,
    val gainMax: Int? = null,
    val gainDef: Int? = null,
) {
    fun isCompatible(): Boolean =
        pcmHz == 8000 && bits == 16 && channels == 1 &&
            chunkSamples == 80 && hdr == 10

    companion object {
        fun fallback() = Ble2AudioConfig(
            pcmHz = 8000,
            bits = 16,
            channels = 1,
            frameMs = 10,
            chunkSamples = 80,
            chunkPcm = 160,
            hdr = 10,
        )
    }
}

data class Ble2DeviceInfo(
    val fw: String,
    val name: String,
    val mic: String,
)

sealed class Ble2Ascii {
    data class Ok(val command: String) : Ble2Ascii()
    data object Pong : Ble2Ascii()
    data class Gain(val state: Ble2GainState) : Ble2Ascii()
    data class AudioConfig(val config: Ble2AudioConfig) : Ble2Ascii()
    data class DeviceInfo(val info: Ble2DeviceInfo) : Ble2Ascii()
    data class Status(val raw: String) : Ble2Ascii()
    data class Err(val raw: String) : Ble2Ascii()
    data class Other(val raw: String) : Ble2Ascii()
}

object Ble2AsciiParser {
    fun parse(line: String): Ble2Ascii {
        val t = line.trim()
        if (t == "PONG") return Ble2Ascii.Pong
        if (t.startsWith("OK ")) return Ble2Ascii.Ok(t.removePrefix("OK ").trim())
        if (t.startsWith("ERR")) return Ble2Ascii.Err(t)
        if (t.startsWith("GAIN ")) return parseGain(t) ?: Ble2Ascii.Other(t)
        if (t.startsWith("AUDIO_CONFIG ")) return parseAudioConfig(t) ?: Ble2Ascii.Other(t)
        if (t.startsWith("DEVICE_INFO ")) return parseDeviceInfo(t) ?: Ble2Ascii.Other(t)
        if (t.startsWith("STATUS ")) return Ble2Ascii.Status(t)
        return Ble2Ascii.Other(t)
    }

    private fun parseGain(line: String): Ble2Ascii.Gain? {
        // GAIN 2 MIN -20 MAX 20 DEF 2 Q8 322
        val p = line.trim().split(Regex("\\s+"))
        fun after(key: String): Int? {
            val i = p.indexOf(key)
            if (i < 0 || i + 1 >= p.size) return null
            return p[i + 1].toIntOrNull()
        }
        val current = p.getOrNull(1)?.toIntOrNull() ?: return null
        val min = after("MIN") ?: return null
        val max = after("MAX") ?: return null
        val def = after("DEF") ?: return null
        val q8 = after("Q8") ?: 0
        return Ble2Ascii.Gain(Ble2GainState(current, min, max, def, q8))
    }

    private fun parseAudioConfig(line: String): Ble2Ascii.AudioConfig? {
        val p = line.removePrefix("AUDIO_CONFIG").trim().split(Regex("\\s+"))
        fun v(key: String): Int? {
            val i = p.indexOf(key)
            if (i < 0 || i + 1 >= p.size) return null
            return p[i + 1].toIntOrNull()
        }
        return Ble2AudioConfig(
            pcmHz = v("PCM_HZ") ?: return null,
            bits = v("BITS") ?: 16,
            channels = v("CH") ?: 1,
            frameMs = v("FRAME_MS") ?: 10,
            chunkSamples = v("CHUNK_SAMPLES") ?: 80,
            chunkPcm = v("CHUNK_PCM") ?: 160,
            hdr = v("HDR") ?: 10,
            gainDb = v("GAIN_DB"),
            gainQ8 = v("GAIN_Q8"),
            gainMin = v("GAIN_MIN"),
            gainMax = v("GAIN_MAX"),
            gainDef = v("GAIN_DEF"),
        ).let { Ble2Ascii.AudioConfig(it) }
    }

    private fun parseDeviceInfo(line: String): Ble2Ascii.DeviceInfo? {
        val p = line.removePrefix("DEVICE_INFO").trim().split(Regex("\\s+"))
        fun s(key: String): String {
            val i = p.indexOf(key)
            return if (i >= 0 && i + 1 < p.size) p[i + 1] else ""
        }
        return Ble2Ascii.DeviceInfo(Ble2DeviceInfo(s("FW"), s("NAME"), s("MIC")))
    }
}
