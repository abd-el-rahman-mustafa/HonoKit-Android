package com.aamus.powerpdf.ui.tools

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aamus.powerpdf.R
import com.aamus.powerpdf.ToolId
import com.aamus.powerpdf.data.Files
import com.aamus.powerpdf.doc.DocumentSession
import com.aamus.powerpdf.engine.PdfFiles
import com.aamus.powerpdf.engine.exportText
import com.aamus.powerpdf.ui.Field
import com.aamus.powerpdf.ui.Group
import com.aamus.powerpdf.ui.GroupDivider
import com.aamus.powerpdf.ui.Hint
import com.aamus.powerpdf.ui.LocalModel
import com.aamus.powerpdf.ui.PrimaryButton
import com.aamus.powerpdf.ui.RadioRow
import com.aamus.powerpdf.ui.Segmented
import com.aamus.powerpdf.ui.theme.Power
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

private enum class PictureSize(val dpi: Int, val label: Int, val desc: Int) {
    Small(96, R.string.picture_small, R.string.picture_small_desc),
    Medium(150, R.string.picture_medium, R.string.picture_medium_desc),
    Large(300, R.string.picture_large, R.string.picture_large_desc),
}


/**
 * PDF → pictures (desktop: PDF → JPG/PNG): each page drawn at the chosen size and saved into
 * Pictures › HonoPDF › <document name>, where the gallery finds them (research-ux-mobile.md 7.1).
 */
@Composable
fun ToImagesTool(session: DocumentSession) {
    val model = LocalModel.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val name by session.name.collectAsStateWithLifecycle()
    val state by session.state.collectAsStateWithLifecycle()
    var png by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(PictureSize.Medium) }
    var allPages by remember { mutableStateOf(true) }
    var range by remember { mutableStateOf("") }
    val count = state.pageCount
    val ranges = if (allPages) listOf(0 until count) else PdfFiles.parseRanges(range, count)
    val pages = ranges?.flatMap { it.toList() }?.distinct().orEmpty()

    fun go() {
        val list = pages
        if (list.isEmpty()) return
        scope.launch {
            try {
                if (!model.canWriteShared()) return@launch
                val base = name.removeSuffix(".pdf").removeSuffix(".PDF")
                val mime = if (png) "image/png" else "image/jpeg"
                val made = ArrayList<Uri>()
                list.forEachIndexed { k, p ->
                    model.busy = model.str(R.string.page_progress, k + 1, list.size)
                    val bytes = withContext(Dispatchers.Default) {
                        val pt = session.state.value.pageSizes[p]
                        // At most 25 megapixels: a very tall page at 300 dpi would not fit in memory.
                        val wanted = pt.width * size.dpi / 72f
                        val fit = kotlin.math.sqrt(25_000_000f / (pt.width * pt.height * (size.dpi / 72f) * (size.dpi / 72f))).coerceAtMost(1f)
                        val px = (wanted * fit).roundToInt().coerceIn(100, 6000)
                        val bmp = session.engine { it.renderWidth(p, px) }
                        try {
                            ByteArrayOutputStream().also { bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
                        } finally {
                            bmp.recycle()
                        }
                    }
                    val file = "$base-${p + 1}.${if (png) "png" else "jpg"}"
                    val uri = withContext(Dispatchers.IO) { Files.saveToPictures(context, bytes, base, file, mime) }
                    made.add(uri)
                }
                model.busy = null
                model.pop()
                // Open and Share for all the pictures at once (Open when there is one).
                model.showSaved(ToolId.ToImages, made, place = null, title = model.plural(R.plurals.pictures_saved, list.size, list.size, com.aamus.powerpdf.ui.bidi(base)), mime = mime)
            } catch (e: Throwable) {
                model.say(model.errorText(e))
            } finally {
                model.busy = null
            }
        }
    }

    com.aamus.powerpdf.OnDebug { c ->
        when (c.cmd) {
            "format" -> png = c.value.equals("png", true)
            "size" -> size = PictureSize.entries.firstOrNull { it.name.equals(c.value, true) } ?: size
            "range" -> { allPages = false; range = c.value }
            "go" -> go()
        }
    }

    ToolFrame(ToolId.ToImages, subtitle = name, bottom = {
        PrimaryButton(pluralStringResource(R.plurals.pictures_button, pages.size.coerceAtLeast(1), pages.size), { go() }, Modifier.fillMaxWidth(), enabled = pages.isNotEmpty())
    }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
            Text(stringResource(R.string.picture_format), style = MaterialTheme.typography.labelLarge, color = Power.colors.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            Segmented(listOf(false to "JPG", true to "PNG"), png, { png = it }, Modifier.padding(horizontal = 16.dp))
            Hint(stringResource(if (png) R.string.picture_png_hint else R.string.picture_jpg_hint), Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            Text(stringResource(R.string.picture_size), style = MaterialTheme.typography.labelLarge, color = Power.colors.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            Group {
                PictureSize.entries.forEachIndexed { i, s ->
                    if (i > 0) GroupDivider()
                    RadioRow(stringResource(s.label), size == s, { size = s }, stringResource(s.desc))
                }
            }
            Text(stringResource(R.string.pages), style = MaterialTheme.typography.labelLarge, color = Power.colors.muted, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp))
            Group {
                RadioRow(stringResource(R.string.all_pages_n, count), allPages, { allPages = true })
                GroupDivider()
                RadioRow(stringResource(R.string.some_pages), !allPages, { allPages = false })
            }
            if (!allPages) {
                Field(
                    range, { range = it }, stringResource(R.string.page_ranges),
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = stringResource(R.string.page_ranges_hint),
                    error = if (range.isNotBlank() && ranges == null) stringResource(R.string.page_ranges_bad, count) else null,
                )
            }
            Hint(stringResource(R.string.pictures_where), Modifier.padding(20.dp))
        }
    }
}

/**
 * PDF → text or Markdown (desktop features §50 in part): the words in reading order, Arabic
 * included, paragraph by paragraph; Markdown marks headings. Saved like any new file.
 */
@Composable
fun ToTextTool(session: DocumentSession) {
    val model = LocalModel.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val name by session.name.collectAsStateWithLifecycle()
    var markdown by remember { mutableStateOf(false) }

    fun go() {
        scope.launch {
            try {
                model.busy = model.str(R.string.working)
                val total = session.state.value.pageCount
                val text = session.engine { d -> d.exportText(markdown) { p -> model.busy = model.str(R.string.page_progress, p + 1, total) } }
                if (text.isBlank()) {
                    model.busy = null
                    model.say(model.str(R.string.text_none), long = true)
                    return@launch
                }
                val out = Files.newWorkFile(context, if (markdown) "md" else "txt")
                withContext(Dispatchers.IO) { out.writeText(text) }
                model.busy = null
                val base = name.removeSuffix(".pdf").removeSuffix(".PDF")
                val mime = if (markdown) "text/markdown" else "text/plain"
                val saved = model.saveNewFile(out, "$base.${if (markdown) "md" else "txt"}", mime)
                out.delete()
                if (saved != null) {
                    model.pop()
                    model.showSaved(ToolId.ToText, listOf(saved.first), saved.second, mime = mime)
                }
            } catch (e: Throwable) {
                model.say(model.errorText(e))
            } finally {
                model.busy = null
            }
        }
    }

    com.aamus.powerpdf.OnDebug { c ->
        when (c.cmd) {
            "markdown" -> markdown = c.value != "off"
            "go" -> go()
        }
    }

    ToolFrame(ToolId.ToText, subtitle = name, bottom = {
        PrimaryButton(stringResource(R.string.save_text), { go() }, Modifier.fillMaxWidth())
    }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
            Group {
                RadioRow(stringResource(R.string.text_plain), !markdown, { markdown = false }, stringResource(R.string.text_plain_desc))
                GroupDivider()
                RadioRow(stringResource(R.string.text_markdown), markdown, { markdown = true }, stringResource(R.string.text_markdown_desc))
            }
            Hint(stringResource(R.string.text_hint), Modifier.padding(20.dp))
        }
    }
}

private enum class Paper(val w: Float, val h: Float, val label: String) {
    A4(595f, 842f, "A4"),
    Letter(612f, 792f, "Letter"),
    A5(420f, 595f, "A5"),
    Legal(612f, 1008f, "Legal"),
    A3(842f, 1191f, "A3"),
}

/** A new blank document (desktop 0.10): paper size, orientation and page count; opens ready to write on. */
@Composable
fun BlankTool() {
    val model = LocalModel.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var paper by remember { mutableStateOf(Paper.A4) }
    var landscape by remember { mutableStateOf(false) }
    var pages by remember { mutableStateOf(1) }

    fun create() {
        scope.launch {
            try {
                val out = Files.newWorkFile(context)
                val (w, h) = if (landscape) paper.h to paper.w else paper.w to paper.h
                com.aamus.powerpdf.engine.PdfEngine.run { PdfFiles.blank(out, w, h, pages) }
                model.pop()
                model.openNewDocument(out, model.str(R.string.new_document_name) + ".pdf")
            } catch (e: Throwable) {
                model.say(model.errorText(e))
            }
        }
    }

    com.aamus.powerpdf.OnDebug { c ->
        when (c.cmd) {
            "paper" -> paper = Paper.entries.firstOrNull { it.name.equals(c.value, true) } ?: paper
            "landscape" -> landscape = c.value != "off"
            "count" -> pages = c.value.toIntOrNull()?.coerceIn(1, 200) ?: pages
            "go" -> create()
        }
    }

    ToolFrame(ToolId.Blank, bottom = {
        PrimaryButton(stringResource(R.string.create), { create() }, Modifier.fillMaxWidth())
    }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
            Text(stringResource(R.string.page_size), style = MaterialTheme.typography.labelLarge, color = Power.colors.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            Group {
                Paper.entries.forEachIndexed { i, p ->
                    if (i > 0) GroupDivider()
                    val mm = { v: Float -> (v * 25.4f / 72f).roundToInt() }
                    RadioRow(p.label, paper == p, { paper = p }, "${mm(p.w)} × ${mm(p.h)} mm")
                }
            }
            Text(stringResource(R.string.orientation), style = MaterialTheme.typography.labelLarge, color = Power.colors.muted, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp))
            Segmented(listOf(false to stringResource(R.string.portrait), true to stringResource(R.string.landscape)), landscape, { landscape = it }, Modifier.padding(horizontal = 16.dp))
            Text(stringResource(R.string.page_count), style = MaterialTheme.typography.labelLarge, color = Power.colors.muted, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp))
            androidx.compose.foundation.layout.Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                com.aamus.powerpdf.ui.IconAction(R.drawable.ic_remove, stringResource(R.string.fewer), { pages = (pages - 1).coerceAtLeast(1) }, enabled = pages > 1)
                Text("$pages", style = MaterialTheme.typography.titleMedium, color = Power.colors.ink, modifier = Modifier.padding(horizontal = 16.dp))
                com.aamus.powerpdf.ui.IconAction(R.drawable.ic_add, stringResource(R.string.more_pages), { pages = (pages + 1).coerceAtMost(200) })
            }
            Hint(stringResource(R.string.blank_hint), Modifier.padding(20.dp))
        }
    }
}
