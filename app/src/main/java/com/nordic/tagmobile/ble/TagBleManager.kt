package com.nordic.tagmobile.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.nordic.tagmobile.BleUiBridge
import com.nordic.tagmobile.TagSession
import com.nordic.tagmobile.log.LogCategory
import com.nordic.tagmobile.log.TagLogger
import com.nordic.tagmobile.model.RecordingState
import com.nordic.tagmobile.protocol.TagCommand
import com.nordic.tagmobile.protocol.TagUuids
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.data.Data
import no.nordicsemi.android.ble.observer.ConnectionObserver
import java.nio.charset.Charset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Nordic BleManager for Tag GATT: MTU, discover, notify, START/STOP writes.
 * Holds link with FGS + reconnect on unexpected drops (commercial behavior).
 */
class TagBleManager(context: Context) : BleManager(context) {

    interface Listener {
        fun onReady(device: BluetoothDevice)
        fun onDisconnected()
        fun onPacket(data: ByteArray)
        fun onError(message: String)
        fun onAsciiLine(line: String) {}
        fun onAudioNotify(data: ByteArray) {}
    }

    /** Screen-specific listener (Scan / Record). Prefer [addListener]/[removeListener]. */
    var listener: Listener? = null

    private val extraListeners = CopyOnWriteArrayList<Listener>()
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val userDisconnect = AtomicBoolean(false)

    @Volatile
    private var lastDevice: BluetoothDevice? = null

    @Volatile
    var isReconnecting: Boolean = false
        private set

    private var reconnectAttempt = 0
    private val reconnectRunnable = Runnable { attemptReconnect() }

    /** True while GATT is ready or we are actively recovering the link. */
    val isHoldingLink: Boolean
        get() = isTagReady || isReconnecting

    fun addListener(l: Listener) {
        if (!extraListeners.contains(l)) extraListeners.add(l)
    }

    fun removeListener(l: Listener) {
        extraListeners.remove(l)
    }

    private fun emitReady(device: BluetoothDevice) {
        listener?.onReady(device)
        extraListeners.forEach { it.onReady(device) }
    }

    private fun emitDisconnected() {
        listener?.onDisconnected()
        extraListeners.forEach { it.onDisconnected() }
    }

    private fun emitPacket(data: ByteArray) {
        listener?.onPacket(data)
        extraListeners.forEach { it.onPacket(data) }
    }

    private fun emitError(message: String) {
        listener?.onError(message)
        extraListeners.forEach { it.onError(message) }
    }

    private fun emitAscii(line: String) {
        listener?.onAsciiLine(line)
        extraListeners.forEach { it.onAsciiLine(line) }
    }

    private fun emitAudio(data: ByteArray) {
        listener?.onAudioNotify(data)
        extraListeners.forEach { it.onAudioNotify(data) }
    }

    private val nusServiceUuid = UUID.fromString(TagUuids.NUS_SERVICE)
    private val nusRxUuid = UUID.fromString(TagUuids.NUS_RX)
    private val nusTxUuid = UUID.fromString(TagUuids.NUS_TX)
    private val streamUuid = UUID.fromString(TagUuids.STREAM_SERVICE)
    private val sensorUuid = UUID.fromString(TagUuids.SENSOR_DATA)
    private val commandUuid = UUID.fromString(TagUuids.COMMAND)
    private val firmwareUuid = UUID.fromString(TagUuids.FIRMWARE_VERSION)

    private var nusRxChar: BluetoothGattCharacteristic? = null
    private var nusTxChar: BluetoothGattCharacteristic? = null
    private var sensorChar: BluetoothGattCharacteristic? = null
    private var commandChar: BluetoothGattCharacteristic? = null
    private var firmwareChar: BluetoothGattCharacteristic? = null
    private var ready = false
    private val asciiAcc = StringBuilder()

    val isTagReady: Boolean get() = ready && isConnected

    init {
        setConnectionObserver(object : ConnectionObserver {
            override fun onDeviceConnecting(device: BluetoothDevice) = Unit
            override fun onDeviceConnected(device: BluetoothDevice) = Unit
            override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) {
                ready = false
                val label = reasonLabel(reason)
                TagLogger.log(LogCategory.BLE, "BLE_CONNECT_FAIL", "reason=$label ($reason)")
                if (isReconnecting) {
                    scheduleReconnectOrGiveUp("connect_fail:$label")
                    return
                }
                val msg = when (reason) {
                    ConnectionObserver.REASON_NOT_SUPPORTED ->
                        "Not a Tag_PDM device (NUS missing)"
                    ConnectionObserver.REASON_TIMEOUT ->
                        "Connect timed out"
                    else ->
                        "Connect failed ($reason)"
                }
                emitError(msg)
            }

            override fun onDeviceReady(device: BluetoothDevice) = Unit
            override fun onDeviceDisconnecting(device: BluetoothDevice) = Unit
            override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
                ready = false
                val label = reasonLabel(reason)
                TagLogger.log(
                    LogCategory.BLE,
                    "BLE_DISCONNECT",
                    "reason=$label ($reason) user=${userDisconnect.get()} reconnecting=$isReconnecting",
                )
                val intentional = userDisconnect.get() ||
                    reason == ConnectionObserver.REASON_SUCCESS ||
                    reason == ConnectionObserver.REASON_TERMINATE_LOCAL_HOST

                if (intentional) {
                    finishLinkDown(stopService = true)
                    return
                }

                // Mid-recording: do not reconnect — DeviceActivity auto-saves SESSION_LOSS.
                // Home / idle link still uses reconnect below.
                if (TagSession.recordingState == RecordingState.RECEIVING) {
                    TagLogger.log(
                        LogCategory.BLE,
                        "BLE_NO_RECONNECT_WHILE_RECORDING",
                        "reason=$label ($reason)",
                    )
                    finishLinkDown(stopService = true)
                    return
                }

                // Unexpected drop — keep UI / session, try to recover.
                isReconnecting = true
                BleUiBridge.notifyConnectionChanged(true)
                scheduleReconnectOrGiveUp("drop:$label")
            }
        })
    }

    override fun getGattCallback(): BleManagerGattCallback = object : BleManagerGattCallback() {
        override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
            val nus = gatt.getService(nusServiceUuid) ?: return false
            nusRxChar = nus.getCharacteristic(nusRxUuid)
            nusTxChar = nus.getCharacteristic(nusTxUuid)
            val rxOk = nusRxChar != null &&
                (
                    (nusRxChar!!.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                        (nusRxChar!!.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                    )
            val txOk = nusTxChar != null &&
                (nusTxChar!!.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
            // Keep optional TAG_STREAM refs if present (camera path unused for ble_2).
            val stream = gatt.getService(streamUuid)
            sensorChar = stream?.getCharacteristic(sensorUuid)
            commandChar = stream?.getCharacteristic(commandUuid)
            firmwareChar = stream?.getCharacteristic(firmwareUuid)
            return rxOk && txOk
        }

        override fun initialize() {
            requestMtu(247).enqueue()
            requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED).enqueue()
            asciiAcc.setLength(0)
            setNotificationCallback(nusTxChar).with { _, data ->
                val bytes = data.value ?: return@with
                handleNusNotify(bytes)
            }
            enableNotifications(nusTxChar)
                .fail { _, status ->
                    ready = false
                    emitError("Notify enable failed ($status)")
                }
                .done { markReady() }
                .enqueue()
        }

        override fun onServicesInvalidated() {
            ready = false
            nusRxChar = null
            nusTxChar = null
            sensorChar = null
            commandChar = null
            firmwareChar = null
            asciiAcc.setLength(0)
        }
    }

    private fun handleNusNotify(bytes: ByteArray) {
        if (com.nordic.tagmobile.protocol.AudioPacketParser.looksLikeAudio(bytes)) {
            emitAudio(bytes)
            return
        }
        val text = try {
            String(bytes, Charsets.UTF_8)
        } catch (_: Exception) {
            emitPacket(bytes)
            return
        }
        asciiAcc.append(text)
        if (asciiAcc.length > 512) {
            asciiAcc.delete(0, asciiAcc.length - 256)
        }
        while (true) {
            val raw = asciiAcc.toString()
            val nl = raw.indexOf('\n')
            if (nl < 0) break
            val line = raw.substring(0, nl).trim('\r', ' ', '\t')
            asciiAcc.delete(0, nl + 1)
            if (line.isNotEmpty()) emitAscii(line)
        }
    }

    fun sendAscii(command: String) {
        val char = nusRxChar
        if (char == null || !ready) {
            emitError("Not connected")
            return
        }
        val payload = com.nordic.tagmobile.protocol.Ble2Command.utf8(command)
        val type =
            if ((char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
        writeCharacteristic(char, Data(payload), type)
            .fail { _, status -> emitError("Write failed ($status): $command") }
            .enqueue()
    }

    private fun readFirmwareThenReady() {
        val fw = firmwareChar
        if (fw == null || (fw.properties and BluetoothGattCharacteristic.PROPERTY_READ) == 0) {
            TagSession.firmwareVersion = null
            markReady()
            return
        }
        readCharacteristic(fw)
            .with { _, data ->
                val bytes = data.value
                TagSession.firmwareVersion = if (bytes != null && bytes.isNotEmpty()) {
                    String(bytes, Charset.forName("UTF-8")).trim { it <= ' ' || it == '\u0000' }
                        .ifBlank { null }
                } else {
                    null
                }
            }
            .fail { _, _ ->
                TagSession.firmwareVersion = null
                markReady()
            }
            .done { markReady() }
            .enqueue()
    }

    private fun markReady() {
        ready = true
        isReconnecting = false
        reconnectAttempt = 0
        mainHandler.removeCallbacks(reconnectRunnable)
        userDisconnect.set(false)
        TagBleForegroundService.start(appContext)
        bluetoothDevice?.let {
            lastDevice = it
            emitReady(it)
        }
        BleUiBridge.notifyConnectionChanged(true)
        TagLogger.log(LogCategory.BLE, "BLE_READY", "NUS notify ok; FGS started")
        sendAscii(com.nordic.tagmobile.protocol.Ble2Command.PING)
    }

    fun connectTag(device: BluetoothDevice) {
        userDisconnect.set(false)
        cancelReconnect(keepHolding = false)
        ready = false
        lastDevice = device
        TagSession.firmwareVersion = null
        TagLogger.log(LogCategory.BLE, "BLE_CONNECT", device.address)
        connect(device)
            .retry(3, 200)
            .useAutoConnect(false)
            .timeout(20_000)
            .enqueue()
    }

    fun disconnectTag() {
        userDisconnect.set(true)
        cancelReconnect(keepHolding = false)
        TagLogger.log(LogCategory.BLE, "BLE_USER_DISCONNECT", lastDevice?.address ?: "?")
        disconnect().enqueue()
        ready = false
        lastDevice = null
        TagBleForegroundService.stop(appContext)
    }

    fun startRecording(unixMs: Long) {
        val char = commandChar
        if (char == null || !ready) {
            emitError("Not connected")
            return
        }
        writeCharacteristic(
            char,
            Data(TagCommand.startPayload(unixMs)),
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
        )
            .fail { _, status -> emitError("START failed ($status)") }
            .enqueue()
    }

    fun stopRecording() {
        val char = commandChar ?: return
        writeCharacteristic(
            char,
            Data(TagCommand.stopPayload()),
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
        )
            .fail { _, status -> emitError("STOP failed ($status)") }
            .enqueue()
    }

    private fun scheduleReconnectOrGiveUp(cause: String) {
        if (userDisconnect.get()) {
            finishLinkDown(stopService = true)
            return
        }
        // Safety: never recover mid-recording (Option B).
        if (TagSession.recordingState == RecordingState.RECEIVING) {
            TagLogger.log(LogCategory.BLE, "BLE_NO_RECONNECT_WHILE_RECORDING", cause)
            finishLinkDown(stopService = true)
            return
        }
        val device = lastDevice
        if (device == null) {
            finishLinkDown(stopService = true)
            return
        }
        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            TagLogger.log(LogCategory.BLE, "BLE_RECONNECT_GIVE_UP", cause)
            finishLinkDown(stopService = true)
            return
        }
        isReconnecting = true
        val delayMs = reconnectDelayMs(reconnectAttempt)
        TagLogger.log(
            LogCategory.BLE,
            "BLE_RECONNECT_SCHEDULE",
            "attempt=${reconnectAttempt + 1}/$MAX_RECONNECT_ATTEMPTS in=${delayMs}ms cause=$cause",
        )
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, delayMs)
    }

    private fun attemptReconnect() {
        if (userDisconnect.get()) {
            finishLinkDown(stopService = true)
            return
        }
        if (TagSession.recordingState == RecordingState.RECEIVING) {
            TagLogger.log(LogCategory.BLE, "BLE_NO_RECONNECT_WHILE_RECORDING", "attempt_aborted")
            finishLinkDown(stopService = true)
            return
        }
        val device = lastDevice
        if (device == null) {
            finishLinkDown(stopService = true)
            return
        }
        if (isConnected && ready) {
            isReconnecting = false
            return
        }
        reconnectAttempt++
        isReconnecting = true
        TagLogger.log(
            LogCategory.BLE,
            "BLE_RECONNECT_TRY",
            "attempt=$reconnectAttempt addr=${device.address}",
        )
        connect(device)
            .retry(2, 250)
            .useAutoConnect(true)
            .timeout(15_000)
            .enqueue()
    }

    private fun finishLinkDown(stopService: Boolean) {
        cancelReconnect(keepHolding = false)
        ready = false
        lastDevice = null
        if (stopService) {
            TagBleForegroundService.stop(appContext)
        }
        BleUiBridge.notifyConnectionChanged(false)
        emitDisconnected()
    }

    private fun cancelReconnect(keepHolding: Boolean) {
        mainHandler.removeCallbacks(reconnectRunnable)
        reconnectAttempt = 0
        if (!keepHolding) {
            isReconnecting = false
        }
    }

    private fun reconnectDelayMs(attemptZeroBased: Int): Long {
        // 1s, 2s, 4s, 6s, 8s, 10s
        val base = (1L shl attemptZeroBased.coerceAtMost(3)) * 1000L
        return base.coerceAtMost(10_000L)
    }

    private fun reasonLabel(reason: Int): String = when (reason) {
        ConnectionObserver.REASON_SUCCESS -> "SUCCESS"
        ConnectionObserver.REASON_TERMINATE_LOCAL_HOST -> "LOCAL_HOST"
        ConnectionObserver.REASON_TERMINATE_PEER_USER -> "PEER_USER"
        ConnectionObserver.REASON_LINK_LOSS -> "LINK_LOSS"
        ConnectionObserver.REASON_NOT_SUPPORTED -> "NOT_SUPPORTED"
        ConnectionObserver.REASON_CANCELLED -> "CANCELLED"
        ConnectionObserver.REASON_TIMEOUT -> "TIMEOUT"
        else -> "OTHER"
    }

    companion object {
        private const val MAX_RECONNECT_ATTEMPTS = 6
    }
}
