package com.aamus.powerpdf.viewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aamus.powerpdf.R
import com.aamus.powerpdf.data.Files
import com.aamus.powerpdf.engine.SignatureInk
import com.aamus.powerpdf.ui.Field
import com.aamus.powerpdf.ui.Hint
import com.aamus.powerpdf.ui.SecondaryButton
import com.aamus.powerpdf.ui.theme.Power
import com.aamus.powerpdf.ui.tools.decodeUpright
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil

/**
 * Typed signatures (the result is a picture, so nothing is embedded): each style pairs a Latin face
 * with an Arabic one. The handwriting style writes Arabic in the bundled Aref Ruqaa (a Ruqʿa hand,
 * SIL OFL): phones do not reliably have a calligraphic Arabic face (a test phone had only
 * Naskh), so the Urdu locale's Nastaliq could not be counted on. Latin uses the phone's faces.
 */
enum class SignStyle(val label: Int) {
    Script(R.string.sign_style_script),
    Classic(R.string.sign_style_classic),
    Plain(R.string.sign_style_plain);

    fun typeface(): Typeface = when (this) {
        Script -> Typeface.create("cursive", Typeface.NORMAL)
        Classic -> Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
        Plain -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    /** The locale that picks the Arabic face (the Latin face has no Arabic letters). */
    fun locale(): Locale = when (this) {
        Script -> Locale.forLanguageTag("ur")
        else -> Locale.forLanguageTag("ar")
    }

    fun compose(): TextStyle = when (this) {
        Script -> TextStyle(fontFamily = FontFamily.Cursive, localeList = LocaleList("ur"))
        Classic -> TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic, localeList = LocaleList("ar"))
        Plain -> TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, localeList = LocaleList("ar"))
    }
}

/** Trims a transparent bitmap to what is drawn on it, with a small margin; null if nothing is. */
fun trimToInk(src: Bitmap, margin: Int = 6): Bitmap? {
    val w = src.width
    val h = src.height
    val px = IntArray(w * h)
    src.getPixels(px, 0, w, 0, 0, w, h)
    var x0 = w
    var y0 = h
    var x1 = -1
    var y1 = -1
    for (y in 0 until h) for (x in 0 until w) {
        if ((px[y * w + x] ushr 24) > 24) {
            if (x < x0) x0 = x
            if (x > x1) x1 = x
            if (y < y0) y0 = y
            if (y > y1) y1 = y
        }
    }
    if (x1 < 0) return null
    val l = (x0 - margin).coerceAtLeast(0)
    val t = (y0 - margin).coerceAtLeast(0)
    val r = (x1 + margin).coerceAtMost(w - 1)
    val b = (y1 + margin).coerceAtMost(h - 1)
    return Bitmap.createBitmap(src, l, t, r - l + 1, b - t + 1)
}

private fun isArabicLetter(ch: Char) = ch in '\u0600'..'\u06FF' || ch in '\u0750'..'\u077F' || ch in '\u08A0'..'\u08FF' || ch in '\uFB50'..'\uFDFF' || ch in '\uFE70'..'\uFEFF'

/** Runs of Arabic letters (with the spaces and marks between them) in [text], as index ranges. */
private fun arabicRuns(text: String): List<IntRange> {
    val out = ArrayList<IntRange>()
    var start = -1
    var lastArabic = -1
    for (i in text.indices) {
        val ch = text[i]
        if (isArabicLetter(ch)) {
            if (start < 0) start = i
            lastArabic = i
        } else if (start >= 0 && !(ch == ' ' || Character.getType(ch) == Character.NON_SPACING_MARK.toInt())) {
            out.add(start..lastArabic)
            start = -1
        }
    }
    if (start >= 0) out.add(start..lastArabic)
    return out
}

private var ruqaa: Typeface? = null

/** Aref Ruqaa, loaded once. */
fun ruqaaTypeface(context: android.content.Context): Typeface? = ruqaa ?: runCatching {
    androidx.core.content.res.ResourcesCompat.getFont(context, R.font.aref_ruqaa_regular)
}.getOrNull().also { ruqaa = it }

/** Sets a typeface on the text it covers (TypefaceSpan(Typeface) needs API 28). */
private class FaceSpan(private val face: Typeface) : android.text.style.MetricAffectingSpan() {
    override fun updateMeasureState(paint: TextPaint) {
        paint.typeface = face
    }

    override fun updateDrawState(paint: TextPaint) {
        paint.typeface = face
    }
}

/** A typed signature as a transparent picture, in [style] and [ink]. */
fun renderTyped(context: android.content.Context, text: String, style: SignStyle, ink: Int): Bitmap? {
    if (text.isBlank()) return null
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 140f
        color = ink
        typeface = style.typeface()
        textLocale = style.locale()
    }
    val face = if (style == SignStyle.Script) ruqaaTypeface(context) else null
    val styled = android.text.SpannableString(text).apply {
        if (face != null) for (r in arabicRuns(text)) setSpan(FaceSpan(face), r.first, r.last + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    val width = ceil(Layout.getDesiredWidth(styled, paint)).toInt().coerceAtLeast(10) + 80
    val layout = StaticLayout.Builder.obtain(styled, 0, styled.length, paint, width)
        .setAlignment(Layout.Alignment.ALIGN_CENTER)
        .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
        .setIncludePad(true)
        .build()
    // Room above and below: calligraphic hands reach far beyond the line.
    val bmp = Bitmap.createBitmap(width, layout.height + 160, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.translate(0f, 80f)
    layout.draw(canvas)
    return trimToInk(bmp, 10).also { if (it != bmp) bmp.recycle() }
}

/** A signature from a photo of it on paper: the ink kept, the paper transparent, cropped (engine SignatureInk). */
fun inkFromPhoto(photo: Bitmap, ink: Int?): Bitmap? {
    val w = photo.width
    val h = photo.height
    val px = IntArray(w * h)
    photo.getPixels(px, 0, w, 0, 0, w, h)
    val r = SignatureInk.extract(px, w, h, ink) ?: return null
    return Bitmap.createBitmap(r.argb, r.width, r.height, Bitmap.Config.ARGB_8888)
}

private val RuqaaFamily = FontFamily(androidx.compose.ui.text.font.Font(R.font.aref_ruqaa_regular))

/** The Type tab: the name typed once, shown in every style; a tap picks one. */
@Composable
fun TypeSignature(text: String, onText: (String) -> Unit, style: SignStyle, onStyle: (SignStyle) -> Unit, ink: Color) {
    val c = Power.colors
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Field(text, onText, stringResource(R.string.sign_type_label), placeholder = stringResource(R.string.sign_type_hint))
        SignStyle.entries.forEach { s ->
            val on = s == style
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 88.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White)
                    .border(if (on) 2.dp else 1.dp, if (on) c.accent else c.line, RoundedCornerShape(14.dp))
                    .clickable { onStyle(s) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                val shown = text.ifBlank { stringResource(R.string.sign_type_sample) }
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        append(shown)
                        if (s == SignStyle.Script) for (r in arabicRuns(shown)) addStyle(androidx.compose.ui.text.SpanStyle(fontFamily = RuqaaFamily), r.first, r.last + 1)
                    },
                    style = s.compose().copy(fontSize = 34.sp, color = if (text.isBlank()) Color(0xFF9E9E9E) else ink),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Text(
                    stringResource(s.label),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF757575),
                    modifier = Modifier.align(Alignment.TopStart),
                )
            }
        }
    }
}

/** The Photo tab: a photo of a signature on paper, from the gallery or the camera. */
@Composable
fun PhotoSignature(result: Bitmap?, onResult: (Bitmap?) -> Unit, ink: Int?) {
    val c = Power.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf<Uri?>(null) }
    var cameraFile by remember { mutableStateOf<File?>(null) }
    var failed by remember { mutableStateOf(false) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) source = uri }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraFile
        if (ok && f != null && f.length() > 0) source = Uri.fromFile(f)
    }
    LaunchedEffect(source, ink) {
        val uri = source ?: return@LaunchedEffect
        val made = withContext(Dispatchers.Default) {
            runCatching { decodeUpright(context, uri, 0, 1600)?.let { inkFromPhoto(it, ink) } }.getOrNull()
        }
        failed = made == null
        onResult(made)
    }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White)
                .border(1.dp, c.line, RoundedCornerShape(14.dp))
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (result != null) Image(result.asImageBitmap(), stringResource(R.string.signature), Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else Hint(stringResource(if (failed) R.string.sign_photo_none else R.string.sign_photo_hint), Modifier.padding(8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(stringResource(R.string.from_gallery), { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, Modifier.weight(1f), icon = R.drawable.ic_photo_library)
            SecondaryButton(stringResource(R.string.take_photo), {
                val dir = File(context.cacheDir, "camera").apply { mkdirs() }
                val f = File(dir, "${UUID.randomUUID()}.jpg")
                cameraFile = f
                runCatching { camera.launch(Files.shareUri(context, f)) }
            }, Modifier.weight(1f), icon = R.drawable.ic_photo_camera)
        }
    }
}

