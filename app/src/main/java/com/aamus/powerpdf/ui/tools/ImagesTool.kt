package com.aamus.powerpdf.ui.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.exifinterface.media.ExifInterface
import com.aamus.powerpdf.R
import com.aamus.powerpdf.ToolId
import com.aamus.powerpdf.data.Files
import com.aamus.powerpdf.engine.PageEdges
import com.aamus.powerpdf.engine.PageQuad
import com.aamus.powerpdf.engine.PageShape
import com.aamus.powerpdf.engine.PaperSize
import com.aamus.powerpdf.engine.PdfEngine
import com.aamus.powerpdf.engine.PdfImages
import com.aamus.powerpdf.engine.PictureInput
import com.aamus.powerpdf.engine.ScanFilters
import com.aamus.powerpdf.engine.ScanLook
import com.aamus.powerpdf.ui.Hint
import com.aamus.powerpdf.ui.IconAction
import com.aamus.powerpdf.ui.LocalModel
import com.aamus.powerpdf.ui.PrimaryButton
import com.aamus.powerpdf.ui.SecondaryButton
import com.aamus.powerpdf.ui.Segmented
import com.aamus.powerpdf.ui.Sym
import com.aamus.powerpdf.ui.Zoomable
import com.aamus.powerpdf.ui.theme.Power
import com.aamus.powerpdf.viewer.loupe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * One picture: where it comes from, a quarter-turn count, the page's four corners (0–1), how it is
 * cleaned ([look] and the adjustments) and what the scanner found out about it.
 */
data class Picture(
    val id: String = UUID.randomUUID().toString(),
    val uri: Uri,
    val turns: Int = 0,
    /** Top-left, top-right, bottom-right, bottom-left, as fractions of the upright picture. */
    val corners: List<Offset> = FULL,
    val look: ScanLook = ScanLook.Original,
    /** −0.5..0.5, 0 = none. */
    val brightness: Float = 0f,
    /** 0.6..1.6, 1 = none. */
    val contrast: Float = 1f,
    /** How much darker than the paper ink must be in black and white. */
    val inkLevel: Float = 0.78f,
    /** Unsharp mask, 0..2: set by itself on a blurry photo, adjustable for any page. */
    val sharpen: Float = 0f,
    /** The corners were found automatically (the scanner's page detection). */
    val found: Boolean = false,
    /** The photo looks shaken or out of focus (docs/research-scan.md S3). */
    val blurry: Boolean = false,
    /** A page, one side of an ID card, or both pages of an open book. */
    val kind: ScanKind = ScanKind.Document,
    /** A book: where the spine is, as a fraction of the straightened width (null: not found yet). */
    val split: Float? = null,
    /** The lens's focal length over the picture's diagonal, from the photo's EXIF (null: not named). */
    val focal: Double? = null,
) {
    /** A quarter turn clockwise; the corners turn with the picture (a book's spine is found again). */
    fun turned(): Picture {
        fun r(o: Offset) = Offset(1f - o.y, o.x)
        return copy(turns = turns + 1, corners = listOf(r(corners[3]), r(corners[0]), r(corners[1]), r(corners[2])), split = null)
    }

    /** The same cleaning as [other] (Apply to all). */
    fun cleanedLike(other: Picture) = copy(look = other.look, brightness = other.brightness, contrast = other.contrast, inkLevel = other.inkLevel, sharpen = if (blurry) maxOf(sharpen, other.sharpen) else other.sharpen)

    /** What the processed drawing depends on, for the preview cache. */
    fun key(side: Int, original: Boolean) =
        "$id|$turns|$corners|${if (original) "o" else "$look|$brightness|$contrast|$inkLevel|$sharpen"}|$side"

    companion object {
        val FULL = listOf(Offset(0f, 0f), Offset(1f, 0f), Offset(1f, 1f), Offset(0f, 1f))
    }
}

/**
 * How big the file is (docs/research-scan.md M4): the picture's longest side and JPEG quality.
 * Black-and-white pages are stored one bit a pixel whatever is chosen.
 */
enum class ScanQuality(val side: Int, val jpeg: Int) { Small(1700, 72), Standard(2400, 84), High(3500, 92) }

/** What is open in place of the review screen's tool row. */
private enum class ScanPanel { None, Looks, Adjust }

/** The page size chosen for the PDF; [Auto] snaps each page to A4 or Letter when it nearly is one. */
enum class PageSizeChoice { Auto, A4, Letter, Fit }

/**
 * What the scanner is pointed at (docs/research-camscanner.md, part 2): a page; an ID card, whose
 * front and back share one page at the card's real size; or an open book, whose photo becomes two
 * pages split at the spine.
 */
enum class ScanKind { Document, IdCard, Book }

/** Whether picture [i] is the back of an ID card: the second of each pair in a run of card sides. */
fun isCardBack(list: List<Picture>, i: Int): Boolean {
    if (list.getOrNull(i)?.kind != ScanKind.IdCard) return false
    var run = 0
    var k = i - 1
    while (k >= 0 && list[k].kind == ScanKind.IdCard) {
        run++
        k--
    }
    return run % 2 == 1
}

/** Where the spine of the open book inside [corners] is (ScanLayout.spine), on a 900 px copy. */
fun findSpine(context: Context, uri: Uri, turns: Int, corners: List<Offset>, focal: Double? = null): Float {
    val small = decodeUpright(context, uri, turns, 1200) ?: return 0.5f
    val page = warp(small, corners, 900, focal)
    small.recycle()
    val px = IntArray(page.width * page.height)
    page.getPixels(px, 0, page.width, 0, 0, page.width, page.height)
    val s = com.aamus.powerpdf.engine.ScanLayout.spine(px, page.width, page.height)
    page.recycle()
    return s
}

/** A finished page as a PDF picture: ink-or-paper one bit a pixel, anything else a JPEG. */
private fun pictureInput(done: Bitmap, bilevel: Boolean, jpeg: Int, paper: PaperSize?, margin: Float? = null): PictureInput =
    if (bilevel) {
        val px = IntArray(done.width * done.height)
        done.getPixels(px, 0, done.width, 0, 0, done.width, done.height)
        PictureInput(com.aamus.powerpdf.engine.Bilevel.pack(px, done.width, done.height), done.width, done.height, paper, bilevel = true, margin = margin)
    } else {
        val bytes = ByteArrayOutputStream().also { done.compress(Bitmap.CompressFormat.JPEG, jpeg, it) }.toByteArray()
        PictureInput(bytes, done.width, done.height, paper, margin = margin)
    }

/**
 * An ID card's front and back (or one side) on a sheet of [paper] at 300 dpi, each at the card's
 * real size (ISO ID-1, or a passport page), with rounded corners and a hairline edge, so the PDF
 * prints at 100% as a true copy.
 */
private fun idSheet(context: Context, sides: List<Picture>, paper: PaperSize, jpeg: Int): PictureInput? {
    val dpi = 300f
    val pw = (paper.width / 72f * dpi).roundToInt()
    val ph = (paper.height / 72f * dpi).roundToInt()
    val sheet = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(sheet)
    canvas.drawColor(android.graphics.Color.WHITE)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xFFBDBDBD.toInt()
    }
    // The front in the upper part of the page, the back under it.
    val centres = if (sides.size == 2) listOf(0.3f, 0.66f) else listOf(0.3f)
    var drawn = 0
    for ((k, p) in sides.withIndex()) {
        val up = decodeUpright(context, p.uri, p.turns, 1800) ?: continue
        val done = process(up, p, maxSide = 1600)
        up.recycle()
        val (mmW, mmH) = com.aamus.powerpdf.engine.ScanLayout.cardSize(done.width, done.height)
        val w = mmW / 25.4f * dpi
        val h = mmH / 25.4f * dpi
        val left = (pw - w) / 2
        val top = ph * centres[k] - h / 2
        val rect = android.graphics.RectF(left, top, left + w, top + h)
        // ID-1 corners are rounded 3.18 mm.
        val radius = 3.18f / 25.4f * dpi
        val clip = android.graphics.Path().apply { addRoundRect(rect, radius, radius, android.graphics.Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawBitmap(done, null, rect, paint)
        canvas.restore()
        canvas.drawRoundRect(rect, radius, radius, edge)
        done.recycle()
        drawn++
    }
    if (drawn == 0) {
        sheet.recycle()
        return null
    }
    val r = pictureInput(sheet, bilevel = false, jpeg = jpeg, paper = paper, margin = 0f)
    sheet.recycle()
    return r
}

/** The page's corners in the picture (docs/decisions.md §15), or null when no page stands out. */
fun findPage(context: Context, uri: Uri, turns: Int): List<Offset>? {
    val small = decodeUpright(context, uri, turns, 320) ?: return null
    val px = IntArray(small.width * small.height)
    small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
    // The edge-and-line search first (light desks, pages filling the photo: decisions §30); the
    // old light-region search when it is not sure (it still finds pages on dark backgrounds).
    val found = PageQuad.detect(px, small.width, small.height)?.takeIf { it.confident }?.corners
        // The page model when the edges are not enough (a sheet on other paper; decisions §31).
        ?: com.aamus.powerpdf.ocr.PageFinder.open(context)?.use { f ->
            f.find(px, small.width, small.height)?.takeIf { it.confidence >= 0.6f && PageQuad.plausible(it.corners) }?.corners
        }
        ?: PageEdges.find(px, small.width, small.height)
    small.recycle()
    return found?.let { f -> (0 until 4).map { Offset(f[it * 2], f[it * 2 + 1]) } }
}

/** Whether the photo of the page inside [corners] looks blurred, on a 600 px copy. */
fun looksBlurry(context: Context, uri: Uri, turns: Int, corners: List<Offset>): Boolean {
    val small = decodeUpright(context, uri, turns, 900) ?: return false
    val page = warp(small, corners, 600)
    small.recycle()
    val px = IntArray(page.width * page.height)
    page.getPixels(px, 0, page.width, 0, 0, page.width, page.height)
    val q = ScanFilters.quality(px, page.width, page.height)
    page.recycle()
    return q.blurry
}

/**
 * The lens the photo was taken with, as a fraction of the picture's diagonal (EXIF "35 mm
 * equivalent" focal length over the 35 mm frame's diagonal), or null when the photo does not say.
 */
fun lensFocal(context: Context, uri: Uri): Double? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0) }
}.getOrNull()?.takeIf { it > 0 }?.let { it / PageShape.FILM_DIAGONAL_MM }

/** Decodes a picture upright (EXIF applied, then [turns]), at most [maxSide] pixels. */
fun decodeUpright(context: Context, uri: Uri, turns: Int, maxSide: Int): Bitmap? {
    val cr = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    // Measuring only: decodeStream returns null here by design, so only a missing stream fails.
    val stream = cr.openInputStream(uri) ?: return null
    stream.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
    val exif = runCatching { cr.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
    val m = android.graphics.Matrix()
    when (exif) {
        ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
    }
    m.postRotate(90f * (((turns % 4) + 4) % 4))
    var out = if (m.isIdentity) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true).also { if (it != bmp) bmp.recycle() }
    val longest = maxOf(out.width, out.height)
    if (longest > maxSide) {
        val k = maxSide.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(out, (out.width * k).roundToInt(), (out.height * k).roundToInt(), true)
        if (scaled != out) out.recycle()
        out = scaled
    }
    return out
}

/**
 * Straightens the page inside the four corners (a perspective warp), in the sheet's real shape
 * (PageShape: a page shot at an angle is not stretched). [maxSide], when given, caps the result's
 * longest side; [focal] is the lens (lensFocal), when the photo names it.
 */
fun warp(src: Bitmap, corners: List<Offset>, maxSide: Int = Int.MAX_VALUE, focal: Double? = null): Bitmap {
    val w = src.width.toFloat()
    val h = src.height.toFloat()
    val pts = corners.map { Offset(it.x * w, it.y * h) }
    val full = corners == Picture.FULL
    var outW: Float
    var outH: Float
    if (full) {
        outW = w
        outH = h
    } else {
        val across = maxOf(hypot(pts[1].x - pts[0].x, pts[1].y - pts[0].y), hypot(pts[2].x - pts[3].x, pts[2].y - pts[3].y)).coerceAtLeast(16f)
        val down = maxOf(hypot(pts[3].x - pts[0].x, pts[3].y - pts[0].y), hypot(pts[2].x - pts[1].x, pts[2].y - pts[1].y)).coerceAtLeast(16f)
        val flat = FloatArray(8) { if (it % 2 == 0) corners[it / 2].x else corners[it / 2].y }
        val aspect = PageShape.aspect(flat, src.width, src.height, focal).toFloat()
        // As many pixels as the photo has on the longer measured side, in the real shape.
        outW = maxOf(across, down * aspect)
        outH = outW / aspect
    }
    val cap = maxOf(outW, outH)
    if (cap > maxSide) {
        outW *= maxSide / cap
        outH *= maxSide / cap
    }
    val out = Bitmap.createBitmap(outW.roundToInt().coerceAtLeast(1), outH.roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawColor(android.graphics.Color.WHITE)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    val m = android.graphics.Matrix()
    val from = floatArrayOf(pts[0].x, pts[0].y, pts[1].x, pts[1].y, pts[2].x, pts[2].y, pts[3].x, pts[3].y)
    val to = floatArrayOf(0f, 0f, out.width.toFloat(), 0f, out.width.toFloat(), out.height.toFloat(), 0f, out.height.toFloat())
    m.setPolyToPoly(from, 0, to, 0, 4)
    canvas.drawBitmap(src, m, paint)
    return out
}

/** Straightens the page and cleans it as [p] says ([original]: straightened only). */
fun process(src: Bitmap, p: Picture, original: Boolean = false, maxSide: Int = Int.MAX_VALUE): Bitmap {
    val out = warp(src, p.corners, maxSide, p.focal)
    val plain = original || (p.look == ScanLook.Original && p.brightness == 0f && p.contrast == 1f && p.sharpen == 0f)
    if (!plain) {
        val px = IntArray(out.width * out.height)
        out.getPixels(px, 0, out.width, 0, 0, out.width, out.height)
        ScanFilters.apply(px, out.width, out.height, p.look, p.brightness, p.contrast, p.inkLevel, p.sharpen)
        out.setPixels(px, 0, out.width, 0, 0, out.width, out.height)
    }
    return out
}

/** Decoded pictures and processed previews, kept while the screen is open. */
private class PreviewCache {
    private val sources = object : LruCache<String, Bitmap>(3) {}
    private val pages = object : LruCache<String, ImageBitmap>(48) {}

    fun cached(p: Picture, side: Int, original: Boolean): ImageBitmap? = pages.get(p.key(side, original))

    fun render(context: Context, p: Picture, side: Int, original: Boolean): ImageBitmap? {
        pages.get(p.key(side, original))?.let { return it }
        // The decoded photo is shared by every look and size up to 1400 px.
        val srcSide = if (side <= 400) 600 else 1400
        val srcKey = "${p.id}|${p.turns}|$srcSide"
        val src = sources.get(srcKey) ?: decodeUpright(context, p.uri, p.turns, srcSide)?.also { sources.put(srcKey, it) } ?: return null
        val out = process(src, p, original, side).asImageBitmap()
        pages.put(p.key(side, original), out)
        return out
    }
}

/** A processed page, from the cache at once when it is there, else when it is ready. */
@Composable
private fun PageImage(
    cache: PreviewCache,
    p: Picture,
    side: Int,
    modifier: Modifier = Modifier,
    original: Boolean = false,
    /** Drawn over the page once it is there (the book's split line). */
    overlay: (@Composable (ImageBitmap) -> Unit)? = null,
) {
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    // The drawing stays while the new one is made (no flash); a spinner says it is coming.
    val image by produceState(cache.cached(p, side, original), p, side, original) {
        val ready = cache.cached(p, side, original)
        if (ready != null) {
            value = ready
            return@produceState
        }
        busy = true
        val t0 = System.currentTimeMillis()
        value = withContext(Dispatchers.Default) {
            runCatching { cache.render(context, p, side, original) }.onFailure { android.util.Log.w("PowerPDF", "scan preview $side failed", it) }.getOrNull()
        } ?: value
        if (side > 400) android.util.Log.i("PowerPDF", "scan preview ${p.look} $side: ${System.currentTimeMillis() - t0} ms")
        busy = false
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val img = image
        if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        if (img != null && overlay != null) overlay(img)
        if (img == null || busy) CircularProgressIndicator(Modifier.size(if (img == null) 28.dp else 22.dp), color = Power.colors.accent, strokeWidth = 2.5.dp)
    }
}


private val LOOKS = listOf(ScanLook.Auto, ScanLook.Original, ScanLook.Bright, ScanLook.Grey, ScanLook.BlackWhite, ScanLook.Whiteboard)

@Composable
private fun lookName(look: ScanLook) = stringResource(
    when (look) {
        ScanLook.Auto -> R.string.look_auto
        ScanLook.Original -> R.string.look_original
        ScanLook.Grey -> R.string.look_grey
        ScanLook.BlackWhite -> R.string.look_bw
        ScanLook.Whiteboard -> R.string.look_whiteboard
        ScanLook.Bright -> R.string.look_bright
    },
)

/**
 * A book photo's split line over the page drawn by [PageImage] (fitted, centred, [imageW] ×
 * [imageH]): dashed, with a round handle to drag it sideways; [onSplit] when let go.
 */
@Composable
private fun SplitLine(split: Float, imageW: Int, imageH: Int, onSplit: (Float) -> Unit, modifier: Modifier = Modifier) {
    var at by remember(split) { mutableStateOf(split) }
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val boxW = with(density) { maxWidth.toPx() }
        val boxH = with(density) { maxHeight.toPx() }
        if (imageW <= 0 || imageH <= 0) return@BoxWithConstraints
        val k = minOf(boxW / imageW, boxH / imageH)
        val dw = imageW * k
        val dh = imageH * k
        val left = (boxW - dw) / 2
        val top = (boxH - dh) / 2
        val accent = Power.colors.accent
        Canvas(Modifier.fillMaxSize()) {
            val x = left + at * dw
            drawLine(Color.White, Offset(x, top), Offset(x, top + dh), strokeWidth = 5.dp.toPx())
            drawLine(
                accent, Offset(x, top), Offset(x, top + dh), strokeWidth = 2.5.dp.toPx(),
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 6.dp.toPx())),
            )
            drawCircle(Color.White, 15.dp.toPx(), Offset(x, top + dh / 2))
            drawCircle(accent, 12.dp.toPx(), Offset(x, top + dh / 2))
        }
        // The handle: a wide strip around the line takes the drag (not the pager's swipe).
        // Placed from the left whatever the language, like the drawing and the picture.
        val handleW = 48.dp
        val handlePx = with(density) { handleW.toPx() }
        Box(
            Modifier
                .align(androidx.compose.ui.AbsoluteAlignment.TopLeft)
                .absoluteOffset { IntOffset((left + at * dw - handlePx / 2).roundToInt(), top.roundToInt()) }
                .width(handleW)
                .height(with(density) { dh.toDp() })
                .pointerInput(dw, left) {
                    detectHorizontalDragGestures(
                        onDragEnd = { onSplit(at) },
                        onDragCancel = { onSplit(at) },
                    ) { change, delta ->
                        change.consume()
                        at = (at + delta / dw).coerceIn(0.2f, 0.8f)
                    }
                },
        )
    }
}

/**
 * Images to PDF and Scan (docs/research-scan.md M1): one large page at a time with its clean-up,
 * swiped through; the looks shown on the page itself; crop, turn, adjust, retake, delete and undo;
 * a strip of pages to reorder by long-press drag; page size and margin; Create PDF.
 */
@Composable
fun ImagesTool(inputs: List<Uri>, camera: Boolean, kind: ScanKind = ScanKind.Document) {
    val model = LocalModel.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = Power.colors
    val pictures = remember { mutableStateListOf<Picture>() }
    val history = remember { mutableStateListOf<List<Picture>>() }
    val cache = remember { PreviewCache() }
    var size by remember { mutableStateOf(if (camera) PageSizeChoice.Auto else PageSizeChoice.A4) }
    var margin by remember { mutableStateOf(0) }
    var quality by remember { mutableStateOf(ScanQuality.Standard) }
    /** Recognize the text of the new PDF (decisions §29), so it can be searched and copied. */
    var searchable by remember { mutableStateOf(false) }
    var cropping by remember { mutableStateOf<Int?>(null) }
    /** The looks or the sliders, open in place of the tool row. */
    var panel by remember { mutableStateOf(ScanPanel.None) }
    var optionsOpen by remember { mutableStateOf(false) }
    var showOriginal by remember { mutableStateOf(false) }
    var cameraFile by remember { mutableStateOf<File?>(null) }
    /** The page a photo being taken replaces (Retake), or null to add one. */
    var retakeAt by remember { mutableStateOf<Int?>(null) }
    /** The scanner's own camera is open; [liveSingle]: for one picture (Retake). */
    var live by remember { mutableStateOf(false) }
    var liveSingle by remember { mutableStateOf(false) }
    /** The camera permission was refused: the phone's camera app is used instead. */
    var refused by remember { mutableStateOf(false) }
    val pager = rememberPagerState { pictures.size }
    val current = pager.currentPage.coerceIn(0, maxOf(0, pictures.size - 1))
    /** What new pictures are of; the camera's mode strip changes it. */
    var scanKind by remember { mutableStateOf(kind) }
    /** A book's pages in reading order: right first in Arabic. */
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var rightFirst by remember { mutableStateOf(rtl) }
    /** Cards are not paper: they keep their colours (Bright); pages are cleaned (Auto). */
    fun lookFor(k: ScanKind) = when {
        !camera -> ScanLook.Original
        k == ScanKind.IdCard -> ScanLook.Bright
        else -> ScanLook.Auto
    }

    /** Remembers the pages as they are, for Undo. */
    fun remember() {
        history.add(pictures.toList())
        if (history.size > 30) history.removeAt(0)
    }

    fun undo() {
        val last = history.removeLastOrNull() ?: return
        pictures.clear()
        pictures.addAll(last)
    }

    fun show(i: Int) {
        scope.launch { if (i in pictures.indices) pager.animateScrollToPage(i) }
    }

    /** A new picture as the scanner sees it: its page found, its sharpness checked. */
    suspend fun inspect(u: Uri, like: Picture?, k: ScanKind = scanKind): Picture {
        val focal = withContext(Dispatchers.IO) { lensFocal(context, u) }
        if (!camera) return Picture(uri = u, look = like?.look ?: lookFor(k), focal = focal)
        val corners = withContext(Dispatchers.Default) { runCatching { findPage(context, u, 0) }.getOrNull() }
        val p = Picture(uri = u, corners = corners ?: Picture.FULL, look = lookFor(k), found = corners != null, kind = k, focal = focal)
        val blurry = withContext(Dispatchers.Default) { runCatching { looksBlurry(context, u, 0, p.corners) }.getOrDefault(false) }
        // A page like the last one is cleaned like it (not a card like a page).
        val cleaned = if (like != null && like.kind == k) p.cleanedLike(like) else p
        val split = if (k == ScanKind.Book) withContext(Dispatchers.Default) { runCatching { findSpine(context, u, 0, p.corners, p.focal) }.getOrDefault(0.5f) } else null
        // A blurry photo is sharpened by itself (owner, 2026-10-01); Adjust can change it.
        return cleaned.copy(blurry = blurry, sharpen = if (blurry) maxOf(cleaned.sharpen, 1f) else cleaned.sharpen, split = split)
    }

    /** A book picture whose corners or turn changed looks for its spine again. */
    LaunchedEffect(pictures.map { if (it.kind == ScanKind.Book && it.split == null) it.id else "" }.joinToString()) {
        for (i in pictures.indices) {
            val p = pictures[i]
            if (p.kind != ScanKind.Book || p.split != null) continue
            val s = withContext(Dispatchers.Default) { runCatching { findSpine(context, p.uri, p.turns, p.corners, p.focal) }.getOrDefault(0.5f) }
            val now = pictures.getOrNull(i)
            if (now?.id == p.id && now.split == null) pictures[i] = now.copy(split = s)
        }
    }

    fun add(uris: List<Uri>, fromCamera: Boolean = false) {
        scope.launch {
            val at = retakeAt
            retakeAt = null
            if (at != null && at in pictures.indices && uris.isNotEmpty()) {
                remember()
                pictures[at] = inspect(uris.first(), pictures[at], pictures[at].kind).copy(id = pictures[at].id + "r")
                show(at)
                return@launch
            }
            if (uris.isNotEmpty()) remember()
            val like = pictures.lastOrNull()
            for (u in uris) pictures.add(inspect(u, like))
            if (uris.isNotEmpty()) {
                show(pictures.lastIndex)
                // A photo whose page was not found opens straight in the crop editor.
                if (fromCamera && !pictures.last().found) cropping = pictures.lastIndex
            }
        }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris -> add(uris) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraFile
        if (ok && f != null && f.length() > 0) add(listOf(Uri.fromFile(f)), fromCamera = true) else retakeAt = null
    }
    /** The phone's own camera app, for one picture (the fallback). */
    fun systemCamera(replace: Int?) {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val f = File(dir, "${UUID.randomUUID()}.jpg")
        cameraFile = f
        retakeAt = replace
        try {
            takePhoto.launch(Files.shareUri(context, f))
        } catch (e: Throwable) {
            retakeAt = null
            // No camera app, or the camera permission was refused (then Android refuses the camera
            // app too, because HonoKit declares the permission): photos already on the phone are
            // the way on.
            val why = if (e is SecurityException) R.string.error_camera_refused else R.string.error_no_camera
            model.say(model.str(why), action = model.str(R.string.from_gallery), long = true) { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        }
    }

    /** Opens the scanner's live camera (docs/research-scan.md M5), or the phone's camera app. */
    fun shoot(replace: Int? = null) {
        if (refused) return systemCamera(replace)
        retakeAt = replace
        liveSingle = replace != null
        live = true
    }
    fun pick() = gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    LaunchedEffect(Unit) {
        if (pictures.isEmpty()) {
            add(inputs)
            if (camera && inputs.isEmpty()) shoot()
        }
    }

    fun delete(i: Int) {
        if (i !in pictures.indices) return
        remember()
        pictures.removeAt(i)
        model.say(model.str(R.string.page_deleted), action = model.str(R.string.undo)) { undo() }
    }

    fun move(from: Int, to: Int) {
        if (from == to || from !in pictures.indices || to !in pictures.indices) return
        remember()
        pictures.add(to, pictures.removeAt(from))
        show(to)
    }

    fun create() {
        val list = pictures.toList()
        val choice = size
        val q = quality
        val rightPageFirst = rightFirst
        val ocr = searchable
        scope.launch {
            try {
                val inputsPdf = withContext(Dispatchers.Default) {
                    val out = ArrayList<PictureInput>()
                    /** A page cut out of a straightened picture: Auto size snaps it to A4 or Letter. */
                    fun add(done: Bitmap, look: ScanLook) {
                        val own = if (choice == PageSizeChoice.Auto) ScanFilters.snapPaper(done.width, done.height) ?: PaperSize.Fit else null
                        // Ink or paper: one bit a pixel, a small part of a JPEG of the same page.
                        out.add(pictureInput(done, look == ScanLook.BlackWhite, q.jpeg, own))
                    }
                    var i = 0
                    while (i < list.size) {
                        val p = list[i]
                        withContext(Dispatchers.Main) { model.busy = model.str(R.string.page_progress, i + 1, list.size) }
                        if (p.kind == ScanKind.IdCard) {
                            // The front and the back that follows it share a page.
                            val back = list.getOrNull(i + 1)?.takeIf { it.kind == ScanKind.IdCard }
                            val sheetPaper = if (choice == PageSizeChoice.Letter) PaperSize.Letter else PaperSize.A4
                            idSheet(context, listOfNotNull(p, back), sheetPaper, q.jpeg)?.let { out.add(it) }
                            i += if (back != null) 2 else 1
                            continue
                        }
                        // Black and white is always made at about 300 dpi: one bit a pixel stays
                        // small, and thin Arabic strokes stay smooth (docs/research-camscanner.md, 4).
                        // A book photo holds two pages, so it is made twice as wide.
                        val base = if (p.look == ScanLook.BlackWhite) ScanQuality.High.side else q.side
                        val side = if (p.kind == ScanKind.Book) (base * 1.5f).roundToInt() else base
                        val up = decodeUpright(context, p.uri, p.turns, side + 200)
                        if (up == null) {
                            i++
                            continue
                        }
                        val done = process(up, p, maxSide = side)
                        up.recycle()
                        if (p.kind == ScanKind.Book) {
                            val cut = ((p.split ?: 0.5f) * done.width).roundToInt().coerceIn(1, done.width - 1)
                            val leftPage = Bitmap.createBitmap(done, 0, 0, cut, done.height)
                            val rightPage = Bitmap.createBitmap(done, cut, 0, done.width - cut, done.height)
                            for (page in if (rightPageFirst) listOf(rightPage, leftPage) else listOf(leftPage, rightPage)) {
                                add(page, p.look)
                                page.recycle()
                            }
                        } else {
                            add(done, p.look)
                        }
                        done.recycle()
                        i++
                    }
                    out
                }
                model.busy = model.str(R.string.working)
                val out = Files.newWorkFile(context)
                val m = when (margin) {
                    1 -> 18f
                    2 -> 42f
                    else -> 0f
                }
                val paper = when (choice) {
                    PageSizeChoice.A4 -> PaperSize.A4
                    PageSizeChoice.Letter -> PaperSize.Letter
                    else -> PaperSize.Fit
                }
                PdfEngine.run { PdfImages.imagesToPdf(inputsPdf, paper, m, out) }
                if (ocr) {
                    // The new pages are pictures: their text goes into an invisible layer.
                    val withText = Files.newWorkFile(context)
                    try {
                        com.aamus.powerpdf.doc.Recognize.makeSearchable(context, out, withText) { p, of ->
                            scope.launch(Dispatchers.Main) { model.busy = model.str(R.string.ocr_reading_page, p, of) }
                        }
                        withText.renameTo(out).let { moved -> if (!moved) { withText.copyTo(out, overwrite = true); withText.delete() } }
                    } catch (e: com.aamus.powerpdf.ocr.OcrException.Unsupported) {
                        // A 32-bit phone: the PDF is still made, without the text layer.
                        withText.delete()
                        model.say(model.str(R.string.ocr_64bit), long = true)
                    }
                }
                model.busy = null
                val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH.mm", java.util.Locale.US).format(java.util.Date())
                val base = model.str(
                    when {
                        !camera -> R.string.images_name
                        list.all { it.kind == ScanKind.IdCard } -> R.string.kind_id
                        list.all { it.kind == ScanKind.Book } -> R.string.kind_book
                        else -> R.string.scanned_name
                    },
                )
                val saved = model.saveNewFile(out, "$base $stamp.pdf")
                out.delete()
                if (saved != null) {
                    model.pop()
                    model.showSaved(if (camera) ToolId.Scan else ToolId.Images, listOf(saved.first), saved.second)
                }
            } catch (e: Throwable) {
                model.say(model.errorText(e))
            } finally {
                model.busy = null
            }
        }
    }

    com.aamus.powerpdf.OnDebug { d ->
        when (d.cmd) {
            "crop" -> cropping = d.value.toIntOrNull()
            "cropdone" -> cropping = null
            "look" -> {
                val i = d.value.substringBefore(',').toIntOrNull() ?: 0
                val look = ScanLook.entries.firstOrNull { it.name.equals(d.value.substringAfter(','), true) } ?: ScanLook.Original
                if (i in pictures.indices) pictures[i] = pictures[i].copy(look = look)
            }
            "applyall" -> pictures.getOrNull(current)?.let { p -> for (i in pictures.indices) pictures[i] = pictures[i].cleanedLike(p) }
            "adjust" -> panel = if (d.value != "off") ScanPanel.Adjust else ScanPanel.None
            "looks" -> panel = if (d.value != "off") ScanPanel.Looks else ScanPanel.None
            "original" -> showOriginal = d.value != "off"
            "showpage" -> d.value.toIntOrNull()?.let { show(it) }
            "move" -> d.value.split(',').mapNotNull { it.trim().toIntOrNull() }.takeIf { it.size == 2 }?.let { move(it[0], it[1]) }
            "corners" -> {
                // "i,x0,y0,x1,y1,x2,y2,x3,y3" as fractions: top-left, top-right, bottom-right, bottom-left.
                val v = d.value.split(',').mapNotNull { it.trim().toFloatOrNull() }
                val i = v.getOrNull(0)?.toInt() ?: 0
                if (v.size == 9 && i in pictures.indices) pictures[i] = pictures[i].copy(corners = (0 until 4).map { Offset(v[1 + it * 2], v[2 + it * 2]) })
            }
            "find" -> {
                val i = d.value.toIntOrNull() ?: 0
                if (i in pictures.indices) scope.launch {
                    val found = withContext(Dispatchers.Default) { findPage(context, pictures[i].uri, pictures[i].turns) }
                    android.util.Log.i("PowerPDF", "find page $i: $found")
                    if (found != null) pictures[i] = pictures[i].copy(corners = found, found = true)
                }
            }
            "turn" -> d.value.toIntOrNull()?.takeIf { it in pictures.indices }?.let { pictures[it] = pictures[it].turned() }
            "paper" -> size = PageSizeChoice.entries.firstOrNull { it.name.equals(d.value, true) } ?: size
            "kind" -> scanKind = ScanKind.entries.firstOrNull { it.name.equals(d.value, true) } ?: scanKind
            "split" -> {
                val i = d.value.substringBefore(',').toIntOrNull() ?: 0
                val v = d.value.substringAfter(',').toFloatOrNull()
                if (i in pictures.indices && v != null) pictures[i] = pictures[i].copy(split = v)
            }
            "order" -> rightFirst = d.value == "right"
            "searchable" -> searchable = d.value != "off"
            "go" -> create()
        }
    }

    val toolId = when {
        !camera -> ToolId.Images
        kind == ScanKind.IdCard -> ToolId.ScanId
        kind == ScanKind.Book -> ToolId.ScanBook
        else -> ToolId.Scan
    }
    // The page takes the screen (owner, 2026-10-02: "the tools took over more than half the
    // screen"): its number in the bar, what the scanner found on the page itself, one row of tools,
    // and the looks or the sliders only while they are being used.
    val pageCount = pictures.indices.sumOf { i ->
        // The PDF's pages: a book photo makes two, a card's two sides make one.
        when (pictures[i].kind) {
            ScanKind.Book -> 2
            ScanKind.IdCard -> if (isCardBack(pictures, i)) 0 else 1
            else -> 1
        }
    }
    // Back closes an open panel first; with pages taken it asks before they are thrown away
    // (one Back used to lose the whole scan).
    var leaveAsk by remember { mutableStateOf(false) }
    fun back() {
        when {
            panel != ScanPanel.None -> panel = ScanPanel.None
            pictures.isNotEmpty() -> leaveAsk = true
            else -> model.pop()
        }
    }
    androidx.activity.compose.BackHandler(enabled = true) { back() }
    if (leaveAsk) {
        AlertDialog(
            onDismissRequest = { leaveAsk = false },
            title = { Text(stringResource(R.string.scan_leave_title)) },
            text = { Text(stringResource(R.string.scan_leave_body)) },
            confirmButton = { com.aamus.powerpdf.ui.DialogButton(stringResource(R.string.discard_change), { leaveAsk = false; model.pop() }, danger = true) },
            dismissButton = { com.aamus.powerpdf.ui.DialogButton(stringResource(R.string.cancel), { leaveAsk = false }) },
            containerColor = c.surface,
        )
    }
    ToolFrame(
        toolId,
        subtitle = if (pictures.isNotEmpty()) stringResource(R.string.scan_page_of, current + 1, pictures.size) else null,
        onBack = { back() },
        actions = {
            if (pictures.isNotEmpty()) {
                IconAction(R.drawable.ic_visibility, stringResource(R.string.show_original), { showOriginal = !showOriginal }, selected = showOriginal)
                IconAction(R.drawable.ic_undo, stringResource(R.string.undo), { undo() }, enabled = history.isNotEmpty())
            }
        },
        bottom = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val sizeName = stringResource(
                    when (size) {
                        PageSizeChoice.Auto -> R.string.size_auto
                        PageSizeChoice.A4 -> R.string.size_a4
                        PageSizeChoice.Letter -> R.string.size_letter
                        PageSizeChoice.Fit -> R.string.size_fit
                    },
                )
                Row(
                    Modifier
                        .clip(CircleShape)
                        .border(1.dp, c.line, CircleShape)
                        .clickable { optionsOpen = true }
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .widthIn(max = 140.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Sym(R.drawable.ic_tune, stringResource(R.string.pdf_page_options), size = 18.dp, tint = c.muted)
                    Spacer(Modifier.width(6.dp))
                    Text(sizeName, style = MaterialTheme.typography.labelLarge, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                PrimaryButton(pluralStringResource(R.plurals.images_button, pageCount, pageCount), { create() }, Modifier.weight(1f), enabled = pictures.isNotEmpty())
            }
        },
    ) {
        if (pictures.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Sym(if (camera) R.drawable.ic_photo_camera else R.drawable.ic_photo_library, null, size = 48.dp, tint = c.muted)
                Spacer(Modifier.height(12.dp))
                Hint(stringResource(R.string.scan_empty))
                Spacer(Modifier.height(20.dp))
                // Side by side, or one under the other when large text makes them too wide.
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SecondaryButton(stringResource(R.string.from_gallery), { pick() }, icon = R.drawable.ic_photo_library)
                    PrimaryButton(stringResource(R.string.take_photo), { shoot() }, icon = R.drawable.ic_photo_camera)
                }
            }
            return@ToolFrame
        }
        val p = pictures[current]
        val mixed = pictures.size > 1 && pictures.any { it.look != p.look || it.brightness != p.brightness || it.contrast != p.contrast || it.inkLevel != p.inkLevel }
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().background(c.sunken)) {
                HorizontalPager(pager, Modifier.fillMaxSize(), key = { pictures.getOrNull(it)?.id ?: it }, beyondViewportPageCount = 1) { i ->
                    val pi = pictures.getOrNull(i) ?: return@HorizontalPager
                    Box(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
                        // Pinch or double-tap to look closely (owner, 2026-10-01); a tap opens the crop.
                        Zoomable(onTap = { cropping = i }) {
                            PageImage(
                                cache, pi, 1400, Modifier.fillMaxSize(), original = showOriginal,
                                overlay = if (pi.kind == ScanKind.Book && pi.split != null) { img ->
                                    SplitLine(pi.split, img.width, img.height, onSplit = { s ->
                                        val now = pictures.getOrNull(i)
                                        if (now != null && now.id == pi.id && now.split != s) {
                                            remember()
                                            pictures[i] = now.copy(split = s)
                                        }
                                    }, Modifier.fillMaxSize())
                                } else null,
                            )
                        }
                    }
                }
                // What the scanner found out, small, on the page's top corner.
                Row(Modifier.align(Alignment.TopStart).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (p.kind == ScanKind.IdCard) Badge(R.drawable.ic_id_card, stringResource(if (isCardBack(pictures, current)) R.string.card_back else R.string.card_front), Color(0xE60D6E8A))
                    else if (p.kind == ScanKind.Book) Badge(R.drawable.ic_menu_book, stringResource(R.string.book_two_pages), Color(0xE69A2B33))
                    if (p.blurry) Badge(R.drawable.ic_warning, stringResource(if (p.sharpen > 0f) R.string.blurry_sharpened else R.string.looks_blurry), Color(0xE67A4500))
                    else if (p.found) Badge(R.drawable.ic_check_circle, stringResource(R.string.edges_found), Color(0xE61E6B3A), compact = true)
                }
                // A hint, when there is one, over the bottom of the page: not a row of its own.
                val hint = when {
                    p.blurry -> stringResource(if (p.sharpen > 0f) R.string.blurry_sharpened_hint else R.string.blurry_hint)
                    p.kind == ScanKind.IdCard -> stringResource(R.string.id_hint)
                    p.kind == ScanKind.Book -> stringResource(R.string.book_hint)
                    else -> null
                }
                if (hint != null && panel == ScanPanel.None) {
                    Text(
                        hint,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xB3202020))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            HorizontalDivider(color = c.line)
            when (panel) {
                ScanPanel.Adjust -> AdjustPanel(p, onChange = { pictures[current] = it }, onReset = { pictures[current] = p.copy(brightness = 0f, contrast = 1f, inkLevel = 0.78f, sharpen = 0f) }, onDone = { panel = ScanPanel.None })
                ScanPanel.Looks -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                LazyRow(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The looks, each shown on this page; then Apply to all (while the pages
                    // differ) and Done, in the same row so the page keeps its room.
                    itemsIndexed(LOOKS) { _, look ->
                        LookChoice(cache, p, look, selected = p.look == look) {
                            if (p.look != look) {
                                remember()
                                pictures[current] = p.copy(look = look)
                            }
                        }
                    }
                    if (mixed) {
                        item {
                            PanelAction(R.drawable.ic_copy_all, stringResource(R.string.apply_to_all)) {
                                remember()
                                for (k in pictures.indices) pictures[k] = pictures[k].cleanedLike(p)
                            }
                        }
                    }
                }
                // Done stays in view at the row's end.
                PanelAction(R.drawable.ic_check, stringResource(R.string.done), accent = true) { panel = ScanPanel.None }
                }
                ScanPanel.None -> Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
                    ToolButton(R.drawable.ic_filter_b_and_w, lookName(p.look), Modifier.weight(1f)) { panel = ScanPanel.Looks }
                    ToolButton(R.drawable.ic_crop, stringResource(R.string.crop), Modifier.weight(1f)) { cropping = current }
                    ToolButton(R.drawable.ic_rotate_right, stringResource(R.string.rotate), Modifier.weight(1f)) {
                        remember()
                        pictures[current] = p.turned()
                    }
                    ToolButton(R.drawable.ic_tune, stringResource(R.string.adjust), Modifier.weight(1f)) {
                        remember()
                        panel = ScanPanel.Adjust
                    }
                    if (camera) ToolButton(R.drawable.ic_photo_camera, stringResource(R.string.retake), Modifier.weight(1f)) { shoot(replace = current) }
                    ToolButton(R.drawable.ic_delete, stringResource(R.string.delete), Modifier.weight(1f)) { delete(current) }
                }
            }
            HorizontalDivider(color = c.line)
            PageStrip(cache, pictures, current, onSelect = { show(it) }, onMove = { from, to -> move(from, to) }, onAdd = { if (camera) shoot() else pick() }, onGallery = if (camera) ({ pick() }) else null)
        }
    }

    if (optionsOpen) {
        AlertDialog(
            onDismissRequest = { optionsOpen = false },
            title = { Text(stringResource(R.string.pdf_page_options)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.page_size), style = MaterialTheme.typography.labelLarge, color = c.muted)
                    Segmented(listOf(PageSizeChoice.Auto to stringResource(R.string.size_auto), PageSizeChoice.A4 to stringResource(R.string.size_a4), PageSizeChoice.Letter to stringResource(R.string.size_letter), PageSizeChoice.Fit to stringResource(R.string.size_fit)), size, { size = it })
                    if (size == PageSizeChoice.Auto) Hint(stringResource(R.string.size_auto_note))
                    Text(stringResource(R.string.margin), style = MaterialTheme.typography.labelLarge, color = c.muted)
                    Segmented(listOf(0 to stringResource(R.string.margin_none), 1 to stringResource(R.string.margin_small), 2 to stringResource(R.string.margin_large)), margin, { margin = it })
                    Text(stringResource(R.string.file_quality), style = MaterialTheme.typography.labelLarge, color = c.muted)
                    Segmented(listOf(ScanQuality.Small to stringResource(R.string.quality_small), ScanQuality.Standard to stringResource(R.string.quality_standard), ScanQuality.High to stringResource(R.string.quality_high)), quality, { quality = it })
                    Hint(stringResource(R.string.quality_note))
                    if (camera) {
                        com.aamus.powerpdf.ui.SwitchRow(stringResource(R.string.scan_searchable), searchable, { searchable = it }, stringResource(R.string.scan_searchable_note))
                    }
                    if (pictures.any { it.kind == ScanKind.Book }) {
                        Text(stringResource(R.string.book_order), style = MaterialTheme.typography.labelLarge, color = c.muted)
                        Segmented(listOf(true to stringResource(R.string.book_right_first), false to stringResource(R.string.book_left_first)), rightFirst, { rightFirst = it })
                    }
                }
            },
            confirmButton = { com.aamus.powerpdf.ui.DialogButton(stringResource(R.string.done), { optionsOpen = false }) },
            containerColor = c.surface,
        )
    }

    if (live) {
        ScanCamera(
            pages = pictures.size,
            last = pictures.lastOrNull()?.uri,
            single = liveSingle,
            // A retake keeps the picture's own kind; otherwise the strip chooses.
            kind = if (liveSingle) retakeAt?.let { pictures.getOrNull(it)?.kind } ?: scanKind else scanKind,
            onKind = if (liveSingle) null else ({ scanKind = it }),
            // The next card side, to say which one to show.
            cardBack = pictures.isNotEmpty() && pictures.last().kind == ScanKind.IdCard && !isCardBack(pictures, pictures.lastIndex),
            onPhoto = { uri -> add(listOf(uri)) },
            onDone = { live = false },
            onGallery = {
                live = false
                pick()
            },
            onFallback = {
                live = false
                refused = true
                systemCamera(retakeAt)
            },
        )
    }

    cropping?.let { i ->
        val p = pictures.getOrNull(i)
        if (p == null) cropping = null else CropEditor(p, onDismiss = { cropping = null }, onDone = { np ->
            if (np.corners != p.corners) {
                remember()
                pictures[i] = np.copy(split = if (np.kind == ScanKind.Book) null else np.split)
            }
            cropping = null
        }, onNext = if (camera) { np ->
            if (np.corners != p.corners) {
                remember()
                pictures[i] = np.copy(split = if (np.kind == ScanKind.Book) null else np.split)
            }
            cropping = null
            shoot()
        } else null)
    }
}

/** An icon with its name under it (the scan screen's tools). */
@Composable
private fun ToolButton(icon: Int, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Power.colors
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Sym(icon, null, size = 22.dp, tint = c.ink)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A small mark on the page: what the scanner found out. */
@Composable
private fun Badge(icon: Int, text: String, background: Color, compact: Boolean = false) {
    Row(
        Modifier.clip(CircleShape).background(background).padding(start = if (compact) 4.dp else 8.dp, end = if (compact) 4.dp else 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Sym(icon, if (compact) text else null, size = 16.dp, tint = Color.White)
        if (!compact) {
            Spacer(Modifier.width(4.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** One look, shown on the page itself, with its name. */
@Composable
private fun LookChoice(cache: PreviewCache, p: Picture, look: ScanLook, selected: Boolean, onClick: () -> Unit) {
    val c = Power.colors
    Column(
        Modifier
            .width(64.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PageImage(
            cache,
            p.copy(look = look),
            200,
            Modifier
                .size(56.dp, 70.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(c.sunken)
                .border(if (selected) 2.5.dp else 1.dp, if (selected) c.accent else c.line, RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.height(4.dp))
        Text(lookName(look), style = MaterialTheme.typography.labelMedium, color = if (selected) c.accent else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Brightness and contrast (and the black-and-white level), seen live on the page. */
@Composable
private fun AdjustPanel(p: Picture, onChange: (Picture) -> Unit, onReset: () -> Unit, onDone: () -> Unit) {
    val c = Power.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        // The page is redrawn when the finger lifts (onValueChangeFinished, also sent by TalkBack's
        // steps): cleaning a 1400 px page for every step of a drag made the sliders lag.
        var b by remember(p.id) { mutableStateOf(p.brightness) }
        var k by remember(p.id) { mutableStateOf(p.contrast) }
        var ink by remember(p.id) { mutableStateOf(p.inkLevel) }
        var sh by remember(p.id) { mutableStateOf(p.sharpen) }
        fun commit() = onChange(p.copy(brightness = b, contrast = k, inkLevel = ink, sharpen = sh))

        @Composable
        fun row(label: String, value: Float, range: ClosedFloatingPointRange<Float>, set: (Float) -> Unit) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = c.ink, modifier = Modifier.width(110.dp), maxLines = 2)
                Slider(value, set, Modifier.weight(1f), valueRange = range, onValueChangeFinished = { commit() }, colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent))
            }
        }
        row(stringResource(R.string.brightness), b, -0.5f..0.5f) { b = it }
        row(stringResource(R.string.contrast), k, 0.6f..1.6f) { k = it }
        row(stringResource(R.string.sharpen), sh, 0f..2f) { sh = it }
        if (p.look == ScanLook.BlackWhite) row(stringResource(R.string.bw_level), ink, 0.6f..0.92f) { ink = it }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton({
                b = 0f
                k = 1f
                ink = 0.78f
                sh = 0f
                onReset()
            }) { Text(stringResource(R.string.reset)) }
            TextButton(onDone) { Text(stringResource(R.string.done)) }
        }
    }
}

/** The pages in a row: tap to show one, long-press and drag to move it, + to add one. */
@Composable
private fun PageStrip(
    cache: PreviewCache,
    pictures: List<Picture>,
    current: Int,
    onSelect: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onAdd: () -> Unit,
    onGallery: (() -> Unit)?,
) {
    val c = Power.colors
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val state = rememberLazyListState()
    var dragging by remember { mutableStateOf(-1) }
    var dragX by remember { mutableStateOf(0f) }
    val step = with(density) { (46 + 8).dp.toPx() }
    LaunchedEffect(current) { if (current in pictures.indices) state.animateScrollToItem(maxOf(0, current - 2)) }
    LazyRow(state = state, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(pictures, key = { _, p -> p.id }) { i, p ->
            val isDragged = dragging == i
            val shift = if (dragging >= 0 && !isDragged) {
                // The others make room where the dragged page would land.
                val target = (dragging + (dragX * (if (rtl) -1 else 1) / step).roundToInt()).coerceIn(0, pictures.lastIndex)
                when {
                    i in (dragging + 1)..target -> -step
                    i in target until dragging -> step
                    else -> 0f
                } * (if (rtl) -1 else 1)
            } else 0f
            Box(
                Modifier
                    .zIndex(if (isDragged) 1f else 0f)
                    .graphicsLayer {
                        translationX = if (isDragged) dragX else shift
                        val s = if (isDragged) 1.08f else 1f
                        scaleX = s
                        scaleY = s
                    }
                    .size(46.dp, 60.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(c.sunken)
                    .border(if (i == current) 2.5.dp else 1.dp, if (i == current) c.accent else c.line, RoundedCornerShape(8.dp))
                    .pointerInput(p.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                dragging = pictures.indexOfFirst { it.id == p.id }
                                dragX = 0f
                            },
                            onDrag = { ch, d ->
                                ch.consume()
                                dragX += d.x
                            },
                            onDragEnd = {
                                val from = dragging
                                val to = (from + (dragX * (if (rtl) -1 else 1) / step).roundToInt()).coerceIn(0, pictures.lastIndex)
                                dragging = -1
                                dragX = 0f
                                if (from >= 0) onMove(from, to)
                            },
                            onDragCancel = {
                                dragging = -1
                                dragX = 0f
                            },
                        )
                    }
                    .clickable { onSelect(i) },
            ) {
                PageImage(cache, p, 200, Modifier.fillMaxSize().padding(3.dp))
                Text(
                    "${i + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp).clip(CircleShape).background(Color(0xCC2C2C2C)).padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
        item {
            // Side by side, each a full 48 dp touch target (they were 46 × 27 dp, stacked).
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AddTile(R.drawable.ic_add, stringResource(R.string.add_page), onAdd)
                if (onGallery != null) AddTile(R.drawable.ic_photo_library, stringResource(R.string.from_gallery), onGallery)
            }
        }
    }
}

@Composable
private fun AddTile(icon: Int, label: String, onClick: () -> Unit) {
    val c = Power.colors
    Box(
        Modifier
            .size(48.dp, 60.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, c.line, RoundedCornerShape(8.dp))
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Sym(icon, label, size = 20.dp, tint = c.accent) }
}

/** Whether [pt] is inside the four-cornered shape [q] (convex, in order). */
private fun inside(pt: Offset, q: List<Offset>): Boolean {
    var sign = 0
    for (i in 0 until 4) {
        val a = q[i]
        val b = q[(i + 1) % 4]
        val c = (b.x - a.x) * (pt.y - a.y) - (b.y - a.y) * (pt.x - a.x)
        val s = if (c > 0) 1 else if (c < 0) -1 else 0
        if (s != 0) {
            if (sign != 0 && s != sign) return false
            sign = s
        }
    }
    return true
}

/**
 * The page's corners over the picture (docs/research-scan.md M3): drag a corner (with a loupe), the
 * middle of an edge (the whole edge) or inside the frame (all of it); the part outside is dimmed and
 * a small straightened copy shows the result.
 */
@Composable
private fun CropEditor(p: Picture, onDismiss: () -> Unit, onDone: (Picture) -> Unit, onNext: ((Picture) -> Unit)? = null) {
    val c = Power.colors
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var corners by remember { mutableStateOf(p.corners) }
    var dragging by remember { mutableStateOf(-1) }
    var hint by remember { mutableStateOf(if (p.found) R.string.crop_found else R.string.crop_hint) }
    var finding by remember { mutableStateOf(false) }
    fun find() {
        finding = true
        scope.launch {
            val found = withContext(Dispatchers.Default) { runCatching { findPage(context, p.uri, p.turns) }.getOrNull() }
            finding = false
            if (found != null) corners = found
            hint = if (found != null) R.string.crop_found else R.string.crop_not_found
        }
    }
    val image by produceState<ImageBitmap?>(null, p.uri, p.turns) {
        value = withContext(Dispatchers.Default) { decodeUpright(context, p.uri, p.turns, 1600)?.asImageBitmap() }
    }
    // A small copy for the straightened preview.
    val small by produceState<Bitmap?>(null, p.uri, p.turns) {
        value = withContext(Dispatchers.Default) { decodeUpright(context, p.uri, p.turns, 700) }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF111111)).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconAction(R.drawable.ic_close, stringResource(R.string.cancel), onDismiss, tint = Color.White)
                Text(stringResource(R.string.crop), style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.weight(1f))
                // The scanner goes straight on to the next page (research-ux-mobile.md 4.5).
                if (onNext != null) {
                    TextButton({ onNext(p.copy(corners = corners, found = p.found && corners == p.corners)) }) {
                        Text(stringResource(R.string.next_page), color = Color.White, style = MaterialTheme.typography.labelLarge)
                    }
                }
                Box(Modifier.padding(end = 8.dp)) { PrimaryButton(stringResource(R.string.done), { onDone(p.copy(corners = corners, found = p.found && corners == p.corners)) }) }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                val img = image
                if (img != null) {
                    val boxW = with(density) { maxWidth.toPx() }
                    val boxH = with(density) { maxHeight.toPx() }
                    val k = minOf(boxW / img.width, boxH / img.height)
                    val w = img.width * k
                    val h = img.height * k
                    val handle = with(density) { 28.dp.toPx() }
                    var finger by remember { mutableStateOf<Offset?>(null) }
                    Canvas(
                        Modifier
                            .size(with(density) { w.toDp() }, with(density) { h.toDp() })
                            .pointerInput(w, h) {
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    val pts = corners.map { Offset(it.x * w, it.y * h) }
                                    val mids = (0 until 4).map { (pts[it] + pts[(it + 1) % 4]) / 2f }
                                    fun near(list: List<Offset>) = list.indices.minByOrNull { (list[it] - down.position).getDistance() }?.takeIf { (list[it] - down.position).getDistance() < handle * 1.6f }
                                    // A corner, else an edge's middle (the whole edge), else inside the frame (all of it).
                                    dragging = near(pts) ?: near(mids)?.let { 4 + it } ?: if (inside(down.position, pts)) 8 else -1
                                    if (dragging < 0) return@awaitEachGesture
                                    val start = corners
                                    while (true) {
                                        val e = awaitPointerEvent()
                                        val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!ch.pressed) break
                                        val d = Offset((ch.position.x - down.position.x) / w, (ch.position.y - down.position.y) / h)
                                        corners = if (dragging < 4) {
                                            val np = Offset((ch.position.x / w).coerceIn(0f, 1f), (ch.position.y / h).coerceIn(0f, 1f))
                                            finger = Offset(np.x * w, np.y * h)
                                            start.toMutableList().also { it[dragging] = np }
                                        } else {
                                            val moving = if (dragging == 8) (0 until 4).toList() else listOf(dragging - 4, (dragging - 3) % 4)
                                            // Kept inside the picture: the move stops where a moving corner would leave it.
                                            val dx = d.x.coerceIn(moving.maxOf { -start[it].x }, moving.minOf { 1f - start[it].x })
                                            val dy = d.y.coerceIn(moving.maxOf { -start[it].y }, moving.minOf { 1f - start[it].y })
                                            start.mapIndexed { i, o -> if (i in moving) Offset(o.x + dx, o.y + dy) else o }
                                        }
                                        ch.consume()
                                    }
                                    finger = null
                                    dragging = -1
                                }
                            },
                    ) {
                        drawImage(img, dstSize = IntSize(w.roundToInt(), h.roundToInt()), dstOffset = IntOffset.Zero)
                        val pts = corners.map { Offset(it.x * w, it.y * h) }
                        val quad = Path().apply {
                            moveTo(pts[0].x, pts[0].y)
                            pts.drop(1).forEach { lineTo(it.x, it.y) }
                            close()
                        }
                        val outside = Path().apply {
                            fillType = PathFillType.EvenOdd
                            addRect(androidx.compose.ui.geometry.Rect(0f, 0f, w, h))
                            addPath(quad)
                        }
                        drawPath(outside, Color.Black.copy(alpha = 0.5f))
                        drawPath(quad, Color(0xFF4B9CF5), style = Stroke(2.dp.toPx()))
                        // Edge handles: a short bar along the middle of each side.
                        for (k in 0 until 4) {
                            val a = pts[k]
                            val b = pts[(k + 1) % 4]
                            val mid = (a + b) / 2f
                            val len = (b - a).getDistance().coerceAtLeast(1f)
                            val along = (b - a) / len * 11.dp.toPx()
                            drawLine(Color.White, mid - along, mid + along, strokeWidth = 7.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                            drawLine(Color(0xFF1473E6), mid - along, mid + along, strokeWidth = if (dragging == 4 + k) 4.dp.toPx() else 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                        }
                        pts.forEachIndexed { i, pt ->
                            drawCircle(Color.White, 12.dp.toPx(), pt)
                            drawCircle(Color(0xFF1473E6), if (i == dragging) 9.dp.toPx() else 7.dp.toPx(), pt)
                        }
                        // The corner under the finger, magnified (research-ux-mobile.md 4.10).
                        finger?.let { f -> loupe(img, w, h, f) { drawPath(quad, Color(0xFF4B9CF5), style = Stroke(1.dp.toPx())) } }
                    }
                }
            }
            // The page as it will come out, straightened, redrawn when the finger lifts; beside the
            // hint, so it never covers a handle.
            val settled = if (dragging < 0) corners else null
            val straight by produceState<ImageBitmap?>(null, settled, small) {
                val cs = settled ?: return@produceState
                val src = small ?: return@produceState
                value = withContext(Dispatchers.Default) { runCatching { warp(src, cs, 420, p.focal).asImageBitmap() }.getOrNull() } ?: value
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                straight?.let { st ->
                    Image(
                        st, stringResource(R.string.crop_result), Modifier
                            .size(54.dp, 72.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.White)
                            .border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(6.dp)),
                        contentScale = ContentScale.Fit,
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    stringResource(hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFD0D0D0),
                    textAlign = if (straight != null) TextAlign.Start else TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
            Column(Modifier.fillMaxWidth().background(c.surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton(stringResource(R.string.crop_find), { find() }, Modifier.weight(1f), icon = R.drawable.ic_auto_fix_high, enabled = !finding)
                    SecondaryButton(stringResource(R.string.crop_reset), { corners = Picture.FULL; hint = R.string.crop_hint }, Modifier.weight(1f), icon = R.drawable.ic_fit_page_width)
                }
            }
            HorizontalDivider(color = c.line)
        }
    }
}

/** An action at the end of the looks row: an icon in a round button, its name under it. */
@Composable
private fun PanelAction(icon: Int, label: String, accent: Boolean = false, onClick: () -> Unit) {
    val c = Power.colors
    Column(
        Modifier.width(72.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(if (accent) c.accent else c.sunken),
            contentAlignment = Alignment.Center,
        ) { Sym(icon, null, size = 22.dp, tint = if (accent) Color.White else c.ink) }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = c.ink, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
    }
}
