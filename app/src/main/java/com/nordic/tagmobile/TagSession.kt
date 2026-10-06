package com.nordic.tagmobile

import com.nordic.tagmobile.model.ConnectedDevice
import com.nordic.tagmobile.model.CameraConfig
import com.nordic.tagmobile.model.DeviceConfig
import com.nordic.tagmobile.model.RecordingState
import com.nordic.tagmobile.model.AppUser
import com.nordic.tagmobile.model.UserProfile
import com.nordic.tagmobile.protocol.SensorCsvRow
import com.nordic.tagmobile.storage.HistoryEntry

object TagSession {
    var connectedDevice: ConnectedDevice? = null
    var recordingState: RecordingState = RecordingState.IDLE
    var syncBaseUnixMs: Long = 0L
    /** Session id for current recording, e.g. SESSION-20260910-174530-a1b2 (set at Start). */
    var sessionBaseName: String = ""
    var tagUptimeAtSync: Long? = null

    // Recording metadata for manifest.json (set at Start / Stop)
    var recordingStartUnixMs: Long = 0L
    var recordingVideoWidth: Int? = null
    var recordingVideoHeight: Int? = null
    var recordingVideoFps: Int? = null
    var recordingOrientationHint: Int? = null

    val receivedRows: MutableList<SensorCsvRow> = mutableListOf()
    val packetIds: MutableList<Long> = mutableListOf()
    var packetCount: Int = 0
    var parseFailures: Int = 0
    var deviceConfig: DeviceConfig = DeviceConfig.default()
    var customDataEnabled: Boolean = false
    var includeSiUnits: Boolean = false
    var lastHistoryEntry: HistoryEntry? = null
    var lastFeedbackText: String = ""

    // ── User & pet ───────────────────────────────────────────────────────────
    var appUser: AppUser = AppUser()
    var userProfile: UserProfile = UserProfile()
    /** Collar firmware string read over BLE (null if char missing / unread). */
    var firmwareVersion: String? = null
    var cameraConfig: CameraConfig = CameraConfig()

    fun resetRecording() {
        recordingState = RecordingState.IDLE
        syncBaseUnixMs = 0L
        sessionBaseName = ""
        tagUptimeAtSync = null
        recordingStartUnixMs = 0L
        recordingVideoWidth = null
        recordingVideoHeight = null
        recordingVideoFps = null
        recordingOrientationHint = null
        receivedRows.clear()
        packetIds.clear()
        packetCount = 0
        parseFailures = 0
        lastFeedbackText = ""
    }

    fun isConnected(): Boolean {
        val device = connectedDevice ?: return false
        return try {
            val ble = TagApp.instance.bleManager
            // Keep Home / Start enabled while GATT recovers (reconnect window).
            ble.isTagReady || ble.isHoldingLink
        } catch (_: Exception) {
            // App not ready yet — fall back to remembered device.
            device != null
        }
    }

    fun clearConnection() {
        connectedDevice = null
        firmwareVersion = null
        resetRecording()
        customDataEnabled = false
        includeSiUnits = false
        deviceConfig = DeviceConfig.default()
        lastHistoryEntry = null
        BleUiBridge.notifyConnectionChanged(false)
    }
}
