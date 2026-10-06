package com.nordic.tagmobile.protocol

/** ASCII commands for ble_2 NUS RX (UTF-8). */
object Ble2Command {
    const val START = "START"
    const val STOP = "STOP"
    const val GAIN_GET = "GAIN_GET"
    const val STATUS_GET = "STATUS_GET"
    const val AUDIO_CONFIG_GET = "AUDIO_CONFIG_GET"
    const val DEVICE_INFO_GET = "DEVICE_INFO_GET"
    const val RESET = "RESET"
    const val PING = "PING"

    fun gainSet(db: Int) = "GAIN_SET $db"
    fun gainMinSet(db: Int) = "GAIN_MIN_SET $db"
    fun gainMaxSet(db: Int) = "GAIN_MAX_SET $db"
    fun gainDefaultSet(db: Int) = "GAIN_DEFAULT_SET $db"

    fun utf8(line: String): ByteArray = line.toByteArray(Charsets.UTF_8)
}
