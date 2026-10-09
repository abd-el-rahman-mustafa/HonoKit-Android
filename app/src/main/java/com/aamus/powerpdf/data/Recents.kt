package com.aamus.powerpdf.data

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Recently opened files, newest first. A file picked in the system picker is reopened through its
 * lasting permission; one that came through "Open with" or Share usually has no lasting permission,
 * so a private copy is kept (up to [CACHE_LIMIT] in all) and reopened instead.
 */
data class RecentFile(
    val id: String,
    val uri: String,
    val name: String,
    val size: Long,
    val pages: Int,
    val openedAt: Long,
    /** Private copy, when the original cannot be reopened later. */
    val cached: String?,
    val starred: Boolean = false,
    /** The page the person was on. */
    val lastPage: Int = 0,
    /** [KIND_PDF] or [KIND_IMAGE]: a picture opens in the image viewer (decisions §42). */
    val kind: String = KIND_PDF,
) {
    val isImage: Boolean get() = kind == KIND_IMAGE

    companion object {
        const val KIND_PDF = "pdf"
        const val KIND_IMAGE = "image"
    }
}

class RecentsStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("recents", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val flow: StateFlow<List<RecentFile>> = state.asStateFlow()

    val thumbDir = File(context.filesDir, "thumbs").apply { mkdirs() }
    val cacheDir = File(context.filesDir, "recent-copies").apply { mkdirs() }

    private fun read(): List<RecentFile> = runCatching {
        val arr = JSONArray(prefs.getString("list", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            RecentFile(
                id = o.getString("id"),
                uri = o.getString("uri"),
                name = o.optString("name", "document.pdf"),
                size = o.optLong("size", -1),
                pages = o.optInt("pages", 0),
                openedAt = o.optLong("openedAt", 0),
                cached = o.optString("cached").takeIf { it.isNotEmpty() },
                starred = o.optBoolean("starred", false),
                lastPage = o.optInt("lastPage", 0),
                kind = o.optString("kind", RecentFile.KIND_PDF).ifEmpty { RecentFile.KIND_PDF },
            )
        }
    }.getOrDefault(emptyList())

    private fun write(list: List<RecentFile>) {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(
                JSONObject()
                    .put("id", r.id).put("uri", r.uri).put("name", r.name).put("size", r.size)
                    .put("pages", r.pages).put("openedAt", r.openedAt).put("cached", r.cached ?: "")
                    .put("starred", r.starred).put("lastPage", r.lastPage).put("kind", r.kind)
            )
        }
        prefs.edit().putString("list", arr.toString()).apply()
        state.value = list
    }

    fun find(uri: String): RecentFile? = state.value.firstOrNull { it.uri == uri }

    fun get(id: String): RecentFile? = state.value.firstOrNull { it.id == id }

    /** The recent file whose private copy [uri] is (a file:// Uri), if any. */
    fun byCopy(uri: android.net.Uri): RecentFile? =
        if (uri.scheme != "file") null else uri.path?.let { p -> state.value.firstOrNull { it.cached == p } }

    fun record(entry: RecentFile) {
        // The same file reached another way (Open with, then from inside the app) is one entry,
        // the newest; a star on the older one stays (decisions §48, RecentList).
        val same = RecentList.others(entry, state.value)
        val kept = if (!entry.starred && same.any { it.starred }) entry.copy(starred = true) else entry
        val rest = state.value.filterNot { it.id == entry.id || it.uri == entry.uri || same.any { s -> s.id == it.id } }
        val list = (listOf(kept) + rest).take(MAX)
        // Drop what fell off the end.
        state.value.filter { old -> list.none { it.id == old.id } }.forEach { forgetFiles(it) }
        write(list)
        trimCache(list)
    }

    fun update(id: String, change: (RecentFile) -> RecentFile) {
        write(state.value.map { if (it.id == id) change(it) else it })
    }

    fun remove(id: String) {
        state.value.firstOrNull { it.id == id }?.let { forgetFiles(it) }
        write(state.value.filterNot { it.id == id })
    }

    /** Removes a file from Recent: [id] and every other entry for the same file (RecentList). */
    fun removeFile(id: String) {
        val r = get(id) ?: return
        (RecentList.others(r, state.value) + r).forEach { remove(it.id) }
    }

    /** Changes [id] and every other entry for the same file (a star must not come back from a hidden twin). */
    fun updateFile(id: String, change: (RecentFile) -> RecentFile) {
        val r = get(id) ?: return
        val ids = (RecentList.others(r, state.value) + r).map { it.id }.toSet()
        write(state.value.map { if (it.id in ids) change(it) else it })
    }

    fun clear() {
        state.value.forEach { forgetFiles(it) }
        write(emptyList())
    }

    fun thumbFile(id: String) = File(thumbDir, "$id.png")

    fun saveThumb(id: String, bitmap: Bitmap) {
        runCatching { thumbFile(id).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) } }
    }

    /**
     * Where a private copy of a picture goes: a folder of its own, so the copy keeps the file's
     * real name (the image tools name their results after it).
     */
    fun imageCopy(id: String, name: String): File = File(File(cacheDir, id).apply { mkdirs() }, name.replace('/', '_'))

    private fun forgetFiles(r: RecentFile) {
        thumbFile(r.id).delete()
        r.cached?.let { deleteCopy(File(it), r.id) }
    }

    private fun deleteCopy(f: File, id: String) {
        f.delete()
        f.parentFile?.takeIf { it.name == id && it.parentFile == cacheDir }?.deleteRecursively()
    }

    /** Keeps private copies under the limit, dropping the oldest unstarred ones first. */
    private fun trimCache(list: List<RecentFile>) {
        var total = list.sumOf { r -> r.cached?.let { File(it).length() } ?: 0L }
        if (total <= CACHE_LIMIT) return
        val order = list.filter { it.cached != null }.sortedWith(compareBy<RecentFile> { it.starred }.thenBy { it.openedAt })
        val changed = list.toMutableList()
        for (r in order) {
            if (total <= CACHE_LIMIT) break
            val f = File(r.cached!!)
            total -= f.length()
            deleteCopy(f, r.id)
            val i = changed.indexOfFirst { it.id == r.id }
            if (i >= 0) changed[i] = r.copy(cached = null)
        }
        write(changed)
    }

    companion object {
        const val MAX = 40
        const val CACHE_LIMIT = 300L * 1024 * 1024
    }
}
