package com.aamus.powerpdf.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/**
 * Reading and writing through Android's storage framework. Nothing here goes online: files come
 * from the system picker, "Open with" or Share, and go back to a place the person chose.
 */
object Files {
    const val PDF = "application/pdf"
    const val FOLDER = "HonoKit"

    /** Where earlier versions saved (the app was HonoPDF until 2026-10-02): still listed. */
    val OLD_FOLDERS = listOf("HonoPDF")

    fun displayName(context: Context, uri: Uri): String {
        // A recent file's private copy is called by the recent file's name (PDF copies are "<id>.pdf").
        if (uri.scheme == "file") return runCatching { com.aamus.powerpdf.PowerPdfApp.instance.recents.byCopy(uri)?.name }.getOrNull() ?: File(uri.path ?: "document.pdf").name
        val picker = PickerName.isPicker(uri.authority, uri.path)
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0)
                    if (!name.isNullOrBlank()) return if (picker) pickerName(context, uri, name) else name
                }
            }
        }
        val last = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: return "document.pdf"
        return if (picker) pickerName(context, uri, last) else last
    }

    /**
     * The photo picker gives a media number as the name ("1000000063.jpg", UX review finding 12):
     * such a picture is named after the date it was taken instead ("Photo 2026-10-04 11.26.jpg").
     */
    private fun pickerName(context: Context, uri: Uri, raw: String): String {
        if (!PickerName.isNumberOnly(raw)) return raw
        val taken = runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATE_TAKEN), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
        }.getOrNull()
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val word = context.getString(com.aamus.powerpdf.R.string.photo_name)
        return PickerName.nameFor(raw, picker = true, takenMillis = taken, mime = mime, word = word)
    }

    /**
     * Renames a file HonoKit saved (the result screen's Rename): a file on disk, an item it put in
     * Downloads or Pictures, or a document from the system picker. Returns the Uri to use from now
     * on (a document may get a new one), or null when Android does not allow it.
     */
    fun rename(context: Context, uri: Uri, newName: String): Uri? = runCatching {
        when {
            uri.scheme == "file" -> {
                val f = File(uri.path ?: return null)
                val target = File(f.parentFile, newName)
                if (target.exists() || !f.renameTo(target)) null else Uri.fromFile(target).also {
                    MediaScannerConnection.scanFile(context, arrayOf(f.absolutePath, target.absolutePath), null, null)
                }
            }
            DocumentsContract.isDocumentUri(context, uri) -> DocumentsContract.renameDocument(context.contentResolver, uri, newName)
            uri.authority == MediaStore.AUTHORITY -> {
                val n = context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }, null, null)
                if (n > 0) uri else null
            }
            else -> null
        }
    }.onFailure { android.util.Log.w("PowerPDF", "rename $uri", it) }.getOrNull()

    fun size(context: Context, uri: Uri): Long {
        if (uri.scheme == "file") return File(uri.path ?: return -1).length()
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
            }
        }
        return -1
    }

    /** Copies what [uri] points at into [target]. */
    fun copyIn(context: Context, uri: Uri, target: File) {
        target.parentFile?.mkdirs()
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("The file could not be read.")
        input.use { i -> target.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
    }

    /** Replaces the contents of [uri] with [source]. */
    fun writeTo(context: Context, source: File, uri: Uri) {
        val out = runCatching { context.contentResolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: context.contentResolver.openOutputStream(uri, "w")
            ?: throw IOException("The file could not be written.")
        out.use { o -> source.inputStream().use { it.copyTo(o, 1 shl 16) } }
    }

    /** Whether Android lets this app write back to [uri] (a file picked with write access). */
    fun canWrite(context: Context, uri: Uri): Boolean {
        if (uri.scheme != "content") return false
        val granted = context.checkUriPermission(uri, android.os.Process.myPid(), android.os.Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED
        if (!granted) return false
        if (DocumentsContract.isDocumentUri(context, uri)) {
            runCatching {
                context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { c ->
                    if (c.moveToFirst()) return (c.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0
                }
            }
        }
        return true
    }

    /** "Report.pdf" → "Report (edited).pdf". */
    fun withSuffix(name: String, suffix: String): String {
        val base = name.removeSuffix(".pdf").removeSuffix(".PDF")
        return "$base ($suffix).pdf"
    }

    fun ensurePdfName(name: String): String = if (name.endsWith(".pdf", ignoreCase = true)) name else "$name.pdf"

    /** Android 8 and 9 need the storage permission to put files in Downloads. */
    fun downloadsNeedsPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

    /**
     * Saves [source] as a new file in Downloads › HonoPDF and returns its Uri. The system adds
     * " (1)" if the name is taken.
     */
    fun saveToDownloads(context: Context, source: File, name: String, mime: String = PDF): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Downloads could not be written.")
            try {
                resolver.openOutputStream(uri, "w")!!.use { o -> source.inputStream().use { it.copyTo(o, 1 shl 16) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Throwable) {
                resolver.delete(uri, null, null)
                throw e
            }
            return uri
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
        dir.mkdirs()
        val target = exclusive(dir, name, "pdf")
        source.inputStream().use { i -> target.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
        return Uri.fromFile(target)
    }

    /**
     * Android 8 and 9 write shared folders directly: the file is created exclusively (" (1)" and so
     * on when the name is taken), so nothing already lying there is written through.
     */
    private fun exclusive(dir: File, name: String, defaultExt: String): File {
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', defaultExt)
        for (n in 0 until 1000) {
            val f = if (n == 0) File(dir, name) else File(dir, if (ext.isEmpty() || ext == name) "$base ($n)" else "$base ($n).$ext")
            if (f.createNewFile()) return f
        }
        throw IOException("No free name for $name.")
    }

    /**
     * Saves a picture into Pictures › HonoPDF › [folder] and returns its Uri (PDF → pictures). The
     * system adds " (1)" if the name is taken.
     */
    fun saveToPictures(context: Context, bytes: ByteArray, folder: String, name: String, mime: String): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$FOLDER/$folder")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Pictures could not be written.")
            try {
                resolver.openOutputStream(uri, "w")!!.use { it.write(bytes) }
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Throwable) {
                resolver.delete(uri, null, null)
                throw e
            }
            return uri
        }
        @Suppress("DEPRECATION")
        val dir = File(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), FOLDER), folder)
        dir.mkdirs()
        val target = exclusive(dir, name, "")
        target.writeBytes(bytes)
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
        return Uri.fromFile(target)
    }

    /**
     * Deletes a picture this install saved earlier (an image tool's result that a new run with
     * other settings replaces; decisions §48). Android lets an app delete its own MediaStore items
     * without asking. True when it is gone.
     */
    fun deleteSaved(context: Context, uri: Uri): Boolean = runCatching {
        when {
            uri.scheme == "file" -> {
                val f = File(uri.path ?: return false)
                (f.delete() || !f.exists()).also { MediaScannerConnection.scanFile(context, arrayOf(f.absolutePath), null, null) }
            }
            else -> context.contentResolver.delete(uri, null, null) > 0
        }
    }.onFailure { android.util.Log.w("PowerPDF", "delete $uri", it) }.getOrDefault(false)

    /** A private working folder for one document or job; cleared when the app starts. */
    fun workDir(context: Context): File = File(context.cacheDir, "work").apply { mkdirs() }

    /** A new working file, created exclusively under a random name (decisions §52). */
    fun newWorkFile(context: Context, ext: String = "pdf"): File = com.aamus.powerpdf.engine.Staging.create(workDir(context), "", ".$ext")

    /** Copies for other apps (Share, Open in…), served by FileProvider; cleared at start. */
    fun shareDir(context: Context): File = File(context.cacheDir, "share").apply { mkdirs() }

    fun shareUri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    /** Deletes leftovers of an earlier run (work copies, shared copies older than a day). */
    fun cleanUp(context: Context) {
        runCatching { File(context.cacheDir, "work").deleteRecursively() }
        val dayAgo = System.currentTimeMillis() - 24L * 3600 * 1000
        runCatching { shareDir(context).listFiles()?.filter { it.lastModified() < dayAgo }?.forEach { it.deleteRecursively() } }
        runCatching { File(context.cacheDir, "camera").listFiles()?.filter { it.lastModified() < dayAgo }?.forEach { it.delete() } }
    }

    fun formatSize(bytes: Long, arabic: Boolean): String {
        if (bytes < 0) return ""
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return if (arabic) {
            when {
                mb >= 1 -> String.format(java.util.Locale.US, "%.1f م.ب", mb)
                kb >= 1 -> String.format(java.util.Locale.US, "%.0f ك.ب", kb)
                else -> "$bytes بايت"
            }
        } else {
            when {
                mb >= 1 -> String.format(java.util.Locale.US, "%.1f MB", mb)
                kb >= 1 -> String.format(java.util.Locale.US, "%.0f KB", kb)
                else -> "$bytes B"
            }
        }
    }
}
