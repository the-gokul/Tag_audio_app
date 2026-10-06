package com.nordic.tagmobile.analysis

import com.nordic.tagmobile.protocol.SensorCsvRow

data class SessionReport(
    val packetCount: Int,
    val sampleCount: Int,
    val parseFailures: Int,
    val packetIds: List<Long>,
    val statusShort: String,
    val statusDetail: String,
    val hasPossibleLoss: Boolean,
    /** GOOD | WARNING | FAILED | SESSION_LOSS — used in History + manifest.json */
    val qualityStatus: String,
    /** Estimated missing samples from packet/sample id gaps (0–100). */
    val missingSamplePercent: Double = 0.0,
    val durationSec: Long = 0L,
    val hasVideo: Boolean = false,
) {
    val feedbackText: String
        get() = buildString {
            appendLine("Recording complete")
            appendLine("Packets: $packetCount · Samples: $sampleCount")
            appendLine("Quality: $qualityStatus")
            if (parseFailures > 0) {
                appendLine("Parse failures: $parseFailures")
            }
            if (missingSamplePercent > 0.0) {
                appendLine("Estimated missing samples: ${"%.1f".format(missingSamplePercent)}%")
            }
            appendLine("Status: $statusShort")
            if (statusDetail.isNotBlank()) {
                appendLine(statusDetail)
            }
            append("Saved to History")
        }
}

object SessionAnalyzer {
    /** Below this duration with little/no sensor data → FAILED. */
    private const val MIN_USEFUL_DURATION_SEC = 3L
    /** Missing-sample ratio at or above this → WARNING. */
    private const val WARNING_MISSING_PERCENT = 5.0
    /** Missing-sample ratio at or above this → FAILED. */
    private const val FAILED_MISSING_PERCENT = 40.0

    fun analyze(
        packetCount: Int,
        rows: List<SensorCsvRow>,
        packetIds: List<Long>,
        parseFailures: Int,
        durationSec: Long = 0L,
        hasVideo: Boolean = true,
    ): SessionReport {
        val sampleCount = rows.size
        val sampleGaps = findSampleGaps(rows)
        val packetGaps = findPacketIdGaps(packetIds)
        val missingPercent = estimateMissingPercent(rows, packetIds)
        val hasGaps = sampleGaps.isNotEmpty() || packetGaps.isNotEmpty()
        val detailParts = mutableListOf<String>()
        if (packetGaps.isNotEmpty() || sampleGaps.isNotEmpty()) {
            detailParts.add((packetGaps + sampleGaps).take(3).joinToString("; "))
        }
        if (missingPercent > 0.0) {
            detailParts.add("missing≈${"%.1f".format(missingPercent)}%")
        }
        if (!hasVideo) {
            detailParts.add("video file missing or empty")
        }
        if (durationSec in 1 until MIN_USEFUL_DURATION_SEC) {
            detailParts.add("very short recording (${durationSec}s)")
        }

        val quality = decideQuality(
            packetCount = packetCount,
            sampleCount = sampleCount,
            parseFailures = parseFailures,
            hasGaps = hasGaps,
            missingPercent = missingPercent,
            durationSec = durationSec,
            hasVideo = hasVideo,
        )

        if (packetCount == 0 && sampleCount == 0) {
            return SessionReport(
                packetCount = 0,
                sampleCount = 0,
                parseFailures = parseFailures,
                packetIds = packetIds,
                statusShort = "No data received",
                statusDetail = listOf("Check connection / Start")
                    .plus(detailParts)
                    .joinToString("; "),
                hasPossibleLoss = false,
                qualityStatus = quality,
                missingSamplePercent = 0.0,
                durationSec = durationSec,
                hasVideo = hasVideo,
            )
        }

        return when (quality) {
            "FAILED" -> SessionReport(
                packetCount = packetCount,
                sampleCount = sampleCount,
                parseFailures = parseFailures,
                packetIds = packetIds,
                statusShort = "FAILED",
                statusDetail = detailParts.joinToString("; ").ifBlank { "Unusable session" },
                hasPossibleLoss = hasGaps || missingPercent >= WARNING_MISSING_PERCENT,
                qualityStatus = "FAILED",
                missingSamplePercent = missingPercent,
                durationSec = durationSec,
                hasVideo = hasVideo,
            )
            "WARNING" -> SessionReport(
                packetCount = packetCount,
                sampleCount = sampleCount,
                parseFailures = parseFailures,
                packetIds = packetIds,
                statusShort = "WARNING",
                statusDetail = detailParts.joinToString("; ").ifBlank { "Possible data issues" },
                hasPossibleLoss = true,
                qualityStatus = "WARNING",
                missingSamplePercent = missingPercent,
                durationSec = durationSec,
                hasVideo = hasVideo,
            )
            else -> SessionReport(
                packetCount = packetCount,
                sampleCount = sampleCount,
                parseFailures = parseFailures,
                packetIds = packetIds,
                statusShort = "GOOD",
                statusDetail = "",
                hasPossibleLoss = false,
                qualityStatus = "GOOD",
                missingSamplePercent = missingPercent,
                durationSec = durationSec,
                hasVideo = hasVideo,
            )
        }
    }

    private fun decideQuality(
        packetCount: Int,
        sampleCount: Int,
        parseFailures: Int,
        hasGaps: Boolean,
        missingPercent: Double,
        durationSec: Long,
        hasVideo: Boolean,
    ): String {
        if (!hasVideo) return "FAILED"
        if (packetCount == 0 && sampleCount == 0) return "FAILED"
        if (durationSec > 0 && durationSec < MIN_USEFUL_DURATION_SEC && sampleCount < 5) {
            return "FAILED"
        }
        if (missingPercent >= FAILED_MISSING_PERCENT) return "FAILED"

        if (hasGaps || parseFailures > 0 || missingPercent >= WARNING_MISSING_PERCENT) {
            return "WARNING"
        }
        if (durationSec in 1 until MIN_USEFUL_DURATION_SEC) {
            return "WARNING"
        }
        return "GOOD"
    }

    /**
     * Rough missing % from contiguous id ranges.
     * Uses sample_number span when available; else packet_id span.
     */
    private fun estimateMissingPercent(rows: List<SensorCsvRow>, packetIds: List<Long>): Double {
        if (rows.size >= 2) {
            val first = rows.first().sampleNumber
            val last = rows.last().sampleNumber
            val expected = (last - first + 1).coerceAtLeast(1L)
            val missing = (expected - rows.size).coerceAtLeast(0L)
            return (missing.toDouble() / expected.toDouble()) * 100.0
        }
        if (packetIds.size >= 2) {
            val first = packetIds.first()
            val last = packetIds.last()
            val expected = (last - first + 1).coerceAtLeast(1L)
            val missing = (expected - packetIds.size).coerceAtLeast(0L)
            return (missing.toDouble() / expected.toDouble()) * 100.0
        }
        return 0.0
    }

    private fun findSampleGaps(rows: List<SensorCsvRow>): List<String> {
        if (rows.size < 2) return emptyList()
        val gaps = mutableListOf<String>()
        for (i in 1 until rows.size) {
            val prev = rows[i - 1].sampleNumber
            val cur = rows[i].sampleNumber
            if (cur > prev + 1) {
                gaps.add("sample gap: expected ${prev + 1}, got $cur")
            }
        }
        return gaps
    }

    private fun findPacketIdGaps(ids: List<Long>): List<String> {
        if (ids.size < 2) return emptyList()
        val gaps = mutableListOf<String>()
        for (i in 1 until ids.size) {
            val prev = ids[i - 1]
            val cur = ids[i]
            if (cur > prev + 1) {
                gaps.add("packet_id gap: expected ${prev + 1}, got $cur (~${cur - prev - 1} missing)")
            }
        }
        return gaps
    }
}
