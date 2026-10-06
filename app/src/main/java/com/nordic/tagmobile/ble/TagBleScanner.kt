package com.nordic.tagmobile.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Build
import com.nordic.tagmobile.protocol.TagUuids
import no.nordicsemi.android.support.v18.scanner.BluetoothLeScannerCompat
import no.nordicsemi.android.support.v18.scanner.ScanCallback
import no.nordicsemi.android.support.v18.scanner.ScanRecord
import no.nordicsemi.android.support.v18.scanner.ScanResult
import no.nordicsemi.android.support.v18.scanner.ScanSettings
import java.util.UUID

/**
 * Scans nearby BLE devices and reports Tag / phone / laptop / TWS only
 * (filters out beacons, bulbs, random sensors, etc.).
 */
class TagBleScanner(context: Context) {

    interface Listener {
        fun onDevice(device: BluetoothDevice, rssi: Int, name: String, isTag: Boolean)
        fun onError(message: String)
    }

    var listener: Listener? = null

    private val scanner = BluetoothLeScannerCompat.getScanner()
    private var scanning = false

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val record = result.scanRecord
            val name = resolveName(device, record, hasTagServiceUuid(record))
            val isTag = hasTagServiceUuid(record) || looksLikeTagName(name)
            if (!BleDeviceFilter.shouldShow(device, name, isTag, record)) {
                return
            }
            listener?.onDevice(device, result.rssi, name, isTag)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            listener?.onError("BLE scan failed: $errorCode")
        }
    }

    fun start() {
        if (scanning) return
        // legacy=false: include BLE 5 extended ads (nRF54) as well as legacy 1M.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .setLegacy(false)
            .setUseHardwareBatchingIfSupported(false)
            .build()
        try {
            scanner.startScan(null, settings, callback)
            scanning = true
        } catch (e: Exception) {
            listener?.onError("Scan start failed: ${e.message}")
        }
    }

    fun stop() {
        if (!scanning) return
        try {
            scanner.stopScan(callback)
        } catch (_: Exception) {
        }
        scanning = false
    }

    @SuppressLint("MissingPermission")
    private fun resolveName(device: BluetoothDevice, record: ScanRecord?, isTag: Boolean): String {
        val adv = record?.deviceName?.trim()?.takeIf { it.isNotEmpty() }
        val parsed = parseLocalNameFromBytes(record)
        val cache = try {
            device.name?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: SecurityException) {
            null
        }
        val alias = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                device.alias?.trim()?.takeIf { it.isNotEmpty() }
            } else {
                null
            }
        } catch (_: SecurityException) {
            null
        }
        val bonded = bondedName(device.address)
        val resolved = firstNonBlank(adv, parsed, cache, alias, bonded)
        if (!resolved.isNullOrBlank() && resolved != "Unknown") {
            return resolved
        }
        if (isTag) {
            return "Tag"
        }
        return "Unknown"
    }

    private fun looksLikeTagName(name: String): Boolean =
        name.equals("Tag", ignoreCase = true) ||
            name.startsWith("Tag_", ignoreCase = true)

    @SuppressLint("MissingPermission")
    private fun bondedName(address: String): String? {
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
            adapter.bondedDevices?.firstOrNull { it.address.equals(address, ignoreCase = true) }
                ?.name
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private val TAG_SERVICE: UUID = UUID.fromString(TagUuids.STREAM_SERVICE)
        private val NUS_SERVICE: UUID = UUID.fromString(TagUuids.NUS_SERVICE)

        fun hasTagServiceUuid(record: ScanRecord?): Boolean {
            val uuids = record?.serviceUuids ?: return false
            return uuids.any { it.uuid == TAG_SERVICE || it.uuid == NUS_SERVICE }
        }

        fun hasTagServiceUuid(result: ScanResult): Boolean =
            hasTagServiceUuid(result.scanRecord)

        private fun firstNonBlank(vararg values: String?): String? =
            values.firstOrNull { !it.isNullOrBlank() }

        fun parseLocalNameFromBytes(record: ScanRecord?): String? {
            val bytes = record?.bytes ?: return null
            var i = 0
            while (i < bytes.size) {
                val len = bytes[i].toInt() and 0xFF
                if (len == 0) break
                if (i + len >= bytes.size) break
                val type = bytes[i + 1].toInt() and 0xFF
                if (type == 0x08 || type == 0x09) {
                    val start = i + 2
                    val end = i + 1 + len
                    if (start < end && end <= bytes.size) {
                        val name = String(bytes, start, end - start, Charsets.UTF_8).trim()
                        if (name.isNotEmpty()) return name
                    }
                }
                i += len + 1
            }
            return null
        }
    }
}
