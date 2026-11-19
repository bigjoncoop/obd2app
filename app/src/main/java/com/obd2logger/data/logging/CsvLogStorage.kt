package com.obd2logger.data.logging

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.obd2logger.domain.model.SavedCsvLogSummary
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import javax.inject.Inject
import javax.inject.Singleton

class OpenCsvLogHandle(
    val uri: String,
    val filename: String,
    private val outputStream: OutputStream,
    private val writer: BufferedWriter
) {
    private val lock = Any()
    private var closed = false

    fun writeLine(line: String) {
        synchronized(lock) {
            if (closed) return
            writer.write(line)
            writer.newLine()
        }
    }

    fun flush() {
        synchronized(lock) {
            if (closed) return
            writer.flush()
            runCatching { outputStream.flush() }
        }
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { writer.flush() }
            runCatching { writer.close() }
            runCatching { outputStream.close() }
        }
    }
}

/**
 * Manages CSV log files in `Documents/OBDLogs` via MediaStore (API 29+)
 * or public external Documents directory (API 26-28).
 */
@Singleton
class CsvLogStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun openNewCsvLog(filename: String): OpenCsvLogHandle {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            openViaMediaStore(filename, "text/csv")
        } else {
            openViaLegacyStorage(filename)
        }
    }

    /**
     * Creates a companion file (e.g. `_mode06.csv`, `_extended.csv`, `_precleared.json`, `_session.zip`)
     * in `Documents/OBDLogs` and returns `(uriString, OutputStream)`.
     */
    fun openCompanionOutput(filename: String, mimeType: String): Pair<String, OutputStream> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOCUMENTS}/$OBD_LOGS_SUBDIR"
                )
            }
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values)
                ?: throw IOException("Unable to create MediaStore entry for $filename")
            val stream = resolver.openOutputStream(uri, "w")
                ?: throw IOException("Unable to open output stream for $uri")
            uri.toString() to stream
        } else {
            val handle = openViaLegacyStorage(filename)
            // Return an output stream backed by the legacy file via ContentResolver if possible
            val uri = Uri.parse(handle.uri)
            handle.close()
            val stream = context.contentResolver.openOutputStream(uri, "wt")
                ?: throw IOException("Unable to open legacy output stream for $uri")
            handle.uri to stream
        }
    }

    fun openInputStream(uriString: String): java.io.InputStream? {
        return runCatching {
            context.contentResolver.openInputStream(Uri.parse(uriString))
        }.getOrNull()
    }

    private fun openViaMediaStore(filename: String, mimeType: String): OpenCsvLogHandle {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOCUMENTS}/$OBD_LOGS_SUBDIR"
            )
        }
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: throw IOException("Unable to create MediaStore CSV entry for $filename")
        val stream = resolver.openOutputStream(uri, "w")
            ?: throw IOException("Unable to open output stream for $uri")
        val writer = BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8))
        return OpenCsvLogHandle(
            uri = uri.toString(),
            filename = filename,
            outputStream = stream,
            writer = writer
        )
    }

    @Suppress("DEPRECATION")
    private fun openViaLegacyStorage(filename: String): OpenCsvLogHandle {
        val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val targetDir = File(docsDir, OBD_LOGS_SUBDIR)
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            val fallbackDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), OBD_LOGS_SUBDIR)
            fallbackDir.mkdirs()
            val file = File(fallbackDir, filename)
            val stream = FileOutputStream(file, false)
            val writer = BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8))
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            return OpenCsvLogHandle(uri.toString(), filename, stream, writer)
        }
        val file = File(targetDir, filename)
        val stream = FileOutputStream(file, false)
        val writer = BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return OpenCsvLogHandle(uri.toString(), filename, stream, writer)
    }

    fun listSavedLogs(): List<SavedCsvLogSummary> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            listViaMediaStore()
        } else {
            listViaLegacyStorage()
        }
    }

    private fun listViaMediaStore(): List<SavedCsvLogSummary> {
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.RELATIVE_PATH
        )
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$OBD_LOGS_SUBDIR%", "%.csv")
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"

        val results = mutableListOf<SavedCsvLogSummary>()
        runCatching {
            resolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val modCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: continue
                    val size = cursor.getLong(sizeCol)
                    val dateModifiedSec = cursor.getLong(modCol)
                    val uri = ContentUris.withAppendedId(collection, id)
                    results.add(
                        SavedCsvLogSummary(
                            uri = uri.toString(),
                            filename = name,
                            sizeBytes = size,
                            lastModifiedMs = dateModifiedSec * 1000L
                        )
                    )
                }
            }
        }
        return results
    }

    @Suppress("DEPRECATION")
    private fun listViaLegacyStorage(): List<SavedCsvLogSummary> {
        val dirs = listOfNotNull(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), OBD_LOGS_SUBDIR),
            File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), OBD_LOGS_SUBDIR)
        )
        val files = dirs.flatMap { dir ->
            dir.listFiles { f -> f.isFile && f.name.endsWith(".csv", ignoreCase = true) }?.toList().orEmpty()
        }.sortedByDescending { it.lastModified() }

        return files.mapNotNull { file ->
            runCatching {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                SavedCsvLogSummary(
                    uri = uri.toString(),
                    filename = file.name,
                    sizeBytes = file.length(),
                    lastModifiedMs = file.lastModified()
                )
            }.getOrNull()
        }
    }

    fun queryFileSize(uriString: String): Long {
        val uri = Uri.parse(uriString)
        return runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                afd.length.takeIf { it >= 0 }
            } ?: 0L
        }.getOrDefault(0L)
    }

    fun deleteLog(uriString: String): Boolean {
        val uri = Uri.parse(uriString)
        return runCatching {
            context.contentResolver.delete(uri, null, null) > 0
        }.getOrDefault(false)
    }

    fun buildShareIntent(uriString: String, filename: String): Intent {
        val uri = Uri.parse(uriString)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "OBD2 Log: $filename")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(shareIntent, "Share $filename")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun buildOpenFileIntent(uriString: String): Intent {
        val uri = Uri.parse(uriString)
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "text/csv")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(viewIntent, "Open CSV Log")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun buildOpenFolderIntent(): Intent {
        val folderDocUri = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "primary:Documents/$OBD_LOGS_SUBDIR"
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(folderDocUri, DocumentsContract.Document.MIME_TYPE_DIR)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) != null) {
            return intent
        }
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/csv"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, folderDocUri)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    companion object {
        const val OBD_LOGS_SUBDIR = "OBDLogs"
        const val DISPLAY_FOLDER_PATH = "Documents/$OBD_LOGS_SUBDIR"
    }
}
