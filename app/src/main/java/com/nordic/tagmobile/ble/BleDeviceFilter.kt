package com.nordic.tagmobile.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import no.nordicsemi.android.support.v18.scanner.ScanRecord

/**
 * Keep only Tag + phone + laptop/computer + TWS/audio earbuds in the scan list.
 * Other BLE (beacons, bulbs, random sensors) are hidden.
 */
object BleDeviceFilter {

    enum class Category { TAG, PHONE, COMPUTER, TWS, OTHER }

    fun category(
        device: BluetoothDevice,
        name: String,
        isTag: Boolean,
        record: ScanRecord?,
    ): Category {
        if (isTag || looksLikeTagName(name)) return Category.TAG
        if (looksLikeTwsName(name) || isAudioClass(device) || isAudioAppearance(record)) {
            return Category.TWS
        }
        if (looksLikePhoneName(name) || isPhoneClass(device) || isPhoneAppearance(record)) {
            return Category.PHONE
        }
        if (looksLikeComputerName(name) || isComputerClass(device) || isComputerAppearance(record)) {
            return Category.COMPUTER
        }
        return Category.OTHER
    }

    fun shouldShow(
        device: BluetoothDevice,
        name: String,
        isTag: Boolean,
        record: ScanRecord?,
    ): Boolean = category(device, name, isTag, record) != Category.OTHER

    private fun looksLikeTagName(name: String): Boolean =
        name.equals("Tag", ignoreCase = true) ||
            name.startsWith("Tag_", ignoreCase = true)

    private fun looksLikeTwsName(name: String): Boolean {
        val n = name.lowercase()
        val keys = listOf(
            "airpods", "buds", "tws", "earbud", "earbuds", "earphone", "earphones",
            "headset", "headphone", "headphones", "galaxy buds", "pixel buds",
            "freebuds", "soundcore", "jbl", "sony wf", "sony wh", "beats",
            "nothing ear", "realme buds", "redmi buds", "oppo enco", "oneplus buds",
        )
        return keys.any { n.contains(it) }
    }

    private fun looksLikePhoneName(name: String): Boolean {
        val n = name.lowercase()
        val keys = listOf(
            "iphone", "galaxy", "pixel", "redmi", "xiaomi", "poco", "oneplus",
            "oppo", "vivo", "realme", "huawei", "honor", "motorola", "nokia",
            "samsung", "phone", "android",
        )
        // Avoid matching "Galaxy Buds" as phone — TWS check runs first.
        return keys.any { n.contains(it) }
    }

    private fun looksLikeComputerName(name: String): Boolean {
        val n = name.lowercase()
        val keys = listOf(
            "macbook", "laptop", "notebook", "desktop", "imac", "pc-", "pc ",
            "thinkpad", "ideapad", "yoga", "surface", "chromebook", "windows",
            "dell", "hp ", "lenovo", "asus", "acer", "computer",
        )
        return keys.any { n.contains(it) }
    }

    @SuppressLint("MissingPermission")
    private fun isPhoneClass(device: BluetoothDevice): Boolean =
        try {
            device.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE
        } catch (_: SecurityException) {
            false
        }

    @SuppressLint("MissingPermission")
    private fun isComputerClass(device: BluetoothDevice): Boolean =
        try {
            device.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.COMPUTER
        } catch (_: SecurityException) {
            false
        }

    @SuppressLint("MissingPermission")
    private fun isAudioClass(device: BluetoothDevice): Boolean =
        try {
            device.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO
        } catch (_: SecurityException) {
            false
        }

    /** AD type 0x19 Appearance (Bluetooth Assigned Numbers). */
    private fun appearance(record: ScanRecord?): Int? {
        val bytes = record?.bytes ?: return null
        var i = 0
        while (i < bytes.size) {
            val len = bytes[i].toInt() and 0xFF
            if (len == 0) break
            if (i + len >= bytes.size) break
            val type = bytes[i + 1].toInt() and 0xFF
            if (type == 0x19 && len >= 3) {
                val lo = bytes[i + 2].toInt() and 0xFF
                val hi = bytes[i + 3].toInt() and 0xFF
                return lo or (hi shl 8)
            }
            i += len + 1
        }
        return null
    }

    private fun isPhoneAppearance(record: ScanRecord?): Boolean {
        val a = appearance(record) ?: return false
        // Generic Phone 0x0040 .. category phones
        return a in 0x0040..0x007F
    }

    private fun isComputerAppearance(record: ScanRecord?): Boolean {
        val a = appearance(record) ?: return false
        return a in 0x0080..0x00BF
    }

    private fun isAudioAppearance(record: ScanRecord?): Boolean {
        val a = appearance(record) ?: return false
        // Wearable earpiece / headset / headphones, or audio sink-ish ranges used by buds
        return a in 0x0940..0x0943 || a in 0x03C0..0x03FF
    }
}
