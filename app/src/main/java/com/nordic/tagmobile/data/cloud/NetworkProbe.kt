package com.nordic.tagmobile.data.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build

/** Snapshot of active network for sync diagnostics (visible in Log Viewer). */
object NetworkProbe {
    fun summary(context: Context): String {
        val cm = connectivity(context) ?: return "net=unknown"
        val wifi = findWifiNetwork(context)
        val active = cm.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        val transport = when {
            caps == null -> if (legacyWifiConnected(cm)) "wifi_legacy" else "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val internet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val notMetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        return "active=$transport validated=$validated internet=$internet unmetered=$notMetered " +
            "wifiAvailable=${wifi != null || legacyWifiConnected(cm)} api=${Build.VERSION.SDK_INT}"
    }

    /**
     * True when Wi‑Fi is connected (any Wi‑Fi network), including Android 9 dual‑SIM cases
     * where active network is cellular.
     */
    fun isWifiConnected(context: Context): Boolean {
        if (findWifiNetwork(context) != null) return true
        val cm = connectivity(context) ?: return false
        return legacyWifiConnected(cm)
    }

    /**
     * Wi‑Fi [Network] for optional socket binding.
     * Prefers validated/internet Wi‑Fi; falls back to any TRANSPORT_WIFI.
     */
    fun findWifiNetwork(context: Context): Network? {
        val cm = connectivity(context) ?: return null
        fun isWifi(n: Network, requireInternet: Boolean): Boolean {
            val caps = cm.getNetworkCapabilities(n) ?: return false
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false
            if (requireInternet && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                return false
            }
            return true
        }
        cm.activeNetwork?.let { if (isWifi(it, requireInternet = true)) return it }
        for (n in cm.allNetworks) {
            if (isWifi(n, requireInternet = true)) return n
        }
        // Android 9 captive / slow validation: Wi‑Fi up but INTERNET flag not set yet.
        cm.activeNetwork?.let { if (isWifi(it, requireInternet = false)) return it }
        for (n in cm.allNetworks) {
            if (isWifi(n, requireInternet = false)) return n
        }
        return null
    }

    fun isUnmeteredWifi(context: Context): Boolean = isWifiConnected(context)

    fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> String.format("%.1fKB", bytes / 1024.0)
        else -> String.format("%.1fMB", bytes / (1024.0 * 1024.0))
    }

    @Suppress("DEPRECATION")
    private fun legacyWifiConnected(cm: ConnectivityManager): Boolean {
        return try {
            val ni = cm.activeNetworkInfo
            ni != null && ni.isConnected && ni.type == ConnectivityManager.TYPE_WIFI
        } catch (_: Exception) {
            false
        }
    }

    private fun connectivity(context: Context): ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
}
