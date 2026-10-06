package com.nordic.tagmobile.util

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale

/** Resolve dialing country code from SIM / network (fallback +91). */
object CountryDialCodes {
    private val map = mapOf(
        "IN" to "+91",
        "US" to "+1",
        "CA" to "+1",
        "GB" to "+44",
        "AE" to "+971",
        "SG" to "+65",
        "AU" to "+61",
        "DE" to "+49",
        "FR" to "+33",
        "JP" to "+81",
        "KR" to "+82",
        "CN" to "+86",
        "BR" to "+55",
        "ZA" to "+27",
        "NG" to "+234",
        "KE" to "+254",
        "PK" to "+92",
        "BD" to "+880",
        "LK" to "+94",
        "NP" to "+977",
        "MY" to "+60",
        "TH" to "+66",
        "ID" to "+62",
        "PH" to "+63",
        "VN" to "+84",
        "SA" to "+966",
        "QA" to "+974",
        "KW" to "+965",
        "OM" to "+968",
        "BH" to "+973",
        "NL" to "+31",
        "SE" to "+46",
        "NO" to "+47",
        "DK" to "+45",
        "FI" to "+358",
        "IE" to "+353",
        "NZ" to "+64",
        "MX" to "+52",
    )

    fun detectDialCode(context: Context): String {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val iso = sequenceOf(
            tm?.simCountryIso,
            tm?.networkCountryIso,
            Locale.getDefault().country,
        ).mapNotNull { it?.trim()?.uppercase(Locale.US) }
            .firstOrNull { it.length == 2 }
            ?: "IN"
        return map[iso] ?: "+91"
    }

    /** Labels for a simple country-code picker: "IN (+91)". */
    fun pickerLabels(): List<String> =
        map.entries
            .distinctBy { it.value }
            .sortedBy { it.key }
            .map { "${it.key} (${it.value})" }

    fun dialCodeFromPickerLabel(label: String): String {
        val start = label.lastIndexOf('(')
        val end = label.lastIndexOf(')')
        if (start >= 0 && end > start) return label.substring(start + 1, end)
        return "+91"
    }

    /** Split stored "+91XXXXXXXXXX" into dial code + national digits for the login UI. */
    fun splitStoredPhone(stored: String, fallbackDial: String): Pair<String, String> {
        val trimmed = stored.trim()
        if (trimmed.startsWith("+")) {
            val digits = trimmed.drop(1).filter { it.isDigit() }
            // Longest matching dial prefix from known codes
            val known = map.values.distinct().sortedByDescending { it.length }
            for (code in known) {
                val codeDigits = code.drop(1)
                if (digits.startsWith(codeDigits)) {
                    return code to digits.removePrefix(codeDigits)
                }
            }
            return fallbackDial to digits.takeLast(10)
        }
        return fallbackDial to trimmed.filter { it.isDigit() }.take(10)
    }
}
