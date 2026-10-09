package com.aamus.powerpdf

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aamus.powerpdf.data.CopyPlace
import com.aamus.powerpdf.data.Files
import com.aamus.powerpdf.data.RecentFile
import com.aamus.powerpdf.data.SaveMode
import com.aamus.powerpdf.doc.DocumentSession
import com.aamus.powerpdf.doc.Sessions
import com.aamus.powerpdf.engine.PdfEngine
import com.aamus.powerpdf.engine.PdfException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

enum class ToolId(val needsDocument: Boolean, val makesFile: Boolean) {
    Comment(true, false),
    Sign(true, false),
    AddText(true, false),
    EditText(true, false),
    Erase(true, false),
    Forms(true, false),
    Redact(true, false),
    Organize(true, false),
    Crop(true, false),
    Watermark(true, false),
    PageNumbers(true, false),
    Merge(false, true),
    Split(true, true),
    Images(false, true),
    Scan(false, true),
    ScanId(false, true),
    ScanBook(false, true),
    Recognize(true, false),
    ImageToText(false, false),
    Blank(false, true),
    Compress(true, true),
    Protect(true, true),
    Unlock(true, true),
    ToImages(true, true),
    ToText(true, true),
    Compare(true, true),
    // Image tools (decisions §34): pictures in, pictures out.
    ImgCompress(false, true),
    ImgResize(false, true),
    ImgCrop(false, true),
    ImgConvert(false, true),
    ImgRotate(false, true),
    ImgWatermark(false, true),
    ImgInfo(false, true),
    ImgUpscale(false, true),
    ImgRemoveBg(false, true),
    ImgBlur(false, true),
    ImgEditor(false, true),
}

sealed interface Screen {
    data object Home : Screen
    /**
     * A PDF. It opens in the clean reader (decisions §42); [startTool] goes straight into a tool
     * picked on Home, [edit] opens with the editing tools showing ("Open with" › Edit).
     */
    data class Viewer(val sessionId: String, val startTool: ToolId? = null, val edit: Boolean = false) : Screen
    /** Pictures in the image viewer, [index] first (decisions §42). */
    data class ImageViewer(val uris: List<Uri>, val index: Int = 0) : Screen
    /** A tool screen. [ownsSession]: the tool opened the document itself and closes it when left. */
    data class Tool(val tool: ToolId, val sessionId: String? = null, val inputs: List<Uri> = emptyList(), val ownsSession: Boolean = false) : Screen
    /**
     * What a tool made (decisions §45): one screen for PDF and image tools alike — the files,
     * where they went, Open, Share, Rename, and "Continue with another tool". [again]: offer
     * "Start over" (the tool again, from the chooser).
     */
    data class Result(
        val tool: ToolId,
        val files: List<SavedItem>,
        val title: String?,
        val place: String?,
        val mime: String,
        val again: Boolean = true,
        /** How it was made, said plainly ("Enlarged by the AI model on this phone · 12 s"; decisions §48). */
        val note: String? = null,
        /** The screen under this one: an image tool board (Adjust settings goes back to it) or the photo editor. */
        val under: ResultUnder = ResultUnder.Nothing,
    ) : Screen
    data object Settings : Screen
    data object Licences : Screen
}

/**
 * One file on the result screen. [detail] replaces the size line (pixels and format for a
 * picture), [badge] is a short gain ("40% smaller"), [error] a file that failed.
 */
data class SavedItem(
    val uri: Uri,
    val name: String? = null,
    val detail: String? = null,
    val badge: String? = null,
    val error: String? = null,
    /** The picture it was made from, for Before/After on the result screen (decisions §48). */
    val source: Uri? = null,
)

/** What stays under the result screen (decisions §48). */
enum class ResultUnder {
    /** The tool was closed: Back returns to where it was started. */
    Nothing,
    /** An image tool board with its pictures and options: "Adjust settings" returns to it; Back leaves both. */
    Board,
    /** The photo editor: Back returns to the photo, as before. */
    Editor,
}

/** A file chosen in the chooser sheet (decisions §45): one of HonoKit's recent files, or one from Android's pickers. */
sealed interface Picked {
    data class Recent(val file: RecentFile) : Picked
    data class System(val uri: Uri) : Picked
}

/** The chooser sheet, asked for by a tool: PDFs or pictures, one or several. */
data class ChooserRequest(val kind: com.aamus.powerpdf.data.Choosing.Kind, val multiple: Boolean, val tool: ToolId?, val done: (List<Picked>) -> Unit)

/** A message for the snackbar, with an optional action. */
data class UiMessage(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null, val long: Boolean = false)

/** Something only the activity can do (a system picker, a permission), asked for by the model. */
sealed interface SystemRequest {
    data class CreateDocument(val name: String, val mime: String = Files.PDF, val done: (Uri?) -> Unit) : SystemRequest
    data class StoragePermission(val done: (Boolean) -> Unit) : SystemRequest
}

data class PasswordRequest(val name: String, val wrong: Boolean, val done: (String?) -> Unit)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val context get() = getApplication<PowerPdfApp>()
    val settings get() = context.settings
    val recents get() = context.recents

    val stack = mutableStateListOf<Screen>(Screen.Home)
    val current: Screen get() = stack.last()

    var busy by mutableStateOf<String?>(null)

    /**
     * For work a screen starts that must finish even if the screen goes away: a tool mode's Done
     * writes its last change and closes the mode in the same moment (review finding 2026-10-01:
     * the mode's own scope was cancelled half way through the change).
     */
    val appScope: kotlinx.coroutines.CoroutineScope get() = viewModelScope

    /** Set by the viewer while a tool mode shows its hint and bar: snackbars sit above them (dp). */
    var snackbarLift by mutableStateOf<Int?>(null)
    /** Home's tab: 0 Home, 1 PDF tools, 2 Image tools, 3 Files (decisions §42). */
    var homeTab by mutableStateOf(0)
    var passwordRequest by mutableStateOf<PasswordRequest?>(null)
    var systemRequest by mutableStateOf<SystemRequest?>(null)

    /** Bumped after every save, so the Files tab reads its list again. */
    var savedVersion by mutableStateOf(0)

    /**
     * Files a tool just saved: the result screen (decisions §45; it was a sheet for PDF tools and
     * a screen for image tools). [title] replaces "Saved" (a compress result, a split count);
     * [place] is where it went.
     */
    fun showSaved(tool: ToolId, uris: List<Uri>, place: String?, title: String? = null, mime: String = Files.PDF, again: Boolean = true) {
        showResult(tool, uris.map { SavedItem(it) }, place, title, mime, again)
    }

    fun showResult(tool: ToolId, files: List<SavedItem>, place: String?, title: String? = null, mime: String = Files.PDF, again: Boolean = true, note: String? = null, under: ResultUnder = ResultUnder.Nothing) {
        savedVersion++
        if (files.isNotEmpty()) push(Screen.Result(tool, files, title, place, mime, again, note, under))
    }

    /**
     * Leaves the result screen for good (Back, Continue with, Start over): with an image tool
     * board under it, the board goes too, so Back returns to where the tool was started.
     */
    fun leaveResult() {
        val r = current as? Screen.Result
        pop()
        if (r?.under == ResultUnder.Board && current is Screen.Tool) pop()
    }

    /**
     * State a screen keeps while another is on top of it (an image tool board under its result,
     * decisions §48). Only the top screen is composed, so it lives here, per screen, and goes with it.
     */
    private val kept = java.util.IdentityHashMap<Screen, Any>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> keep(screen: Screen, make: () -> T): T = kept.getOrPut(screen, make) as T

    private fun forgetKept() {
        val live = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Screen, Boolean>()).apply { addAll(stack) }
        kept.keys.retainAll(live)
    }

    // --- choosing a file (decisions §45) ----------------------------------------------------------

    var chooser by mutableStateOf<ChooserRequest?>(null)

    /** Asks for files of one kind: HonoKit's recent ones first, then Android's pickers. */
    fun choose(kind: com.aamus.powerpdf.data.Choosing.Kind, multiple: Boolean, tool: ToolId? = null, done: (List<Picked>) -> Unit) {
        chooser = ChooserRequest(kind, multiple, tool, done)
    }

    /**
     * Starts a tool from Home, a hub or the pinned row: one way in for every tool that needs a
     * file — the chooser, then the tool with the file(s) in. Tools that make something from
     * nothing (Scan, a blank PDF) open at once.
     */
    fun startTool(tool: ToolId) {
        val pdf = com.aamus.powerpdf.data.Choosing.Kind.Pdf
        val image = com.aamus.powerpdf.data.Choosing.Kind.Image
        when {
            tool.needsDocument -> choose(pdf, multiple = false, tool = tool) { picked ->
                val p = picked.firstOrNull() ?: return@choose
                openPicked(p) { s ->
                    if (tool.makesFile) push(Screen.Tool(tool, sessionId = s.id, ownsSession = true))
                    else push(Screen.Viewer(s.id, startTool = tool))
                }
            }
            tool == ToolId.Merge -> choose(pdf, multiple = true, tool = tool) { picked -> withUris(picked) { push(Screen.Tool(tool, inputs = it)) } }
            tool == ToolId.ImgEditor || tool == ToolId.ImageToText -> choose(image, multiple = false, tool = tool) { picked -> withUris(picked) { push(Screen.Tool(tool, inputs = it)) } }
            tool == ToolId.Images || tool.name.startsWith("Img") -> choose(image, multiple = true, tool = tool) { picked -> withUris(picked) { push(Screen.Tool(tool, inputs = it)) } }
            else -> push(Screen.Tool(tool))
        }
    }

    /** The Uris of chosen files (a recent file's original, or its private copy when that is gone). */
    fun withUris(picked: List<Picked>, then: (List<Uri>) -> Unit) {
        viewModelScope.launch {
            val uris = picked.mapNotNull { uriOf(it) }
            if (uris.isNotEmpty()) then(uris)
        }
    }

    private suspend fun uriOf(p: Picked): Uri? = when (p) {
        is Picked.System -> p.uri
        is Picked.Recent -> withContext(Dispatchers.IO) {
            val r = p.file
            val uri = Uri.parse(r.uri)
            val readable = runCatching { context.contentResolver.openInputStream(uri)?.close(); true }.getOrDefault(false)
            val cached = r.cached?.let { File(it) }?.takeIf { it.isFile }
            when {
                readable -> uri
                cached != null -> Uri.fromFile(cached)
                else -> null
            }
        } ?: run {
            say(str(R.string.file_missing), long = true)
            null
        }
    }

    /** Opens a chosen PDF as a document and hands it on (to a tool). */
    fun openPicked(p: Picked, then: (DocumentSession) -> Unit) {
        when (p) {
            is Picked.System -> openUri(p.uri, persistable = true, then)
            is Picked.Recent -> openRecent(p.file, then)
        }
    }

    /**
     * "Continue with another tool" on the result screen: the file(s) just made go straight into
     * [next] (PDF tools take the one PDF; image tools take every picture).
     */
    fun continueWith(next: ToolId, uris: List<Uri>) {
        leaveResult()
        when {
            next == ToolId.Merge -> push(Screen.Tool(next, inputs = uris))
            next.needsDocument -> uris.firstOrNull()?.let { u ->
                openUri(u, persistable = true) { s ->
                    if (next.makesFile) push(Screen.Tool(next, sessionId = s.id, ownsSession = true))
                    else push(Screen.Viewer(s.id, startTool = next))
                }
            }
            else -> push(Screen.Tool(next, inputs = uris))
        }
    }

    private val messageChannel = Channel<UiMessage>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    fun say(text: String, action: String? = null, long: Boolean = false, onAction: (() -> Unit)? = null) {
        messageChannel.trySend(UiMessage(text, action, onAction, long))
    }

    fun str(id: Int, vararg args: Any): String = context.getString(id, *args)

    fun plural(id: Int, count: Int, vararg args: Any): String = context.resources.getQuantityString(id, count, *args)

    // --- navigation ---------------------------------------------------------------------------

    fun push(screen: Screen) {
        stack.add(screen)
    }

    fun replaceTop(screen: Screen) {
        stack[stack.lastIndex] = screen
        forgetKept()
    }

    /** Leaves the top screen, closing a document only that screen used. */
    fun pop() {
        if (stack.size <= 1) return
        val gone = stack.removeAt(stack.lastIndex)
        forgetKept()
        val id = when (gone) {
            is Screen.Viewer -> gone.sessionId
            is Screen.Tool -> if (gone.ownsSession) gone.sessionId else null
            else -> null
        }
        if (id != null && stack.none { it.usesSession(id) }) closeSession(id)
    }

    private fun Screen.usesSession(id: String) = (this is Screen.Viewer && sessionId == id) || (this is Screen.Tool && sessionId == id)

    fun goHome() {
        while (stack.size > 1) pop()
    }

    private fun closeSession(id: String) {
        val s = Sessions.remove(id) ?: return
        viewModelScope.launch { runCatching { s.close() } }
    }

    // --- opening ------------------------------------------------------------------------------

    /**
     * Handles an intent from another app: Open with (view or edit, a PDF or a picture; decisions
     * §42), Share.
     */
    fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_VIEW, Intent.ACTION_EDIT -> {
                val uri = intent.data ?: return
                val edit = intent.action == Intent.ACTION_EDIT
                val declared = intent.type
                viewModelScope.launch {
                    val kind = withContext(Dispatchers.IO) { kindOf(uri, declared) }
                    when (OpenRoute.of(edit, kind)) {
                        OpenRoute.Route.ViewPdf -> openUri(uri, persistable = false)
                        OpenRoute.Route.EditPdf -> openUri(uri, persistable = false) { s -> push(Screen.Viewer(s.id, edit = true)) }
                        // The other app's permission to read the picture ends with this visit, and
                        // the viewer, the editor and the tools read it again later: a private copy.
                        OpenRoute.Route.ViewImage -> importImage(uri)?.let { push(Screen.ImageViewer(listOf(it))) }
                        OpenRoute.Route.EditImage -> importImage(uri)?.let { push(Screen.Tool(ToolId.ImgEditor, inputs = listOf(it))) }
                    }
                }
            }
            Intent.ACTION_SEND -> {
                val uri = intent.streamUri() ?: return
                val type = intent.type ?: context.contentResolver.getType(uri) ?: ""
                if (type.startsWith("image/")) push(Screen.Tool(ToolId.Images, inputs = listOf(uri)))
                else openUri(uri, persistable = false)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = intent.streamUris()
                if (uris.isEmpty()) return
                val images = uris.filter { (context.contentResolver.getType(it) ?: intent.type ?: "").startsWith("image/") }
                val pdfs = uris - images.toSet()
                when {
                    pdfs.size >= 2 -> push(Screen.Tool(ToolId.Merge, inputs = pdfs))
                    images.isNotEmpty() -> push(Screen.Tool(ToolId.Images, inputs = images))
                    pdfs.size == 1 -> openUri(pdfs.first(), persistable = false)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.streamUri(): Uri? = if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun Intent.streamUris(): List<Uri> =
        (if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else getParcelableArrayListExtra(Intent.EXTRA_STREAM)) ?: emptyList()

    /** What a file from another app is: its first bytes, the type it was sent with, its name. */
    private fun kindOf(uri: Uri, declared: String?): OpenRoute.Kind {
        val cr = context.contentResolver
        val head = runCatching {
            cr.openInputStream(uri)?.use { s ->
                val b = ByteArray(64)
                var n = 0
                while (n < b.size) {
                    val r = s.read(b, n, b.size - n)
                    if (r <= 0) break
                    n += r
                }
                b.copyOf(n)
            }
        }.getOrNull()
        val mime = declared?.takeIf { it.isNotBlank() } ?: runCatching { cr.getType(uri) }.getOrNull()
        val name = runCatching { Files.displayName(context, uri) }.getOrNull()
        return OpenRoute.kindOf(mime, name, head)
    }

    /**
     * Copies a picture another app handed over into a private folder of its own (under its real
     * name) and adds it to Recent files. Returns the copy, or null if it could not be read.
     */
    private suspend fun importImage(uri: Uri): Uri? {
        busy = str(R.string.opening)
        try {
            return withContext(Dispatchers.IO) {
                val name = Files.displayName(context, uri)
                val existing = recents.find(uri.toString())
                val id = existing?.id ?: UUID.randomUUID().toString()
                val copy = recents.imageCopy(id, name)
                Files.copyIn(context, uri, copy)
                val local = Uri.fromFile(copy)
                rememberImage(uri, local, name, id, existing, copy)
                local
            }
        } catch (e: Throwable) {
            android.util.Log.w("PowerPDF", "could not read picture $uri", e)
            say(str(R.string.img_cannot_read))
            return null
        } finally {
            busy = null
        }
    }

    /** Shows pictures in the image viewer, [index] first, and adds that one to Recent files. */
    fun openImages(uris: List<Uri>, index: Int = 0, record: Boolean = true) {
        if (uris.isEmpty()) return
        val at = index.coerceIn(0, uris.lastIndex)
        push(Screen.ImageViewer(uris, at))
        if (record) viewModelScope.launch(Dispatchers.IO) {
            val uri = uris[at]
            runCatching {
                val name = Files.displayName(context, uri)
                val existing = recents.find(uri.toString())
                val id = existing?.id ?: UUID.randomUUID().toString()
                // A picture the app can open again later is listed as it is; any other is copied.
                val lasting = uri.scheme == "file" ||
                    context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission } ||
                    (uri.authority == "media" && uri.path?.contains("picker") != true)
                val copy = if (lasting) null else recents.imageCopy(id, name).also { Files.copyIn(context, uri, it) }
                rememberImage(uri, uri, name, id, existing, copy)
            }.onFailure { android.util.Log.w("PowerPDF", "recent picture", it) }
        }
    }

    /** Adds a picture to Recent files, with a small thumbnail. Call off the main thread. */
    private fun rememberImage(original: Uri, readFrom: Uri, name: String, id: String, existing: RecentFile?, copy: File?) {
        val size = Files.size(context, readFrom).takeIf { it >= 0 } ?: Files.size(context, original)
        recents.record(
            RecentFile(
                id = id,
                uri = original.toString(),
                name = name,
                size = size,
                pages = 0,
                openedAt = System.currentTimeMillis(),
                cached = copy?.absolutePath ?: existing?.cached,
                starred = existing?.starred ?: false,
                kind = RecentFile.KIND_IMAGE,
            )
        )
        runCatching {
            val thumb = com.aamus.powerpdf.engine.img.ImageKit.decode(context.contentResolver, readFrom, 240)
            recents.saveThumb(id, thumb)
            thumb.recycle()
        }
    }

    /** Opens a picture from Recent files: the original if it can still be read, else the private copy. */
    private fun openRecentImage(r: RecentFile) {
        viewModelScope.launch {
            val uri = Uri.parse(r.uri)
            val readable = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.close(); true }.getOrDefault(false) }
            val cached = r.cached?.let { File(it) }?.takeIf { it.isFile }
            val use = when {
                readable -> uri
                cached != null -> Uri.fromFile(cached)
                else -> {
                    say(str(R.string.file_missing), long = true)
                    return@launch
                }
            }
            recents.record(r.copy(openedAt = System.currentTimeMillis()))
            push(Screen.ImageViewer(listOf(use)))
        }
    }

    /** Opens a PDF and shows it (or hands it to [then], e.g. a tool). */
    fun openUri(uri: Uri, persistable: Boolean, then: ((DocumentSession) -> Unit)? = null) {
        viewModelScope.launch {
            val session = loadSession(uri, persistable) ?: return@launch
            if (then != null) then(session) else push(Screen.Viewer(session.id))
        }
    }

    fun openRecent(r: RecentFile, then: ((DocumentSession) -> Unit)? = null) {
        if (r.isImage) return openRecentImage(r)
        viewModelScope.launch {
            val uri = Uri.parse(r.uri)
            val readable = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.close(); true }.getOrDefault(false) }
            val session = when {
                readable -> loadSession(uri, persistable = r.cached == null, knownName = r.name)
                r.cached != null && File(r.cached).isFile -> loadSession(uri, persistable = false, fromCache = File(r.cached), knownName = r.name)
                else -> {
                    say(str(R.string.file_missing), long = true)
                    null
                }
            } ?: return@launch
            if (then != null) then(session) else push(Screen.Viewer(session.id))
        }
    }

    /** Opens [uri] into a new session, asking for a password if needed. Null if it could not be opened. */
    suspend fun loadSession(uri: Uri, persistable: Boolean, fromCache: File? = null, knownName: String? = null): DocumentSession? {
        busy = str(R.string.opening)
        try {
            // A recent file reopened from its private copy (or whose name Android no longer gives,
            // the "Open with" grant having ended) keeps its own name, not the media number
            // ("1000000060", found while testing 0.8.1).
            val shown = withContext(Dispatchers.IO) { Files.displayName(context, uri) }
            val name = if (knownName != null && (fromCache != null || shown == uri.lastPathSegment)) knownName else shown
            val work = Files.newWorkFile(context)
            try {
                withContext(Dispatchers.IO) {
                    if (fromCache != null) fromCache.copyTo(work, overwrite = true) else Files.copyIn(context, uri, work)
                }
            } catch (e: Throwable) {
                android.util.Log.w("PowerPDF", "could not read $uri", e)
                work.delete()
                say(str(R.string.error_read))
                return null
            }
            val canOverwrite = fromCache == null && withContext(Dispatchers.IO) { Files.canWrite(context, uri) }
            // A recent file's private copy (chosen in the chooser after the original went away)
            // is that recent file, not a new one.
            val existing = recents.find(uri.toString()) ?: recents.byCopy(uri)
            val recentId = existing?.id ?: UUID.randomUUID().toString()
            var password: String? = null
            var wrong = false
            while (true) {
                try {
                    val s = DocumentSession.open(context, work, uri, name, canOverwrite, recentId, password)
                    Sessions.add(s)
                    remember(s, uri, persistable, existing, recentId, fromCache)
                    return s
                } catch (e: PdfException) {
                    when (e.code) {
                        PdfException.Code.PasswordRequired, PdfException.Code.WrongPassword -> {
                            busy = null
                            password = askPassword(name, wrong || e.code == PdfException.Code.WrongPassword) ?: run {
                                work.delete()
                                return null
                            }
                            wrong = true
                            busy = str(R.string.opening)
                        }
                        else -> {
                            android.util.Log.w("PowerPDF", "could not open $uri", e)
                            work.delete()
                            say(errorText(e))
                            return null
                        }
                    }
                }
            }
        } finally {
            busy = null
        }
    }

    /**
     * Opens another PDF for a tool to read from (Insert pages): a private copy, asking for its
     * password when it has one. Null if it could not be read or the person cancelled. The caller
     * closes the document on the engine thread and deletes the file.
     */
    suspend fun openInput(uri: Uri): Pair<com.aamus.powerpdf.engine.PdfDoc, File>? {
        val name = withContext(Dispatchers.IO) { Files.displayName(context, uri) }
        val file = Files.newWorkFile(context)
        try {
            withContext(Dispatchers.IO) { Files.copyIn(context, uri, file) }
        } catch (e: Throwable) {
            file.delete()
            say(str(R.string.error_read))
            return null
        }
        var password: String? = null
        var wrong = false
        while (true) {
            try {
                val doc = PdfEngine.run { PdfEngine.open(file, password) }
                return doc to file
            } catch (e: PdfException) {
                if (e.code == PdfException.Code.PasswordRequired || e.code == PdfException.Code.WrongPassword) {
                    password = askPassword(name, wrong || e.code == PdfException.Code.WrongPassword) ?: run {
                        file.delete()
                        return null
                    }
                    wrong = true
                } else {
                    file.delete()
                    say(errorText(e))
                    return null
                }
            } catch (e: Throwable) {
                file.delete()
                say(errorText(e))
                return null
            }
        }
    }

    private suspend fun askPassword(name: String, wrong: Boolean): String? = suspendCancellableCoroutine { cont ->
        passwordRequest = PasswordRequest(name, wrong) { pw ->
            passwordRequest = null
            if (cont.isActive) cont.resume(pw)
        }
    }

    /** Adds the file to Recent files, with a thumbnail and, when needed, a private copy. */
    private suspend fun remember(s: DocumentSession, uri: Uri, persistable: Boolean, existing: RecentFile?, id: String, fromCache: File?) {
        val size = withContext(Dispatchers.IO) { Files.size(context, uri).takeIf { it >= 0 } ?: s.work.length() }
        var cached = existing?.cached
        if (!persistable && fromCache == null) {
            cached = withContext(Dispatchers.IO) {
                runCatching {
                    val f = File(recents.cacheDir, "$id.pdf")
                    s.work.copyTo(f, overwrite = true)
                    f.absolutePath
                }.getOrNull()
            }
        }
        val entry = RecentFile(
            id = id,
            // Opened from its private copy: still the same recent file, under its first address.
            uri = existing?.uri ?: uri.toString(),
            name = s.name.value,
            size = size,
            pages = s.state.value.pageCount,
            openedAt = System.currentTimeMillis(),
            cached = cached,
            starred = existing?.starred ?: false,
            lastPage = existing?.lastPage ?: 0,
        )
        recents.record(entry)
        runCatching {
            val thumb = s.engine { it.renderWidth(0, 180) }
            withContext(Dispatchers.IO) { recents.saveThumb(id, thumb) }
            thumb.recycle()
        }
    }

    fun rememberPage(session: DocumentSession, page: Int) {
        val id = session.recentId ?: return
        if (recents.get(id) != null) recents.update(id) { it.copy(lastPage = page) }
    }

    fun errorText(e: Throwable): String = when (e) {
        is PdfException -> when (e.code) {
            PdfException.Code.Corrupt -> str(R.string.error_corrupt)
            PdfException.Code.NotPdf -> str(R.string.error_not_pdf)
            PdfException.Code.Restricted -> str(R.string.error_restricted)
            PdfException.Code.WrongPassword -> str(R.string.wrong_password)
            PdfException.Code.EditUnsafe -> str(R.string.error_edit_unsafe)
            PdfException.Code.Signed -> str(R.string.error_signed)
            PdfException.Code.DynamicXfa -> str(R.string.xfa_dynamic)
            else -> str(R.string.error_generic, e.message ?: "")
        }
        is com.aamus.powerpdf.ocr.OcrException.Unsupported -> str(R.string.ocr_64bit)
        else -> str(R.string.error_generic, e.message ?: e.javaClass.simpleName)
    }

    // --- saving -------------------------------------------------------------------------------

    /**
     * Saves the document the way Settings says (a copy by default). [forceCopy] is "Save a copy";
     * [askWhere] is "Save as…". Returns true when saved.
     */
    suspend fun save(session: DocumentSession, forceCopy: Boolean = false, askWhere: Boolean = false, extra: String? = null, newPassword: String? = null): Boolean {
        val prefs = settings.value
        busy = str(R.string.saving)
        try {
            val written = session.writeOut(extra)
            val overwrite = !forceCopy && !askWhere && prefs.saveMode == SaveMode.Overwrite && session.source != null
            if (overwrite && session.canOverwrite) {
                withContext(Dispatchers.IO) { Files.writeTo(context, written, session.source!!) }
                session.adoptSaved(written, password = newPassword ?: session.doc.password)
                refreshRecent(session, session.source!!)
                say(str(R.string.saved_over))
                return true
            }
            // A copy: update the one made earlier, or make a new one.
            val earlier = session.savedCopy
            val target: Uri
            val place: String
            if (earlier != null && !askWhere && !forceCopy) {
                withContext(Dispatchers.IO) { Files.writeTo(context, written, earlier) }
                target = earlier
                place = session.savedCopyName ?: str(R.string.downloads_place)
            } else {
                val name = copyName(session)
                if (askWhere || prefs.copyPlace == CopyPlace.Ask) {
                    busy = null
                    val uri = createDocument(name) ?: return false
                    busy = str(R.string.saving)
                    withContext(Dispatchers.IO) { Files.writeTo(context, written, uri) }
                    target = uri
                    place = withContext(Dispatchers.IO) { Files.displayName(context, uri) }
                } else {
                    if (Files.downloadsNeedsPermission(context)) {
                        busy = null
                        if (!askStoragePermission()) {
                            say(str(R.string.storage_permission))
                            return false
                        }
                        busy = str(R.string.saving)
                    }
                    target = withContext(Dispatchers.IO) { Files.saveToDownloads(context, written, name) }
                    val shown = withContext(Dispatchers.IO) { Files.displayName(context, target) }
                    place = "${str(R.string.downloads_place)} › $shown"
                }
                session.savedCopy = target
                session.savedCopyName = place
            }
            val shownName = withContext(Dispatchers.IO) { Files.displayName(context, target) }
            session.adoptSaved(written, newName = shownName, password = newPassword ?: session.doc.password)
            refreshRecent(session, target)
            if (overwrite && !session.canOverwrite) say(str(R.string.cannot_overwrite), long = true)
            else say(str(R.string.saved_to, com.aamus.powerpdf.ui.bidi(place)), long = true)
            return true
        } catch (e: Throwable) {
            say(str(R.string.save_failed, e.message ?: e.javaClass.simpleName), long = true)
            return false
        } finally {
            busy = null
        }
    }

    private fun copyName(session: DocumentSession): String {
        val base = session.name.value
        return if (session.source == null) Files.ensurePdfName(base) else Files.withSuffix(base, editedWord())
    }

    private fun editedWord(): String = if (context.resources.configuration.locales[0].language == "ar") "معدّل" else "edited"

    private suspend fun refreshRecent(session: DocumentSession, uri: Uri) {
        val id = session.recentId ?: UUID.randomUUID().toString()
        val old = recents.get(id)
        val entryId = if (old != null && old.uri == uri.toString()) id else UUID.randomUUID().toString()
        // A thumbnail for the saved file too.
        runCatching {
            val thumb = session.engine { it.renderWidth(0, 180) }
            withContext(Dispatchers.IO) { recents.saveThumb(entryId, thumb) }
            thumb.recycle()
        }
        recents.record(
            RecentFile(
                id = entryId,
                uri = uri.toString(),
                name = session.name.value,
                size = session.work.length(),
                pages = session.state.value.pageCount,
                openedAt = System.currentTimeMillis(),
                cached = null,
                starred = old?.starred ?: false,
            )
        )
    }

    /** Saves a file a tool made (merge, compress…) as a new file, and returns where it went. */
    suspend fun saveNewFile(file: File, name: String, mime: String = Files.PDF): Pair<Uri, String>? {
        val prefs = settings.value
        return try {
            if (prefs.copyPlace == CopyPlace.Ask) {
                val uri = createDocument(name, mime) ?: return null
                withContext(Dispatchers.IO) { Files.writeTo(context, file, uri) }
                uri to withContext(Dispatchers.IO) { Files.displayName(context, uri) }
            } else {
                if (Files.downloadsNeedsPermission(context) && !askStoragePermission()) {
                    say(str(R.string.storage_permission))
                    return null
                }
                val uri = withContext(Dispatchers.IO) { Files.saveToDownloads(context, file, name, mime) }
                val shown = withContext(Dispatchers.IO) { Files.displayName(context, uri) }
                uri to "${str(R.string.downloads_place)} › $shown"
            }
        } catch (e: Throwable) {
            say(str(R.string.save_failed, e.message ?: e.javaClass.simpleName), long = true)
            null
        }
    }

    /** Android 8 and 9 ask for the storage permission before writing to shared folders. */
    suspend fun canWriteShared(): Boolean {
        if (!Files.downloadsNeedsPermission(context)) return true
        if (askStoragePermission()) return true
        say(str(R.string.storage_permission))
        return false
    }

    /**
     * Shows a file a tool made: it is saved already, so it opens as a normal document, and a
     * picture opens in the image viewer (it went to the PDF reader and failed as "not a PDF").
     */
    fun openSaved(uri: Uri, mime: String = Files.PDF) {
        if (mime.startsWith("image/")) openImages(listOf(uri)) else openUri(uri, persistable = true)
    }

    private suspend fun createDocument(name: String, mime: String = Files.PDF): Uri? = suspendCancellableCoroutine { cont ->
        systemRequest = SystemRequest.CreateDocument(name, mime) { uri ->
            systemRequest = null
            if (cont.isActive) cont.resume(uri)
        }
    }

    private suspend fun askStoragePermission(): Boolean = suspendCancellableCoroutine { cont ->
        systemRequest = SystemRequest.StoragePermission { ok ->
            systemRequest = null
            if (cont.isActive) cont.resume(ok)
        }
    }

    // --- sharing ------------------------------------------------------------------------------

    /** Shares the document as it is now (unsaved changes included) through Android's share sheet. */
    fun share(session: DocumentSession, launch: (Intent) -> Unit) {
        viewModelScope.launch {
            busy = str(R.string.working)
            try {
                val dir = File(Files.shareDir(context), UUID.randomUUID().toString()).apply { mkdirs() }
                val target = File(dir, Files.ensurePdfName(session.name.value))
                session.writeTo(target)
                shareFile(target, launch)
            } catch (e: Throwable) {
                say(errorText(e))
            } finally {
                busy = null
            }
        }
    }

    /**
     * Shares files the app saved to Downloads under their own names: a Downloads Uri reaches the
     * share sheet (and some apps) as its number ("1000000052"), so named copies in the share
     * folder go instead.
     */
    fun shareSaved(uris: List<Uri>, mime: String, launch: (Intent) -> Unit) {
        viewModelScope.launch {
            try {
                val named = withContext(Dispatchers.IO) {
                    val dir = File(Files.shareDir(context), UUID.randomUUID().toString()).apply { mkdirs() }
                    uris.map { u ->
                        val target = File(dir, Files.displayName(context, u))
                        context.contentResolver.openInputStream(u)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                        Files.shareUri(context, target)
                    }
                }
                shareUris(named, mime, launch)
            } catch (e: Throwable) {
                say(errorText(e))
            }
        }
    }

    fun shareFile(file: File, launch: (Intent) -> Unit) {
        val uri = Files.shareUri(context, file)
        shareUris(listOf(uri), Files.PDF, launch)
    }

    fun shareUris(uris: List<Uri>, mime: String, launch: (Intent) -> Unit) {
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mime
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // HonoKit takes Share itself (to open, combine or make a PDF): it is left out of its own
        // share sheet (UX review finding 17; the second "HonoKit" seen on the emulator was the old
        // com.aamus.powerpdf.dev build, another app).
        launch(
            Intent.createChooser(send, null)
                .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf<android.os.Parcelable>(android.content.ComponentName(context, MainActivity::class.java)))
        )
    }

    /** Opens a document a tool produced in memory (not yet saved anywhere). */
    fun openNewDocument(work: File, name: String) {
        viewModelScope.launch {
            try {
                val s = DocumentSession.fromNew(context, work, name)
                Sessions.add(s)
                push(Screen.Viewer(s.id))
            } catch (e: Throwable) {
                say(errorText(e))
            }
        }
    }

    /** Debug builds: commands from the PC (docs/testing.md). The viewer handles its own. */
    fun debug(c: DebugBus.Command) {
        when (c.cmd) {
            "open" -> openUri(Uri.fromFile(File(c.value)), persistable = false)
            "home" -> goHome()
            "back" -> pop()
            "tab" -> {
                goHome()
                homeTab = c.value.toIntOrNull() ?: 0
            }
            "settings" -> push(Screen.Settings)
            "licences" -> push(Screen.Licences)
            "theme" -> {
                val t = when (c.value) {
                    "light" -> com.aamus.powerpdf.data.ThemeMode.Light
                    "dark" -> com.aamus.powerpdf.data.ThemeMode.Dark
                    else -> com.aamus.powerpdf.data.ThemeMode.System
                }
                settings.update { it.copy(theme = t) }
                PowerPdfApp.applyTheme(t)
            }
            "lang" -> androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                if (c.value.isBlank() || c.value == "system") androidx.core.os.LocaleListCompat.getEmptyLocaleList()
                else androidx.core.os.LocaleListCompat.forLanguageTags(c.value)
            )
            "tool" -> {
                val tool = ToolId.entries.firstOrNull { it.name.equals(c.value, ignoreCase = true) } ?: return
                val session = (current as? Screen.Viewer)?.sessionId
                if (tool.needsDocument && session == null) return
                push(Screen.Tool(tool, sessionId = if (tool.needsDocument) session else null))
            }
            "say" -> say(c.value)
            "password" -> passwordRequest?.done?.invoke(c.value)
            "opensaved" -> openSaved(Uri.parse(c.value), context.contentResolver.getType(Uri.parse(c.value)) ?: Files.PDF)
            "mergefiles" -> push(Screen.Tool(ToolId.Merge, inputs = c.value.split(',').filter { it.isNotBlank() }.map { Uri.fromFile(File(it.trim())) }))
            "images" -> push(Screen.Tool(ToolId.Images, inputs = c.value.split(',').filter { it.isNotBlank() }.map { Uri.fromFile(File(it.trim())) }))
            "scan" -> push(Screen.Tool(ToolId.Scan, inputs = c.value.split(',').filter { it.isNotBlank() }.map { Uri.fromFile(File(it.trim())) }))
            "scanid" -> push(Screen.Tool(ToolId.ScanId, inputs = c.value.split(',').filter { it.isNotBlank() }.map { Uri.fromFile(File(it.trim())) }))
            "scanbook" -> push(Screen.Tool(ToolId.ScanBook, inputs = c.value.split(',').filter { it.isNotBlank() }.map { Uri.fromFile(File(it.trim())) }))
            // An image tool with pictures already chosen: "ImgCompress|/sdcard/a.jpg,/sdcard/b.png" (decisions §34).
            "imgtool" -> {
                val (name, files) = c.value.split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                val tool = ToolId.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return
                push(Screen.Tool(tool, inputs = files.split(',').filter { it.isNotBlank() }.map { Uri.fromFile(File(it.trim())) }))
            }
            "openrecent" -> com.aamus.powerpdf.data.RecentList.distinct(recents.flow.value).getOrNull(c.value.toIntOrNull() ?: 0)?.let { openRecent(it) }
            // A tool as Home starts it: the chooser first (decisions §45).
            "starttool" -> ToolId.entries.firstOrNull { it.name.equals(c.value, ignoreCase = true) }?.let { startTool(it) }
            // The result screen for files on the phone: "Compress|/sdcard/Download/a.pdf,…".
            "result" -> {
                val (name, files) = c.value.split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                val tool = ToolId.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return
                val uris = files.split(',').filter { it.isNotBlank() }.map { Uri.fromFile(File(it.trim())) }
                val mime = if (uris.firstOrNull()?.path?.endsWith(".pdf", true) != false) Files.PDF else "image/*"
                showSaved(tool, uris, place = "Downloads › HonoKit", mime = mime)
            }
            // Pictures in the image viewer: "/path/a.jpg,/path/b.png" (decisions §42).
            "openimage" -> openImages(c.value.split(',').filter { it.isNotBlank() }.map { Uri.fromFile(File(it.trim())) })
        }
    }
}
