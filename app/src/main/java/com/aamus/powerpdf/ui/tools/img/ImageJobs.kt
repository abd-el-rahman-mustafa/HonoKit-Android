package com.aamus.powerpdf.ui.tools.img

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import com.aamus.powerpdf.ToolId
import com.aamus.powerpdf.data.Files
import com.aamus.powerpdf.engine.img.CropBox
import com.aamus.powerpdf.engine.img.CropMath
import com.aamus.powerpdf.engine.img.HideArea
import com.aamus.powerpdf.engine.img.HideStyle
import com.aamus.powerpdf.engine.img.ImageExtras
import com.aamus.powerpdf.engine.img.ImageFacts
import com.aamus.powerpdf.engine.img.ImageKit
import com.aamus.powerpdf.engine.img.ImgFormat
import com.aamus.powerpdf.engine.img.Mark
import com.aamus.powerpdf.ocr.ImageAi
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What each image tool does to one picture (decisions §34), the phone's version of the desktop's
 * engine/img/tools.ts: decode upright → the tool's steps → encode → save into
 * Pictures › HonoKit › <tool>. Never overwrites: the system adds " (1)" to a taken name.
 */

enum class Level { Recommended, Strong, Light, Target }
enum class ResizeBy { Pixels, Percent, Longest }

/** Every tool's options; each tool reads its own. */
data class ImgOptions(
    // compress
    val level: Level = Level.Recommended,
    val targetKb: Int = 300,
    val compressTo: ImgFormat? = null,
    // resize
    val resizeBy: ResizeBy = ResizeBy.Longest,
    val width: Int? = 1920,
    val height: Int? = null,
    val keepShape: Boolean = true,
    val noEnlarge: Boolean = true,
    val percent: Int = 50,
    val longest: Int = 1600,
    // crop: a box you drag. With a shape (width ÷ height) the box is kept as a size and centre,
    // so it fits every picture in a batch; free, it is the same fractions on each (decisions §40).
    val ratio: Float? = null,
    val cropScale: Float = 0.8f,
    val cropCx: Float = 0.5f,
    val cropCy: Float = 0.5f,
    val cropFree: CropBox = CropBox(0.1f, 0.1f, 0.9f, 0.9f),
    // rotate
    val turn: Int = 90,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val angle: Float = 0f,
    // convert
    val convertTo: ImgFormat = ImgFormat.JPG,
    val quality: Int = 88,
    // watermark
    val markText: String = "",
    val markColour: Int = Color.WHITE,
    val markSize: Float = 0.4f,
    val markOpacity: Float = 0.8f,
    val markRotation: Float = 0f,
    val markX: Float = 0.5f,
    val markY: Float = 0.5f,
    val markTile: Boolean = false,
    val markOutline: Boolean = false,
    val markShadow: Boolean = true,
    val markPicture: Uri? = null,
    // AI
    val upscale: Int = 2,
    val bgTransparent: Boolean = true,
    val bgColour: Int = Color.WHITE,
    val findFaces: Boolean = true,
    val findPlates: Boolean = true,
    val hideStyle: HideStyle = HideStyle.Blur,
)

data class ImgResult(val uri: Uri, val name: String, val bytes: Long, val width: Int, val height: Int, val format: ImgFormat, val unchanged: Boolean = false, val error: String? = null)

/** The upright size a resize gives (what each card shows before running). */
fun resized(w: Int, h: Int, o: ImgOptions): Pair<Int, Int> = when (o.resizeBy) {
    ResizeBy.Percent -> max(1, w * o.percent / 100) to max(1, h * o.percent / 100)
    ResizeBy.Longest -> {
        val s = o.longest.toFloat() / max(w, h)
        if (o.noEnlarge && s >= 1f) w to h else max(1, (w * s).roundToInt()) to max(1, (h * s).roundToInt())
    }
    ResizeBy.Pixels -> {
        val tw = o.width
        val th = o.height
        when {
            tw == null && th == null -> w to h
            o.keepShape || tw == null || th == null -> {
                val s = min(tw?.let { it.toFloat() / w } ?: Float.MAX_VALUE, th?.let { it.toFloat() / h } ?: Float.MAX_VALUE)
                if (o.noEnlarge && s >= 1f) w to h else max(1, (w * s).roundToInt()) to max(1, (h * s).roundToInt())
            }
            else -> if (o.noEnlarge) min(tw, w) to min(th, h) else tw to th
        }
    }
}

/** The crop box for a [w] × [h] picture. */
fun cropFor(o: ImgOptions, w: Int, h: Int): CropBox =
    o.ratio?.let { CropMath.fit(it, w, h, o.cropCx, o.cropCy, o.cropScale) } ?: o.cropFree

/** The options after the box was dragged to [b] on a [w] × [h] picture. */
fun withCrop(o: ImgOptions, b: CropBox, w: Int, h: Int): ImgOptions = o.ratio?.let { r ->
    o.copy(cropScale = b.w / CropMath.fit(r, w, h).w, cropCx = b.cx, cropCy = b.cy)
} ?: o.copy(cropFree = b)

/** The format a picture is written in when the tool keeps "the same format". */
fun sameFormat(f: ImgFormat): ImgFormat = if (f.writable && f != ImgFormat.PDF && f != ImgFormat.ICO) f else ImgFormat.JPG

object ImageJobs {
    /** The picture after the tool's steps (also the live preview, on a small copy). */
    fun apply(context: Context, tool: ToolId, o: ImgOptions, src: Bitmap, ai: (() -> ImageAi)?, hide: List<HideArea> = emptyList(), stamp: Bitmap? = null, progress: (Float) -> Unit = {}): Bitmap = when (tool) {
        ToolId.ImgResize -> resized(src.width, src.height, o).let { (w, h) -> ImageKit.resize(src, w, h) }
        ToolId.ImgCrop -> cropFor(o, src.width, src.height).let { if (it.isFull) src else ImageKit.crop(src, it.l, it.t, it.w, it.h) }
        ToolId.ImgRotate -> ImageKit.rotate(src, o.turn + o.angle, o.flipH, o.flipV, if (o.angle % 90f != 0f) Color.WHITE else Color.TRANSPARENT)
        ToolId.ImgWatermark -> stamp?.let { ImageKit.mark(src, listOf(Mark(it, o.markX, o.markY, o.markSize, o.markRotation, o.markOpacity, o.markTile))) } ?: src
        ToolId.ImgUpscale -> ai!!().upscale(src, o.upscale, progress)
        ToolId.ImgRemoveBg -> ai!!().removeBackground(src, if (o.bgTransparent) null else o.bgColour)
        ToolId.ImgBlur -> ImageExtras.hide(src, hide, o.hideStyle)
        else -> src
    }

    /** The mark for Watermark: the chosen picture, or the text drawn large. */
    fun stamp(context: Context, o: ImgOptions, sample: String): Bitmap = o.markPicture?.let { ImageKit.decode(context.contentResolver, it, 1600) }
        ?: ImageExtras.textStamp(o.markText.ifBlank { sample }, o.markColour, o.markOutline, o.markShadow)

    /** Runs the tool on one picture and saves the result. */
    fun run(context: Context, tool: ToolId, o: ImgOptions, uri: Uri, name: String, facts: ImageFacts, suffix: String, ai: (() -> ImageAi)?, hide: List<HideArea>, stamp: Bitmap?, progress: (Float) -> Unit): ImgResult {
        val stem = name.substringBeforeLast('.')
        val original = File(Files.workDir(context), "in-${System.nanoTime()}").also { Files.copyIn(context, uri, it) }
        try {
            // Image info: the details removed from a copy of the file itself, pixels untouched.
            if (tool == ToolId.ImgInfo && facts.format in setOf(ImgFormat.JPG, ImgFormat.PNG, ImgFormat.WEBP)) {
                ImageExtras.stripMetadata(original)
                return save(context, tool, original.readBytes(), "$stem ($suffix).${facts.format.ext}", facts.format, facts.width, facts.height)
            }
            // A resize to a smaller size decodes smaller: a 50 MP photo never needs all its pixels then.
            val maxSide = if (tool == ToolId.ImgResize) resized(facts.width, facts.height, o).let { (w, h) -> max(w, h) * 2 } else Int.MAX_VALUE
            val src = ImageKit.decode(context.contentResolver, uri, maxSide)
            val out = apply(context, tool, o, src, ai, hide, stamp, progress)
            val target = when (tool) {
                ToolId.ImgConvert -> o.convertTo
                ToolId.ImgCompress -> o.compressTo ?: sameFormat(facts.format)
                ToolId.ImgRemoveBg -> if (o.bgTransparent) ImgFormat.PNG else sameFormat(facts.format)
                else -> sameFormat(facts.format)
            }
            val work = File(Files.workDir(context), "heic-${System.nanoTime()}.heic")
            val quality = when (tool) {
                ToolId.ImgCompress -> when (o.level) {
                    Level.Recommended -> if (target == ImgFormat.WEBP) 76 else 78
                    Level.Strong -> 60
                    Level.Light -> 90
                    Level.Target -> 80
                }
                ToolId.ImgConvert -> o.quality
                else -> 92
            }
            val data = if (tool == ToolId.ImgCompress && o.level == Level.Target && target.lossy) {
                ImageKit.fitSize(out, target, o.targetKb * 1024L)?.first ?: ImageKit.encode(out, target, 5, work = work)
            } else ImageKit.encode(out, target, quality, work = work)
            // Compress: a picture already as small as it gets is kept as it was, never made bigger.
            if (tool == ToolId.ImgCompress && data.size >= facts.bytes && target == facts.format) {
                return save(context, tool, original.readBytes(), "$stem ($suffix).${facts.format.ext}", target, facts.width, facts.height, unchanged = true)
            }
            return save(context, tool, data, "$stem ($suffix).${target.ext}", target, out.width, out.height)
        } finally {
            original.delete()
        }
    }

    private fun save(context: Context, tool: ToolId, data: ByteArray, name: String, format: ImgFormat, w: Int, h: Int, unchanged: Boolean = false): ImgResult {
        val uri = Files.saveToPictures(context, data, ImageJobsFolder.of(tool), name, format.mime)
        return ImgResult(uri, name, data.size.toLong(), w, h, format, unchanged)
    }
}
