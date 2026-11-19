package com.obd2logger.data.logging

import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class SessionFileType(val jsonType: String, val displayLabel: String) {
    MAIN("main", "Main CSV"),
    MODE06("mode06", "Mode 06 Tests CSV"),
    EXTENDED("extended", "Manufacturer Extended CSV"),
    PRECLEARED("precleared", "Pre-Clear Snapshot JSON"),
    ZIP("zip", "Full Session ZIP")
}

data class SessionArtifactEntry(
    val uri: String,
    val filename: String,
    val type: SessionFileType,
    val rowCount: Long
)

data class SessionManifest(
    val appVersion: String,
    val sessionId: String,
    val vin: String,
    val startTime: String,
    val endTime: String,
    val schemaVersion: String = SCHEMA_VERSION,
    val units: String = "imperial",
    val files: List<SessionArtifactEntry>
) {
    companion object {
        const val SCHEMA_VERSION = "1.0-phase4"
    }
}

/**
 * Pure-Kotlin streaming session ZIP & `manifest.json` builder.
 * Streams each file through an 8 KiB buffer without ever loading whole files into memory.
 */
object SessionZipBuilder {

    private const val BUFFER_SIZE = 8 * 1024

    fun buildManifestJson(manifest: SessionManifest): String {
        fun esc(s: String): String = buildString {
            append('"')
            for (ch in s) {
                when (ch) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
            append('"')
        }

        val filesJson = manifest.files.joinToString(",\n    ") { f ->
            """{"filename": ${esc(f.filename)}, "type": ${esc(f.type.jsonType)}, "rowCount": ${f.rowCount}}"""
        }

        return """
        {
          "appVersion": ${esc(manifest.appVersion)},
          "sessionId": ${esc(manifest.sessionId)},
          "vin": ${esc(manifest.vin)},
          "startTime": ${esc(manifest.startTime)},
          "endTime": ${esc(manifest.endTime)},
          "schemaVersion": ${esc(manifest.schemaVersion)},
          "units": ${esc(manifest.units)},
          "files": [
            $filesJson
          ]
        }
        """.trimIndent()
    }

    /**
     * Writes `manifest.json` and streams each artifact from [openInput] into [zipOutputStream].
     */
    fun writeStreamingZip(
        zipOutputStream: OutputStream,
        manifest: SessionManifest,
        openInput: (SessionArtifactEntry) -> InputStream?
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        ZipOutputStream(BufferedOutputStream(zipOutputStream, BUFFER_SIZE)).use { zos ->
            // 1. Write manifest.json first
            zos.putNextEntry(ZipEntry("manifest.json"))
            val manifestBytes = buildManifestJson(manifest).toByteArray(Charsets.UTF_8)
            zos.write(manifestBytes)
            zos.closeEntry()

            // 2. Stream each companion file in 8 KiB chunks
            for (artifact in manifest.files) {
                val input = openInput(artifact) ?: continue
                input.use { stream ->
                    zos.putNextEntry(ZipEntry(artifact.filename))
                    while (true) {
                        val read = stream.read(buffer)
                        if (read <= 0) break
                        zos.write(buffer, 0, read)
                    }
                    zos.closeEntry()
                }
            }
            zos.flush()
        }
    }
}
