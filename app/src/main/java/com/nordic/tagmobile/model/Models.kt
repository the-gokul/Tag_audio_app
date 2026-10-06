package com.nordic.tagmobile.model

data class ConnectedDevice(
    var name: String,
    val address: String,
    var rssi: Int,
)

data class DeviceConfig(
    val samplesPerPacket: Int = 5,
    val samplePeriodMs: Int = 50,
    val flushPkts: Int = 20,
    val bmi270Enabled: Boolean = true,
    val bme688Enabled: Boolean = true,
    val tmp117Enabled: Boolean = true,
) {
    val accumMs: Int get() = samplesPerPacket * samplePeriodMs
    val holdMs: Int get() = flushPkts * accumMs

    companion object {
        fun default() = DeviceConfig()
    }
}

enum class RecordingState {
    IDLE,
    SYNCING,
    RECEIVING,
    RECEIVED,
    CONVERTING,
    SAVING,
}
