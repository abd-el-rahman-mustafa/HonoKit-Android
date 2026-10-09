package com.aamus.powerpdf.viewer

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aamus.powerpdf.R
import com.aamus.powerpdf.doc.DocumentSession
import com.aamus.powerpdf.engine.HAlign
import com.aamus.powerpdf.engine.PdfFiles
import com.aamus.powerpdf.engine.Rgb
import com.aamus.powerpdf.engine.StampText
import com.aamus.powerpdf.engine.TextLogic
import com.aamus.powerpdf.engine.VPos
import com.aamus.powerpdf.engine.addPageNumbers
import com.aamus.powerpdf.engine.addWatermark
import com.aamus.powerpdf.engine.addPictureWatermark
import com.aamus.powerpdf.engine.TextStyle as StampStyle
import com.aamus.powerpdf.ui.Field
import com.aamus.powerpdf.ui.LocalModel
import com.aamus.powerpdf.ui.PrimaryButton
import com.aamus.powerpdf.ui.Segmented
import com.aamus.powerpdf.ui.Swatch
import com.aamus.powerpdf.ui.theme.PlexArabic
import com.aamus.powerpdf.ui.theme.Power
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class PagesChoice { All, Current, Custom }

/** Which pages a page tool applies to: All / This page / Custom ranges ("1-3, 5"). */
@Composable
private fun PagesPicker(choice: PagesChoice, onChoice: (PagesChoice) -> Unit, custom: String, onCustom: (String) -> Unit, current: Int, count: Int, error: Boolean) {
    Column {
        Text(stringResource(R.string.pages), style = MaterialTheme.typography.labelLarge, color = Power.colors.muted)
        Spacer(Modifier.height(6.dp))
        Segmented(
            listOf(
                PagesChoice.All to stringResource(R.string.pages_all),
                PagesChoice.Current to stringResource(R.string.pages_current, current + 1),
                PagesChoice.Custom to stringResource(R.string.pages_custom),
            ),
            choice, onChoice,
        )
        if (choice == PagesChoice.Custom) {
            Spacer(Modifier.height(8.dp))
            Field(custom, onCustom, stringResource(R.string.pages_custom_hint), error = if (error) stringResource(R.string.split_bad_range, custom, count) else null)
        }
    }
}

private fun pagesOf(choice: PagesChoice, custom: String, current: Int, count: Int): List<Int>? = when (choice) {
    PagesChoice.All -> (0 until count).toList()
    PagesChoice.Current -> listOf(current)
    PagesChoice.Custom -> PdfFiles.parseRanges(custom, count)?.flatMap { it.toList() }?.distinct()
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, shown: String, onChange: (Float) -> Unit) {
    val c = Power.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = c.muted, modifier = Modifier.width(88.dp))
        Slider(
            value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.sunken),
        )
        Text(shown, style = MaterialTheme.typography.labelLarge, color = c.ink, modifier = Modifier.width(52.dp), textAlign = TextAlign.End)
    }
}

@Composable
fun rememberStampTool(session: DocumentSession, viewport: ViewportState, watermark: Boolean, onExit: () -> Unit): ModeTool {
    val model = LocalModel.current
    // Outlives the mode: Done writes the last change while the mode closes.
    val scope = LocalModel.current.appScope
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val rtlUi = LocalLayoutDirection.current == LayoutDirection.Rtl
    val arabicUi = LocalConfiguration.current.locales[0].language == "ar"
    val prefs by model.settings.flow.collectAsStateWithLifecycle()
    val state by session.state.collectAsStateWithLifecycle()

    // Watermark settings.
    var text by remember { mutableStateOf(model.str(R.string.watermark_default)) }
    var size by remember { mutableFloatStateOf(if (watermark) 60f else 11f) }
    var opacity by remember { mutableFloatStateOf(if (watermark) 0.3f else 1f) }
    var angle by remember { mutableIntStateOf(45) }
    var colour by remember { mutableIntStateOf(if (watermark) 0xFFE34850.toInt() else 0xFF1F1F1F.toInt()) }
    var bold by remember { mutableStateOf(watermark) }
    // A picture watermark instead of text.
    var usePicture by remember { mutableStateOf(false) }
    var pictureBytes by remember { mutableStateOf<ByteArray?>(null) }
    var picture by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var pictureWidth by remember { mutableFloatStateOf(0.5f) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val pickPicture = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val loaded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    // Upright and at most 2000 px: phone photos are much larger than a watermark needs.
                    val bmp = com.aamus.powerpdf.ui.tools.decodeUpright(context, uri, 0, 2000) ?: return@runCatching null
                    val png = java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                    png to bmp.asImageBitmap()
                }.getOrNull()
            }
            if (loaded != null) {
                pictureBytes = loaded.first
                picture = loaded.second
                usePicture = true
            } else model.say(model.str(R.string.error_read))
        }
    }
    // Page number settings.
    var template by remember { mutableIntStateOf(0) }
    var customTemplate by remember { mutableStateOf("") }
    var top by remember { mutableStateOf(false) }
    var align by remember { mutableIntStateOf(1) } // 0 start, 1 centre, 2 end
    var startAt by remember { mutableStateOf("1") }
    var arabicDigits by remember { mutableStateOf(prefs.arabicIndicDigits || arabicUi) }
    // Pages.
    var choice by remember { mutableStateOf(PagesChoice.All) }
    var custom by remember { mutableStateOf("") }

    val count = state.pageCount
    val current by remember(viewport) { derivedStateOf { viewport.currentPage().coerceIn(0, maxOf(0, viewport.pages.size - 1)) } }
    val pages = pagesOf(choice, custom, current, count)

    val pageWord = model.str(R.string.template_page_word)
    val ofWord = model.str(R.string.template_of_word)
    val docName by session.name.collectAsStateWithLifecycle()
    val today = remember {
        val locale = java.util.Locale.Builder().setLocale(context.resources.configuration.locales[0]).setUnicodeLocaleKeyword("nu", "latn").build()
        java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, locale).format(java.util.Date())
    }
    // {date} and {file} are the same on every page, so they are filled in here; {n} and {N} per page.
    val templates = listOf("{n}", "$pageWord {n}", "{n} / {N}", "$pageWord {n} $ofWord {N}", customTemplate)
        .map { it.replace("{date}", today).replace("{file}", docName.removeSuffix(".pdf").removeSuffix(".PDF")) }
    fun hAlign(): HAlign = when (align) {
        0 -> if (rtlUi) HAlign.Right else HAlign.Left
        2 -> if (rtlUi) HAlign.Left else HAlign.Right
        else -> HAlign.Center
    }

    val gestures = remember { object : PageGestures {} }

    val overlay: DrawScope.(PageLayout) -> Unit = { layout ->
        val set = pages?.toSet() ?: emptySet()
        val visible = layout.visiblePages(viewport.contentTop, viewport.contentTop + viewport.viewportHeight)
        val first = (startAt.toIntOrNull() ?: 1)
        for (p in visible) {
            if (p !in set) continue
            val r = layout.viewRect(viewport, p, 0f, 0f, layout.pages[p].width, layout.pages[p].height)
            val px = size * layout.scale
            val sp = (px / density.fontScale / density.density).sp
            val content = if (watermark && usePicture) " " else if (watermark) text else {
                fun d(n: Int) = if (arabicDigits) TextLogic.toArabicIndic(n.toString()) else n.toString()
                templates[template].replace("{n}", d(first + p)).replace("{N}", d(first + count - 1))
            }
            if (content.isBlank() && !(watermark && usePicture)) continue
            val style = TextStyle(
                fontSize = sp,
                color = Color(colour).copy(alpha = opacity),
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                fontFamily = if (StampText.isRtl(content)) PlexArabic else null,
                textAlign = TextAlign.Center,
            )
            val measured = measurer.measure(content, style, constraints = Constraints(maxWidth = maxOf(1, (r.width * 2).roundToInt())))
            if (watermark && usePicture) {
                val img = picture ?: continue
                val w = r.width * pictureWidth
                val h = w * img.height / img.width
                rotate(-angle.toFloat(), r.center) {
                    drawImage(
                        img,
                        dstOffset = androidx.compose.ui.unit.IntOffset((r.center.x - w / 2).roundToInt(), (r.center.y - h / 2).roundToInt()),
                        dstSize = androidx.compose.ui.unit.IntSize(w.roundToInt(), h.roundToInt()),
                        alpha = opacity,
                    )
                }
                continue
            }
            if (watermark) {
                // Shrink to fit along the angle, as the engine does.
                val rad = Math.toRadians(angle.toDouble())
                val c = kotlin.math.abs(kotlin.math.cos(rad)).toFloat()
                val s = kotlin.math.abs(kotlin.math.sin(rad)).toFloat()
                val w = r.width - 48 * layout.scale
                val h = r.height - 48 * layout.scale
                val room = minOf(if (c > 1e-4f) w / c else Float.MAX_VALUE, if (s > 1e-4f) h / s else Float.MAX_VALUE)
                val k = if (measured.size.width > room) room / measured.size.width else 1f
                val center = r.center
                rotate(-angle.toFloat(), center) {
                    drawContext.transform.scale(k, k, center)
                    drawText(measured, topLeft = Offset(center.x - measured.size.width / 2f, center.y - measured.size.height / 2f))
                    drawContext.transform.scale(1 / k, 1 / k, center)
                }
            } else {
                val margin = 28 * layout.scale
                val x = when (hAlign()) {
                    HAlign.Left -> r.left + margin
                    HAlign.Center -> r.center.x - measured.size.width / 2f
                    HAlign.Right -> r.right - margin - measured.size.width
                }
                val y = if (top) r.top + margin else r.bottom - margin - measured.size.height
                drawText(measured, topLeft = Offset(x, y))
            }
        }
    }

    /** Adds the watermark or numbers; false when there is nothing to add (the mode just closes). */
    fun apply(): Boolean {
        val list = pages ?: return false
        if (watermark && !usePicture && text.isBlank()) return false
        if (watermark && usePicture && pictureBytes == null) return false
        val style = StampStyle(size, Rgb.of(colour), bold, opacity)
        scope.launch {
            runCatching {
                session.change(if (watermark) "watermark" else "page numbers") { doc ->
                    if (watermark && usePicture) doc.addPictureWatermark(list, pictureBytes!!, pictureWidth, opacity, angle.toFloat())
                    else if (watermark) doc.addWatermark(list, text.trim(), style, angle.toFloat(), VPos.Middle, HAlign.Center)
                    else doc.addPageNumbers(list, templates[template], startAt.toIntOrNull() ?: 1, style, if (top) VPos.Top else VPos.Bottom, hAlign(), 28f, arabicDigits)
                }
            }.onSuccess {
                model.say(model.str(R.string.added))
                onExit()
            }.onFailure { model.say(model.errorText(it)) }
        }
        return true
    }

    val chrome: @Composable BoxScope.() -> Unit = {
        val c = Power.colors
        Column(Modifier.align(Alignment.TopCenter)) {
            // Done adds what the preview shows, as Apply does (common editor behaviour).
            ModeTopBar(stringResource(if (watermark) R.string.watermark_title else R.string.page_numbers_title), onDone = { if (!apply()) onExit() })
        }
        Column(Modifier.align(Alignment.BottomCenter).imePadding()) {
            ModeBottom {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (watermark) {
                        Segmented(listOf(false to stringResource(R.string.watermark_kind_text), true to stringResource(R.string.watermark_kind_picture)), usePicture, {
                            usePicture = it
                            if (it && pictureBytes == null) pickPicture.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                        })
                    }
                    if (watermark && usePicture) {
                        com.aamus.powerpdf.ui.SecondaryButton(
                            stringResource(if (pictureBytes == null) R.string.choose_picture else R.string.change_picture),
                            { pickPicture.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            Modifier.fillMaxWidth(), icon = R.drawable.ic_photo_library,
                        )
                        LabeledSlider(stringResource(R.string.size), pictureWidth, 0.1f..1f, "${(pictureWidth * 100).roundToInt()}%") { pictureWidth = it }
                        LabeledSlider(stringResource(R.string.opacity), opacity, 0.1f..1f, "${(opacity * 100).roundToInt()}%") { opacity = it }
                        Segmented(listOf(0 to "0°", 45 to "45°", 90 to "90°", -45 to "−45°"), angle, { angle = it })
                    } else if (watermark) {
                        Field(text, { text = it }, stringResource(R.string.watermark_text))
                        LabeledSlider(stringResource(R.string.size), size, 12f..140f, "${size.roundToInt()}") { size = it }
                        LabeledSlider(stringResource(R.string.opacity), opacity, 0.1f..1f, "${(opacity * 100).roundToInt()}%") { opacity = it }
                        Segmented(listOf(0 to "0°", 45 to "45°", 90 to "90°", -45 to "−45°"), angle, { angle = it })
                    } else {
                        Segmented(
                            listOf(0 to stringResource(R.string.template_n), 1 to stringResource(R.string.template_page_n), 2 to stringResource(R.string.template_n_of), 3 to stringResource(R.string.template_page_n_of), 4 to stringResource(R.string.template_custom)),
                            template, { template = it },
                        )
                        if (template == 4) {
                            Field(customTemplate, { customTemplate = it }, stringResource(R.string.template_custom_label), placeholder = stringResource(R.string.template_custom_hint))
                            // Tokens, inserted at the end of the text.
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("{n}" to R.string.token_page, "{N}" to R.string.token_pages, "{date}" to R.string.token_date, "{file}" to R.string.token_file).forEach { (token, label) ->
                                    com.aamus.powerpdf.ui.SecondaryButton(stringResource(label), { customTemplate = (customTemplate.trimEnd() + " " + token).trimStart() })
                                }
                            }
                        }
                        Segmented(listOf(false to stringResource(R.string.pos_bottom), true to stringResource(R.string.pos_top)), top, { top = it })
                        Segmented(listOf(0 to stringResource(R.string.pos_start), 1 to stringResource(R.string.pos_center), 2 to stringResource(R.string.pos_end)), align, { align = it })
                        Segmented(listOf(false to stringResource(R.string.digits_western), true to stringResource(R.string.digits_arabic)), arabicDigits, { arabicDigits = it })
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Field(startAt, { startAt = it.filter(Char::isDigit).take(5) }, stringResource(R.string.start_at), number = true, modifier = Modifier.weight(1f))
                        }
                        LabeledSlider(stringResource(R.string.size), size, 6f..28f, "${size.roundToInt()}") { size = it }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        listOf(0xFFE34850.toInt(), 0xFF808080.toInt(), 0xFF1F1F1F.toInt(), 0xFF1473E6.toInt(), 0xFF2D9D78.toInt(), 0xFF7E57E6.toInt()).forEach { col ->
                            Swatch(Color(col), selected = col == colour, onClick = { colour = col }, size = 26.dp)
                        }
                    }
                    PagesPicker(choice, { choice = it }, custom, { custom = it }, current, count, error = choice == PagesChoice.Custom && custom.isNotBlank() && pages == null)
                    PrimaryButton(
                        stringResource(R.string.apply),
                        { apply() },
                        Modifier.fillMaxWidth(),
                        enabled = pages != null && pages.isNotEmpty() && state.rights?.edit != false &&
                            (if (watermark) (if (usePicture) pictureBytes != null else text.isNotBlank()) else (template != 4 || customTemplate.isNotBlank())),
                    )
                    if (state.rights?.edit == false) Text(stringResource(R.string.error_restricted), color = c.danger, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    // Back asks before the previewed watermark or numbers are thrown away.
    val ready = pages != null && (if (!watermark) true else if (usePicture) pictureBytes != null else text.isNotBlank())
    return ModeTool(gestures, overlay, chrome, pending = { ready }, finish = { if (!apply()) onExit() })
}
