package com.nordic.tagmobile.protocol

object TagUuids {
    const val STREAM_SERVICE = "7f5e0a10-4c1d-4b9a-9c22-a1b2c3d4e5f6"
    const val SENSOR_DATA = "7f5e0a11-4c1d-4b9a-9c22-a1b2c3d4e5f6"
    const val COMMAND = "7f5e0a12-4c1d-4b9a-9c22-a1b2c3d4e5f6"
    /** Optional READ characteristic — firmware version string (UTF-8). */
    const val FIRMWARE_VERSION = "7f5e0a13-4c1d-4b9a-9c22-a1b2c3d4e5f6"

    /** Nordic UART Service (ble_2 Tag_PDM). */
    const val NUS_SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
    const val NUS_RX = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
    const val NUS_TX = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
}
