package com.nordic.tagmobile.log

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

enum class LogLevel {
    ERRORS_ONLY,
    BLE_CONTROL,
    VERBOSE,
}

enum class LogCategory {
    APP,
    BLE,
    CONTROL,
    DATA,
    GAPS,
    FILE,
    ERRORS,
}

object TagLogger {
    private const val PREFS = "tag_log_prefs"
    private const val KEY_ENABLED = "logging_enabled"
    private const val KEY_LEVEL = "log_level"
    private const val MAX_LINES = 4000

    private lateinit var prefs: SharedPreferences
    private val lines = CopyOnWriteArrayList<String>()
    private val sessionLines = CopyOnWriteArrayList<String>()
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var listeners = CopyOnWriteArrayList<(String) -> Unit>()

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var level: LogLevel
        get() = when (prefs.getString(KEY_LEVEL, LogLevel.BLE_CONTROL.name)) {
            LogLevel.ERRORS_ONLY.name -> LogLevel.ERRORS_ONLY
            LogLevel.VERBOSE.name -> LogLevel.VERBOSE
            else -> LogLevel.BLE_CONTROL
        }
        set(value) = prefs.edit().putString(KEY_LEVEL, value.name).apply()

    fun clearSessionLog() {
        sessionLines.clear()
    }

    fun sessionSnapshot(): String = sessionLines.joinToString("\n")

    fun snapshot(): List<String> = lines.toList()

    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    fun log(category: LogCategory, event: String, details: String = "") {
        if (!enabled) return
        if (!shouldLog(category)) return
        val ts = timeFmt.format(Date())
        val line = if (details.isBlank()) {
            "$ts | ${category.name} | $event"
        } else {
            "$ts | ${category.name} | $event | $details"
        }
        lines.add(line)
        sessionLines.add(line)
        while (lines.size > MAX_LINES) {
            lines.removeAt(0)
        }
        android.util.Log.i("TagSync", line)
        listeners.forEach { it(line) }
    }

    private fun shouldLog(category: LogCategory): Boolean =
        when (level) {
            LogLevel.ERRORS_ONLY ->
                category == LogCategory.ERRORS || category == LogCategory.GAPS
            LogLevel.BLE_CONTROL ->
                category != LogCategory.DATA
            LogLevel.VERBOSE -> true
        }

    fun logDataSummary(event: String, details: String = "") {
        if (!enabled) return
        if (level == LogLevel.ERRORS_ONLY) return
        // Summaries allowed at BLE_CONTROL and VERBOSE
        val ts = timeFmt.format(Date())
        val line = if (details.isBlank()) {
            "$ts | DATA | $event"
        } else {
            "$ts | DATA | $event | $details"
        }
        lines.add(line)
        sessionLines.add(line)
        while (lines.size > MAX_LINES) {
            lines.removeAt(0)
        }
        android.util.Log.i("TagSync", line)
        listeners.forEach { it(line) }
    }

    fun logDataVerbose(event: String, details: String = "") {
        if (!enabled || level != LogLevel.VERBOSE) return
        log(LogCategory.DATA, event, details)
    }
}
