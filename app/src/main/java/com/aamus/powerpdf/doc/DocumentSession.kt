package com.aamus.powerpdf.doc

import android.content.Context
import android.net.Uri
import com.aamus.powerpdf.data.Files
import com.aamus.powerpdf.engine.PdfDoc
import com.aamus.powerpdf.engine.PdfEngine
import com.aamus.powerpdf.engine.Rights
import com.aamus.powerpdf.engine.Size
import com.aamus.powerpdf.engine.isSigned
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

/**
 * One open document: MuPDF's document, the private working copy it reads from, where it came
 * from, and its versions for undo.
 *
 * Undo keeps **versions**, not MuPDF's journal (decisions §9): after each change the changes are
 * appended to the working copy (MuPDF's incremental save — only what changed is written), and the
 * file length marks the version. Going back copies the file up to an earlier length into a fresh
 * working copy and opens it. A change that fails half way is dropped by reopening the last version.
 * All engine calls go through [engine] (one thread).
 */
class DocumentSession private constructor(
    val id: String,
    private val context: Context,
    doc: PdfDoc,
    work: File,
    /** Where the file came from; null for a new document made by a tool. */
    val source: Uri?,
    name: String,
    /** Android lets us write back to [source]. */
    val canOverwrite: Boolean,
    val recentId: String?,
) {
    var doc: PdfDoc = doc
        private set
    var work: File = work
        private set

    private val _name = MutableStateFlow(name)
    val name: StateFlow<String> = _name.asStateFlow()

    private val _state = MutableStateFlow(State(pageSizes = emptyList()))
    val state: StateFlow<State> = _state.asStateFlow()

    /** The copy made by the first "Save a copy"; later saves update it (desktop decisions §39). */
    var savedCopy: Uri? = null
    var savedCopyName: String? = null

    /** Shown once per open document, not each time the viewer comes back from a tool screen. */
    var formOffered = false
    var startToolUsed = false
    /** The viewer shows its editing tools (the Edit button was tapped); kept while a tool screen is on top (decisions §42). */
    var editing = false

    /** Text still being typed in a form field: committed before the document is written or read (decisions §52). */
    val typing = PendingTyping()

    /**
     * A version: the first [length] bytes of [file]. [appended]: the file is the one opened (or last
     * saved) with changes only appended to it, so its signatures still hold (decisions §50).
     */
    private data class Version(val file: File, val length: Long, val appended: Boolean = true)

    private val versions = ArrayList<Version>()
    private var position = 0
    private var savedPosition = 0
    /** Changes that have no version of their own (a new password) still count as unsaved. */
    private var forcedDirty = false

    data class State(
        val pageSizes: List<Size>,
        /** Bumped by every change, so pages and text are drawn again. */
        val version: Int = 0,
        val dirty: Boolean = false,
        val canUndo: Boolean = false,
        val canRedo: Boolean = false,
        val rights: Rights? = null,
        val encrypted: Boolean = false,
    ) {
        val pageCount: Int get() = pageSizes.size
    }

    /** Runs [block] on the engine thread with this document. */
    suspend fun <T> engine(block: (PdfDoc) -> T): T = PdfEngine.run { block(doc) }

    private fun refresh(bump: Boolean) {
        val d = doc
        val old = _state.value
        _state.value = State(
            pageSizes = d.pageSizes(),
            version = if (bump) old.version + 1 else old.version,
            dirty = forcedDirty || position != savedPosition,
            canUndo = position > 0,
            canRedo = position < versions.lastIndex,
            rights = d.rights(),
            encrypted = d.isEncrypted,
        )
    }

    /** Engine thread: opens [file] as the document, closing the old one. */
    private fun switchTo(file: File) {
        val password = doc.password
        val next = PdfEngine.open(file, password)
        val old = doc
        doc = next
        work = file
        old.close()
    }

    /** Engine thread: a fresh working copy holding the first [length] bytes of [from]. */
    private fun prefixCopy(from: File, length: Long): File {
        val out = Files.newWorkFile(context)
        RandomAccessFile(from, "r").use { src ->
            out.outputStream().use { o ->
                val buf = ByteArray(1 shl 16)
                var left = length
                while (left > 0) {
                    val n = src.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    o.write(buf, 0, n)
                    left -= n
                }
            }
        }
        return out
    }

    /** Engine thread: after a successful change, makes it a version. */
    private fun commit() {
        // A new change drops the versions that were undone.
        while (versions.size > position + 1) versions.removeAt(versions.lastIndex)
        val appended = doc.canSaveIncrementally && versions[position].appended
        if (doc.canSaveIncrementally) {
            doc.saveIncremental()
            // Reopen: after appending to its own file MuPDF still thinks the file has its old
            // length, cannot read back what it appended and misplaces the next save (seen on a
            // phone). MuPDF's own viewer reloads after saving in place too.
            switchTo(work)
        } else {
            // Some files (repaired on opening) cannot be appended to: write a full copy once.
            val next = Files.newWorkFile(context)
            doc.save(next, final = false)
            switchTo(next)
        }
        versions.add(Version(work, work.length(), appended))
        position = versions.lastIndex
        cleanFiles()
    }

    /** Deletes working files no version points at any more. */
    private fun cleanFiles() {
        val keep = versions.map { it.file.absolutePath }.toSet() + work.absolutePath
        Files.workDir(context).listFiles()?.forEach { f ->
            if (f.absolutePath !in keep && knownFiles.contains(f.absolutePath)) {
                f.delete()
                knownFiles.remove(f.absolutePath)
            }
        }
        knownFiles.addAll(keep)
    }

    private val knownFiles = HashSet<String>()

    /** One undoable change. If it fails, the document goes back to the last version. */
    suspend fun <T> change(label: String, block: (PdfDoc) -> T): T = PdfEngine.run {
        val result = try {
            doc.change(label) { block(doc) }
        } catch (e: Throwable) {
            // Drop whatever the failed change left in memory.
            runCatching { switchTo(prefixCopy(versions[position].file, versions[position].length)) }
            refresh(bump = true)
            throw e
        }
        commit()
        refresh(bump = true)
        PdfEngine.settle()
        result
    }

    private fun goTo(p: Int) {
        val v = versions[p]
        switchTo(prefixCopy(v.file, v.length))
        position = p
        cleanFiles()
        refresh(bump = true)
        PdfEngine.settle()
    }

    suspend fun undo() = PdfEngine.run { if (position > 0) goTo(position - 1) }

    suspend fun redo() = PdfEngine.run { if (position < versions.lastIndex) goTo(position + 1) }

    /** Marks the document changed without an undo step (a change that has none, like a new password). */
    fun markDirty() {
        forcedDirty = true
        _state.value = _state.value.copy(dirty = true)
    }

    /**
     * Writes the document to a new private file, with [extra] MuPDF write options (encryption).
     * Returns the file; the caller copies it where it belongs.
     */
    suspend fun writeOut(extra: String? = null): File {
        // What is still being typed in a form field goes in first (Save, Save as, Share, tools).
        typing.commit()
        return written(extra)
    }

    private suspend fun written(extra: String?): File = PdfEngine.run {
        if (extra == null && keepsSignatures()) {
            // A digitally signed file: the working copy is the file as it was opened with each
            // change appended to it (an incremental update, as Acrobat saves signed files), so
            // its signatures still hold. A full rewrite would break them (decisions §50).
            val v = versions[position]
            return@run prefixCopy(v.file, v.length)
        }
        val out = Files.newWorkFile(context)
        doc.save(out, final = true, extra = extra)
        out
    }

    /** Engine thread: signed, and every change so far appended to the file as it was opened or saved. */
    private fun keepsSignatures(): Boolean =
        versions.getOrNull(position)?.appended == true && runCatching { doc.isSigned() }.getOrDefault(false)

    /** Writes the document as Save would, to [target] (sharing). */
    suspend fun writeTo(target: File) {
        val written = writeOut()
        PdfEngine.run {
            try {
                written.copyTo(target, overwrite = true)
            } finally {
                written.delete()
            }
        }
    }

    /**
     * After the file was saved: reopen what was written as the new working copy, so memory holds
     * what is on disk. The versions start again from here (decisions §8).
     */
    suspend fun adoptSaved(written: File, newName: String? = null, password: String? = doc.password) = PdfEngine.run {
        val reopened = PdfEngine.open(written, password)
        val old = doc
        doc = reopened
        work = written
        old.close()
        versions.clear()
        versions.add(Version(written, written.length()))
        position = 0
        savedPosition = 0
        forcedDirty = false
        cleanFiles()
        newName?.let { _name.value = it }
        refresh(bump = true)
    }

    fun rename(name: String) {
        _name.value = name
    }

    suspend fun close() = PdfEngine.run {
        doc.close()
        (versions.map { it.file } + work).distinct().forEach { it.delete() }
    }

    companion object {
        /** Opens a working copy that is already on disk. */
        suspend fun open(
            context: Context,
            work: File,
            source: Uri?,
            name: String,
            canOverwrite: Boolean,
            recentId: String?,
            password: String? = null,
        ): DocumentSession = PdfEngine.run {
            val doc = PdfEngine.open(work, password)
            val s = DocumentSession(UUID.randomUUID().toString(), context.applicationContext, doc, work, source, name, canOverwrite, recentId)
            s.versions.add(Version(work, work.length()))
            s.knownFiles.add(work.absolutePath)
            s.refresh(bump = false)
            android.util.Log.i("PowerPDF", "opened: rights ${s.state.value.rights}, needs open password ${doc.needsOpenPassword}")
            s
        }

        /** A document a tool has just made: it counts as unsaved until it is saved somewhere. */
        suspend fun fromNew(context: Context, work: File, name: String): DocumentSession {
            val s = open(context, work, null, name, canOverwrite = false, recentId = null)
            s.markDirty()
            return s
        }
    }
}

/** Every open document, kept while the app runs (the screen may be rebuilt, the session stays). */
object Sessions {
    private val open = LinkedHashMap<String, DocumentSession>()

    fun add(s: DocumentSession) {
        open[s.id] = s
    }

    fun get(id: String): DocumentSession? = open[id]

    fun remove(id: String): DocumentSession? = open.remove(id)

    val all: List<DocumentSession> get() = open.values.toList()
}
