package com.aamus.powerpdf.ui.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aamus.powerpdf.R
import com.aamus.powerpdf.ToolId
import com.aamus.powerpdf.data.Files
import com.aamus.powerpdf.doc.DocumentSession
import com.aamus.powerpdf.doc.Recognize
import com.aamus.powerpdf.engine.addTextLayer
import com.aamus.powerpdf.ocr.OcrEngine
import com.aamus.powerpdf.ocr.OcrException
import com.aamus.powerpdf.ocr.OcrImage
import com.aamus.powerpdf.ui.Group
import com.aamus.powerpdf.ui.Hint
import com.aamus.powerpdf.ui.LocalModel
import com.aamus.powerpdf.ui.PrimaryButton
import com.aamus.powerpdf.ui.SecondaryButton
import com.aamus.powerpdf.ui.Sym
import com.aamus.powerpdf.ui.SwitchRow
import com.aamus.powerpdf.ui.theme.Power
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Recognize text on the open document (decisions §29): pages that are pictures get an invisible
 * text layer, as one undoable change; the person saves as usual. Progress line by line, with
 * Cancel (nothing changes then).
 */
@Composable
fun RecognizeTool(session: DocumentSession) {
    val model = LocalModel.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = Power.colors
    val name by session.name.collectAsStateWithLifecycle()
    val count = session.state.value.pageCount
    var redo by remember { mutableStateOf(false) }
    var withoutText by remember { mutableStateOf<Int?>(null) }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var fraction by remember { mutableStateOf(0f) }
    var engine by remember { mutableStateOf<OcrEngine?>(null) }
    val label = stringResource(R.string.tool_recognize)

    LaunchedEffect(Unit) {
        withoutText = runCatching { session.engine { d -> (0 until count).count { !Recognize.hasText(d, it) } } }.getOrNull()
    }

    fun go() {
        if (running) return
        running = true
        fraction = 0f
        scope.launch {
            try {
                val (found, _) = Recognize.read(
                    context,
                    onEngine = { block -> session.engine { d -> block(d) } },
                    pages = (0 until count).toList(),
                    redo = redo,
                    ocrSlot = { engine = it },
                ) { page, of, line, lines ->
                    scope.launch(Dispatchers.Main) {
                        status = if (lines == 0) model.str(R.string.ocr_progress_page, page, of) else model.str(R.string.ocr_progress, page, of, line, lines)
                        fraction = ((page - 1) + if (lines == 0) 0f else line.toFloat() / lines) / of
                    }
                }
                if (found.isEmpty()) {
                    model.say(model.str(if (redo) R.string.ocr_none_found else R.string.ocr_nothing), long = true)
                    return@launch
                }
                val lines = found.values.sumOf { it.size }
                if (lines == 0) {
                    model.say(model.str(R.string.ocr_none_found), long = true)
                    return@launch
                }
                session.change(label) { d -> d.addTextLayer(found) }
                model.say(model.str(R.string.ocr_done, found.count { it.value.isNotEmpty() }, lines), long = true)
                model.pop()
            } catch (e: OcrException.Cancelled) {
                model.say(model.str(R.string.ocr_cancelled))
            } catch (e: OcrException.Unsupported) {
                model.say(model.str(R.string.ocr_64bit), long = true)
            } catch (e: Throwable) {
                model.say(model.errorText(e))
            } finally {
                running = false
                engine = null
            }
        }
    }

    com.aamus.powerpdf.OnDebug { d ->
        when (d.cmd) {
            "redo" -> redo = d.value != "off"
            "go" -> go()
            "cancel" -> engine?.cancelled = true
        }
    }

    ToolFrame(ToolId.Recognize, subtitle = name, bottom = {
        if (running) SecondaryButton(stringResource(R.string.cancel), { engine?.cancelled = true }, Modifier.fillMaxWidth())
        else PrimaryButton(stringResource(R.string.ocr_button), { go() }, Modifier.fillMaxWidth(), enabled = redo || (withoutText ?: 1) > 0)
    }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
            withoutText?.let { n ->
                Text(
                    stringResource(R.string.ocr_pages_info, n, count),
                    style = MaterialTheme.typography.titleSmall,
                    color = c.ink,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                if (n == 0 && !redo) Hint(stringResource(R.string.ocr_nothing), Modifier.padding(horizontal = 20.dp))
            }
            Spacer(Modifier.height(8.dp))
            Group {
                SwitchRow(stringResource(R.string.ocr_redo), redo, { if (!running) redo = it })
            }
            if (running) {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), color = c.accent, trackColor = c.line)
                    Spacer(Modifier.height(8.dp))
                    Text(status, style = MaterialTheme.typography.bodyMedium, color = c.muted)
                }
            }
            Hint(stringResource(R.string.ocr_hint), Modifier.padding(20.dp))
        }
    }
}

/**
 * Image to text: a photo (the scanner's camera) or a picture from the gallery is read, and its
 * text shown to correct, copy, share or save. Nothing leaves the phone.
 */
@Composable
fun ImageToTextTool(inputs: List<Uri>) {
    val model = LocalModel.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = Power.colors
    var text by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var fraction by remember { mutableStateOf(0f) }
    var engine by remember { mutableStateOf<OcrEngine?>(null) }
    var live by remember { mutableStateOf(false) }

    fun read(uri: Uri) {
        if (running) return
        running = true
        text = null
        fraction = 0f
        status = model.str(R.string.itext_finding)
        scope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    // Straightened when a page stands out, as the scanner does; else the whole picture.
                    val corners = runCatching { findPage(context, uri, 0) }.getOrNull()
                    val up = decodeUpright(context, uri, 0, 3000) ?: throw IllegalStateException(model.str(R.string.error_generic, ""))
                    val page = if (corners != null) warp(up, corners, 2600).also { up.recycle() } else up
                    val px = IntArray(page.width * page.height)
                    page.getPixels(px, 0, page.width, 0, 0, page.width, page.height)
                    val image = OcrImage(px, page.width, page.height)
                    page.recycle()
                    OcrEngine.open(context, Recognize.threads()).use { ocr ->
                        withContext(Dispatchers.Main) { engine = ocr }
                        val lines = Recognize.readPage(ocr, Recognize.PagePicture(image, 1f)) { line, lines ->
                            scope.launch(Dispatchers.Main) {
                                status = model.str(R.string.itext_reading, line, lines)
                                fraction = line.toFloat() / lines
                            }
                        }
                        Recognize.toText(lines)
                    }
                }
                text = result
                if (result.isBlank()) model.say(model.str(R.string.itext_none), long = true)
            } catch (e: OcrException.Cancelled) {
                model.say(model.str(R.string.ocr_cancelled))
            } catch (e: OcrException.Unsupported) {
                model.say(model.str(R.string.ocr_64bit), long = true)
            } catch (e: Throwable) {
                model.say(model.errorText(e))
            } finally {
                running = false
                engine = null
            }
        }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) read(uri) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) cameraFile?.let { read(Uri.fromFile(it)) } }

    fun pick() = gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    /** The scanner's own camera, one picture (the phone's camera app when it is refused). */
    fun shoot() {
        live = true
    }

    LaunchedEffect(Unit) { inputs.firstOrNull()?.let { read(it) } }

    fun copy(t: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(model.str(R.string.image_text_name), t))
        model.say(model.str(R.string.text_copied))
    }

    fun share(t: String) {
        runCatching {
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t), null))
        }
    }

    fun save(t: String) {
        scope.launch {
            try {
                val out = Files.newWorkFile(context, "txt")
                withContext(Dispatchers.IO) { out.writeText(t) }
                val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH.mm", java.util.Locale.US).format(java.util.Date())
                val saved = model.saveNewFile(out, "${model.str(R.string.image_text_name)} $stamp.txt", "text/plain")
                out.delete()
                if (saved != null) model.showSaved(ToolId.ImageToText, listOf(saved.first), saved.second, mime = "text/plain", again = false)
            } catch (e: Throwable) {
                model.say(model.errorText(e))
            }
        }
    }

    com.aamus.powerpdf.OnDebug { d ->
        when (d.cmd) {
            "read" -> read(Uri.fromFile(File(d.value)))
            "cancel" -> engine?.cancelled = true
        }
    }

    ToolFrame(ToolId.ImageToText, bottom = {
        val t = text
        when {
            running -> SecondaryButton(stringResource(R.string.cancel), { engine?.cancelled = true }, Modifier.fillMaxWidth())
            t.isNullOrBlank() -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(stringResource(R.string.from_gallery), { pick() }, Modifier.weight(1f), icon = R.drawable.ic_photo_library)
                PrimaryButton(stringResource(R.string.take_photo), { shoot() }, Modifier.weight(1f), icon = R.drawable.ic_photo_camera)
            }
            else -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(stringResource(R.string.share), { share(t) }, Modifier.weight(1f), icon = R.drawable.ic_share)
                SecondaryButton(stringResource(R.string.save), { save(t) }, Modifier.weight(1f), icon = R.drawable.ic_save)
                PrimaryButton(stringResource(R.string.copy), { copy(t) }, Modifier.weight(1f), icon = R.drawable.ic_content_copy)
            }
        }
    }) {
        val t = text
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            when {
                running -> Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), color = c.accent, trackColor = c.line)
                    Spacer(Modifier.height(8.dp))
                    Text(status, style = MaterialTheme.typography.bodyMedium, color = c.muted)
                }
                t.isNullOrBlank() -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Sym(R.drawable.ic_text_fields, null, size = 48.dp, tint = c.muted)
                    Spacer(Modifier.height(12.dp))
                    Hint(stringResource(if (t == null) R.string.itext_empty else R.string.itext_none))
                }
                else -> {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Hint(stringResource(R.string.itext_hint), Modifier.weight(1f))
                        androidx.compose.material3.TextButton({ text = null }) { Text(stringResource(R.string.itext_another), style = MaterialTheme.typography.labelLarge, maxLines = 1) }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = t,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        // Each paragraph takes its own direction (Arabic right to left, English left to right).
                        textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content, textAlign = TextAlign.Start),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.accent, unfocusedBorderColor = c.line, cursorColor = c.accent),
                    )
                }
            }
        }
    }

    if (live) {
        ScanCamera(
            pages = 0,
            last = null,
            single = true,
            onPhoto = { uri -> read(uri) },
            onDone = { live = false },
            onFallback = {
                live = false
                val dir = File(context.cacheDir, "camera").apply { mkdirs() }
                val f = File(dir, "${UUID.randomUUID()}.jpg")
                cameraFile = f
                runCatching { takePhoto.launch(Files.shareUri(context, f)) }
            },
        )
    }
}

/** The photo the phone's camera app is writing (when the camera permission was refused). */
private var cameraFile: File? = null
