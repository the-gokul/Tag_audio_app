package com.nordic.tagmobile.protocol

import com.nordic.tagmobile.model.DeviceConfig
import com.nordic.tagmobile.model.UserProfile
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Minimal .xlsx writer (OOXML) with two sheets: "data" and "Summary".
 * No Apache POI — safe for Android packaging.
 */
object XlsxExporter {

    data class SummaryInfo(
        val profile: UserProfile,
        val deviceConfig: DeviceConfig,
        val deviceName: String,
        val packetCount: Int,
        val sampleCount: Int,
        val status: String,
        val measuredSampleHz: Double? = null,
        val measuredPacketsPerSec: Double? = null,
    )

    fun write(
        outFile: File,
        rows: List<SensorCsvRow>,
        summary: SummaryInfo,
    ) {
        outFile.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(outFile)).use { zip ->
            fun put(path: String, body: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(body.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", contentTypes())
            put("_rels/.rels", rootRels())
            put("xl/workbook.xml", workbook())
            put("xl/_rels/workbook.xml.rels", workbookRels())
            put("xl/styles.xml", styles())
            put("xl/worksheets/sheet1.xml", dataSheet(rows))
            put("xl/worksheets/sheet2.xml", summarySheet(summary, rows))
        }
    }

    private fun xmlEscape(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private fun cellInline(ref: String, value: String): String =
        """<c r="$ref" t="inlineStr"><is><t>${xmlEscape(value)}</t></is></c>"""

    private fun contentTypes() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>"""

    private fun rootRels() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

    private fun workbook() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
 xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets>
    <sheet name="data" sheetId="1" r:id="rId1"/>
    <sheet name="Summary" sheetId="2" r:id="rId2"/>
  </sheets>
</workbook>"""

    private fun workbookRels() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>
  <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""

    private fun styles() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts>
  <fills count="1"><fill><patternFill patternType="none"/></fill></fills>
  <borders count="1"><border/></borders>
  <cellStyleXfs count="1"><xf/></cellStyleXfs>
  <cellXfs count="1"><xf/></cellXfs>
</styleSheet>"""

    private fun colLetter(index0: Int): String {
        var n = index0
        val sb = StringBuilder()
        while (n >= 0) {
            sb.insert(0, ('A'.code + n % 26).toChar())
            n = n / 26 - 1
        }
        return sb.toString()
    }

    private fun dataSheet(rows: List<SensorCsvRow>): String {
        val headers = CsvExporter.header().split(",")
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        sb.append("<row r=\"1\">")
        headers.forEachIndexed { i, h -> sb.append(cellInline("${colLetter(i)}1", h)) }
        sb.append("</row>")
        rows.forEachIndexed { rowIdx, row ->
            val r = rowIdx + 2
            val cells = CsvExporter.row(row).split(",")
            sb.append("<row r=\"$r\">")
            cells.forEachIndexed { i, v -> sb.append(cellInline("${colLetter(i)}$r", v)) }
            sb.append("</row>")
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    private fun summarySheet(summary: SummaryInfo, rows: List<SensorCsvRow>): String {
        val cfg = summary.deviceConfig
        val sampleHzConfig = if (cfg.samplePeriodMs > 0) 1000.0 / cfg.samplePeriodMs else 0.0
        val pktPerSecConfig = if (cfg.accumMs > 0) 1000.0 / cfg.accumMs else 0.0
        val measuredHz = summary.measuredSampleHz
            ?: measuredSampleHz(rows)
        val measuredPps = summary.measuredPacketsPerSec
            ?: measuredPacketsPerSec(rows)

        val pairs = listOf(
            "Field" to "Value",
            "User name" to (com.nordic.tagmobile.TagSession.appUser.name.ifBlank { summary.profile.name }),
            "User phone" to com.nordic.tagmobile.TagSession.appUser.phone,
            "Animal type" to summary.profile.animalType,
            "Animal name" to summary.profile.dogName,
            "Breed" to summary.profile.breed,
            "Age" to summary.profile.age,
            "Weight (kg)" to summary.profile.weight,
            "Gender" to summary.profile.gender,
            "Device" to summary.deviceName,
            "Samples per packet" to cfg.samplesPerPacket.toString(),
            "Sample period (ms)" to cfg.samplePeriodMs.toString(),
            "Configured sample rate (Hz)" to String.format(Locale.US, "%.2f", sampleHzConfig),
            "Configured packets/sec" to String.format(Locale.US, "%.2f", pktPerSecConfig),
            "Flush packets" to cfg.flushPkts.toString(),
            "BMI270 enabled" to cfg.bmi270Enabled.toString(),
            "BME688 enabled" to cfg.bme688Enabled.toString(),
            "TMP117 enabled" to cfg.tmp117Enabled.toString(),
            "Session packets" to summary.packetCount.toString(),
            "Session samples" to summary.sampleCount.toString(),
            "Measured sample rate (Hz)" to (measuredHz?.let { String.format(Locale.US, "%.2f", it) } ?: "n/a"),
            "Measured packets/sec" to (measuredPps?.let { String.format(Locale.US, "%.2f", it) } ?: "n/a"),
            "Status" to summary.status,
        )

        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        pairs.forEachIndexed { idx, (k, v) ->
            val r = idx + 1
            sb.append("<row r=\"$r\">")
            sb.append(cellInline("A$r", k))
            sb.append(cellInline("B$r", v))
            sb.append("</row>")
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    private fun measuredSampleHz(rows: List<SensorCsvRow>): Double? {
        if (rows.size < 2) return null
        val dt = rows.last().timestampMs - rows.first().timestampMs
        if (dt <= 0) return null
        return (rows.size - 1) * 1000.0 / dt
    }

    private fun measuredPacketsPerSec(rows: List<SensorCsvRow>): Double? {
        if (rows.size < 2) return null
        val packetIds = rows.map { it.packetNo }.distinct()
        if (packetIds.size < 2) return null
        val dt = rows.last().timestampMs - rows.first().timestampMs
        if (dt <= 0) return null
        return (packetIds.size - 1) * 1000.0 / dt
    }
}
