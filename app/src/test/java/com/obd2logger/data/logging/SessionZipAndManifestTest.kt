package com.obd2logger.data.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class SessionZipAndManifestTest {

    @Test
    fun `writeStreamingZip bundles all companion files and accurate manifest json`() {
        val mainCsv = "${CsvSchema.headerRowCsv()}\n18-Nov-2026 14:32:05.000,..."
        val mode06Csv = "${CompanionCsvWriters.mode06HeaderCsv()}\n18-Nov-2026 14:32:04.500,s1,VIN123,Start,A2,Misfire Cylinder 1 Data,0B,01,count,0,0,655,PASS,A20B0100000000028F"
        val extCsv = "Device Time,Session ID,GM Transmission Fluid Temp(°F)\n18-Nov-2026 14:32:05.000,s1,194"
        val preClearedJson = """{"vin":"VIN123","storedDtcs":[{"code":"P0171"}]}"""

        val payloads = mapOf(
            "trackLog-2026-Nov-18_14-32-05.csv" to mainCsv,
            "trackLog-2026-Nov-18_14-32-05_mode06.csv" to mode06Csv,
            "trackLog-2026-Nov-18_14-32-05_extended.csv" to extCsv,
            "trackLog-2026-Nov-18_14-32-05_precleared.json" to preClearedJson
        )

        val artifacts = listOf(
            SessionArtifactEntry("u1", "trackLog-2026-Nov-18_14-32-05.csv", SessionFileType.MAIN, 1),
            SessionArtifactEntry("u2", "trackLog-2026-Nov-18_14-32-05_mode06.csv", SessionFileType.MODE06, 1),
            SessionArtifactEntry("u3", "trackLog-2026-Nov-18_14-32-05_extended.csv", SessionFileType.EXTENDED, 1),
            SessionArtifactEntry("u4", "trackLog-2026-Nov-18_14-32-05_precleared.json", SessionFileType.PRECLEARED, 1)
        )

        val manifest = SessionManifest(
            appVersion = "1.0.0",
            sessionId = "s1",
            vin = "1HGCM82633A004352",
            startTime = "18-Nov-2026 14:32:05.000",
            endTime = "18-Nov-2026 14:33:05.000",
            files = artifacts
        )

        val zipOut = ByteArrayOutputStream()
        SessionZipBuilder.writeStreamingZip(
            zipOutputStream = zipOut,
            manifest = manifest,
            openInput = { entry -> payloads[entry.filename]?.toByteArray(Charsets.UTF_8)?.let(::ByteArrayInputStream) }
        )

        val entries = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(zipOut.toByteArray())).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                entries[entry.name] = zis.readBytes().toString(Charsets.UTF_8)
                zis.closeEntry()
            }
        }

        assertEquals(5, entries.size)
        assertTrue("manifest.json" in entries)
        assertEquals(mainCsv, entries["trackLog-2026-Nov-18_14-32-05.csv"])
        assertEquals(mode06Csv, entries["trackLog-2026-Nov-18_14-32-05_mode06.csv"])
        assertEquals(extCsv, entries["trackLog-2026-Nov-18_14-32-05_extended.csv"])
        assertEquals(preClearedJson, entries["trackLog-2026-Nov-18_14-32-05_precleared.json"])

        val manifestText = entries.getValue("manifest.json")
        assertTrue(manifestText.contains("\"appVersion\": \"1.0.0\""))
        assertTrue(manifestText.contains("\"sessionId\": \"s1\""))
        assertTrue(manifestText.contains("\"vin\": \"1HGCM82633A004352\""))
        assertTrue(manifestText.contains("\"units\": \"imperial\""))
        assertTrue(manifestText.contains("\"type\": \"main\""))
        assertTrue(manifestText.contains("\"type\": \"mode06\""))
        assertTrue(manifestText.contains("\"type\": \"extended\""))
        assertTrue(manifestText.contains("\"type\": \"precleared\""))
        assertEquals(137, CsvSchema.columns.size)
    }
}
