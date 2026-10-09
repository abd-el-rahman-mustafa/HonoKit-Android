package com.aamus.powerpdf.engine.img

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaCodecList
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import androidx.heifwriter.HeifWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The image tools' engine on the phone (decisions §34; the desktop's is src/engine/img). Android's
 * own codecs read and write pictures; files are recognised by their first bytes, never by name
 * (a HEIC photo called .jpg, a .jfif that is simply a JPEG).
 */
enum class ImgFormat(val label: String, val mime: String, val ext: String, val alpha: Boolean, val lossy: Boolean) {
    JPG("JPG", "image/jpeg", "jpg", false, true),
    PNG("PNG", "image/png", "png", true, false),
    WEBP("WEBP", "image/webp", "webp", true, true),
    HEIC("HEIC", "image/heic", "heic", false, true),
    AVIF("AVIF", "image/avif", "avif", true, true),
    GIF("GIF", "image/gif", "gif", true, false),
    BMP("BMP", "image/bmp", "bmp", false, false),
    WBMP("WBMP", "image/vnd.wap.wbmp", "wbmp", false, false),
    ICO("ICO", "image/x-icon", "ico", true, false),
    DNG("DNG", "image/x-adobe-dng", "dng", false, false),
    PDF("PDF", "application/pdf", "pdf", false, true),
    ;

    /** This phone can write the format (HEIC needs a hardware HEVC encoder). */
    val writable: Boolean
        get() = when (this) {
            JPG, PNG, WEBP, BMP, ICO, PDF -> true
            HEIC -> ImageKit.canWriteHeic
            else -> false
        }

    companion object {
        /** What a file really is, from its first bytes (and its name only when nothing else tells). */
        fun sniff(head: ByteArray, name: String = ""): ImgFormat? {
            fun at(i: Int) = if (i < head.size) head[i].toInt() and 255 else -1
            fun ascii(start: Int, s: String) = s.indices.all { at(start + it) == s[it].code }
            return when {
                at(0) == 0xff && at(1) == 0xd8 && at(2) == 0xff -> JPG
                at(0) == 0x89 && ascii(1, "PNG") -> PNG
                ascii(0, "GIF8") -> GIF
                ascii(0, "RIFF") && ascii(8, "WEBP") -> WEBP
                ascii(4, "ftyp") -> {
                    val brand = String(head.copyOfRange(8, min(12, head.size)), Charsets.ISO_8859_1)
                    val rest = String(head.copyOfRange(16, min(64, head.size)), Charsets.ISO_8859_1)
                    when {
                        brand == "avif" || brand == "avis" -> AVIF
                        brand in setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1") -> if ("avif" in rest) AVIF else HEIC
                        else -> null
                    }
                }
                ascii(0, "BM") -> BMP
                at(0) == 0 && at(1) == 0 && at(2) == 1 && at(3) == 0 -> ICO
                ascii(0, "%PDF") -> PDF
                ((at(0) == 0x49 && at(1) == 0x49 && at(2) == 0x2a && at(3) == 0) || (at(0) == 0x4d && at(1) == 0x4d && at(2) == 0 && at(3) == 0x2a)) && name.lowercase().endsWith(".dng") -> DNG
                name.lowercase().endsWith(".wbmp") -> WBMP
                else -> null
            }
        }

        val writableNow: List<ImgFormat> get() = entries.filter { it.writable }
    }
}

/** What a picture is, for the board's card and Image info. */
data class ImageFacts(val format: ImgFormat, val width: Int, val height: Int, val bytes: Long, val hasAlpha: Boolean)

/** A text or picture mark laid on a picture (Watermark), placed by fractions of the picture. */
data class Mark(
    val stamp: Bitmap,
    /** Centre, as fractions of the picture. */
    val x: Float,
    val y: Float,
    /** Width as a fraction of the picture's width. */
    val width: Float,
    val rotate: Float,
    val opacity: Float,
    val tile: Boolean = false,
    val gap: Float = 1f,
)

object ImageKit {
    /** A bitmap above this many pixels is decoded smaller: 50 MP is 200 MB in memory. */
    const val MAX_PIXELS = 36_000_000L

    val canWriteHeic: Boolean by lazy {
        Build.VERSION.SDK_INT >= 28 && runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { c -> c.isEncoder && c.supportedTypes.any { it.equals("video/hevc", true) || it.equals("image/vnd.android.heic", true) } }
        }.getOrDefault(false)
    }

    private fun head(resolver: ContentResolver, uri: Uri): ByteArray = resolver.openInputStream(uri)?.use { s ->
        val buf = ByteArray(4096)
        val n = s.read(buf)
        if (n <= 0) ByteArray(0) else buf.copyOf(n)
    } ?: ByteArray(0)

    private fun orientation(resolver: ContentResolver, uri: Uri): Int = runCatching {
        resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
    }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

    /** Format and upright size without decoding the picture. */
    fun facts(resolver: ContentResolver, uri: Uri, size: Long): ImageFacts? {
        val format = ImgFormat.sniff(head(resolver, uri)) ?: return null
        if (format == ImgFormat.PDF) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var w = o.outWidth
        var h = o.outHeight
        if (w <= 0 || h <= 0) {
            if (Build.VERSION.SDK_INT < 28) return null
            // HEIC/AVIF on some phones: only ImageDecoder reads the header.
            val src = ImageDecoder.createSource(resolver, uri)
            runCatching { ImageDecoder.decodeBitmap(src) { d, info, _ -> w = info.size.width; h = info.size.height; d.setTargetSize(1, 1) } }
            if (w <= 0 || h <= 0) return null
        }
        val turn = orientation(resolver, uri)
        val sideways = turn in setOf(ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE)
        return ImageFacts(format, if (sideways) h else w, if (sideways) w else h, size, format.alpha)
    }

    /**
     * The picture upright (EXIF orientation applied), at most [maxSide] on its longer side and
     * [MAX_PIXELS] in all; mutable, ARGB_8888.
     */
    fun decode(resolver: ContentResolver, uri: Uri, maxSide: Int = Int.MAX_VALUE): Bitmap {
        if (Build.VERSION.SDK_INT >= 28) {
            val src = ImageDecoder.createSource(resolver, uri)
            // ImageDecoder turns pictures upright itself.
            return ImageDecoder.decodeBitmap(src) { d, info, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                d.isMutableRequired = true
                val (w, h) = info.size.width to info.size.height
                val s = min(1.0, min(maxSide.toDouble() / max(w, h), Math.sqrt(MAX_PIXELS.toDouble() / (w.toLong() * h))))
                if (s < 1.0) d.setTargetSize(max(1, (w * s).roundToInt()), max(1, (h * s).roundToInt()))
            }.let { if (it.config != Bitmap.Config.ARGB_8888) it.copy(Bitmap.Config.ARGB_8888, true) else it }
        }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var sample = 1
        while (o.outWidth / (sample * 2) >= 1 && (max(o.outWidth, o.outHeight) / sample > maxSide || o.outWidth.toLong() * o.outHeight / (sample * sample) > MAX_PIXELS)) sample *= 2
        val bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample; inMutable = true }) }
            ?: throw IllegalArgumentException("not a picture this phone can read")
        return upright(bmp, orientation(resolver, uri))
    }

    private fun upright(bmp: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bmp
        }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    // --- operations ------------------------------------------------------------------------

    /** A box given as fractions of the picture. */
    fun crop(b: Bitmap, x: Float, y: Float, w: Float, h: Float): Bitmap {
        val left = (x * b.width).roundToInt().coerceIn(0, b.width - 1)
        val top = (y * b.height).roundToInt().coerceIn(0, b.height - 1)
        val cw = (w * b.width).roundToInt().coerceIn(1, b.width - left)
        val ch = (h * b.height).roundToInt().coerceIn(1, b.height - top)
        return Bitmap.createBitmap(b, left, top, cw, ch)
    }

    /** The largest box of shape [ratio] (width / height); [fx], [fy] place it (0 start, 0.5 middle, 1 end). */
    fun cropRatio(b: Bitmap, ratio: Float, fx: Float = 0.5f, fy: Float = 0.5f): Bitmap {
        var w = b.width.toFloat()
        var h = w / ratio
        if (h > b.height) { h = b.height.toFloat(); w = h * ratio }
        return crop(b, (b.width - w) * fx / b.width, (b.height - h) * fy / b.height, w / b.width, h / b.height)
    }

    /** Any angle, clockwise; corners a free angle uncovers get [background] (transparent by default). */
    fun rotate(b: Bitmap, degrees: Float, flipH: Boolean = false, flipV: Boolean = false, background: Int = Color.TRANSPARENT): Bitmap {
        val a = ((degrees % 360) + 360) % 360
        if (a == 0f && !flipH && !flipV) return b
        val m = Matrix().apply {
            postRotate(a)
            postScale(if (flipH) -1f else 1f, if (flipV) -1f else 1f)
        }
        val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        if (background == Color.TRANSPARENT || a % 90f == 0f) return r
        val out = Bitmap.createBitmap(r.width, r.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply { drawColor(background); drawBitmap(r, 0f, 0f, null) }
        return out
    }

    /** A smooth reduction: halving steps first (one big step aliases), then the last bit. */
    fun resize(b: Bitmap, w: Int, h: Int): Bitmap {
        if (w == b.width && h == b.height) return b
        var cur = b
        while (cur.width / 2 >= w && cur.height / 2 >= h) cur = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
        return if (cur.width == w && cur.height == h) cur else Bitmap.createScaledBitmap(cur, max(1, w), max(1, h), true)
    }

    /** Marks laid on a copy of the picture, each by its centre, scaled to the picture's width. */
    fun mark(b: Bitmap, marks: List<Mark>): Bitmap {
        val out = b.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        for (m in marks) {
            val w = m.width * out.width
            val h = w * m.stamp.height / m.stamp.width
            paint.alpha = (m.opacity * 255).roundToInt().coerceIn(0, 255)
            val place = { cx: Float, cy: Float ->
                c.save()
                c.rotate(m.rotate, cx, cy)
                c.drawBitmap(m.stamp, null, RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2), paint)
                c.restore()
            }
            if (m.tile) {
                val gx = w * (1 + m.gap)
                val gy = h * (1 + m.gap)
                var y = gy / 2
                while (y < out.height + gy) {
                    var x = gx / 2
                    while (x < out.width + gx) { place(x, y); x += gx }
                    y += gy
                }
            } else place(m.x * out.width, m.y * out.height)
        }
        return out
    }

    // --- writing ---------------------------------------------------------------------------

    /**
     * Encodes in [format]. Formats without transparency get the picture on [background].
     * [quality] 1–100 for lossy formats; [lossless] for WebP.
     */
    fun encode(b: Bitmap, format: ImgFormat, quality: Int = 85, lossless: Boolean = false, background: Int = Color.WHITE, work: File? = null): ByteArray {
        val src = if (!format.alpha && b.hasAlpha()) flatten(b, background) else b
        val out = ByteArrayOutputStream()
        when (format) {
            ImgFormat.JPG -> src.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)
            ImgFormat.PNG -> src.compress(Bitmap.CompressFormat.PNG, 100, out)
            ImgFormat.WEBP -> if (Build.VERSION.SDK_INT >= 30) src.compress(if (lossless) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY, if (lossless) 100 else quality, out)
            else @Suppress("DEPRECATION") src.compress(Bitmap.CompressFormat.WEBP, if (lossless) 100 else quality, out)
            ImgFormat.BMP -> return bmp(src)
            ImgFormat.ICO -> return ico(src, listOf(256, 128, 64, 48, 32, 16))
            ImgFormat.HEIC -> return heic(src, quality, work ?: throw IllegalArgumentException("HEIC needs a work file"))
            else -> throw IllegalArgumentException("${format.label} cannot be written on this phone")
        }
        return out.toByteArray()
    }

    private fun flatten(b: Bitmap, background: Int): Bitmap {
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply { drawColor(background); drawBitmap(b, 0f, 0f, null) }
        return out
    }

    private fun heic(b: Bitmap, quality: Int, work: File): ByteArray {
        work.delete()
        val writer = HeifWriter.Builder(work.absolutePath, b.width, b.height, HeifWriter.INPUT_MODE_BITMAP).setQuality(quality.coerceIn(1, 100)).setMaxImages(1).build()
        try {
            writer.start()
            writer.addBitmap(b)
            writer.stop(30_000)
        } finally {
            writer.close()
        }
        return work.readBytes().also { work.delete() }
    }

    /** A 24-bit Windows bitmap, rows bottom-up and padded to four bytes. */
    fun bmp(b: Bitmap): ByteArray {
        val row = (b.width * 3 + 3) / 4 * 4
        val size = 54 + row * b.height
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put('B'.code.toByte()).put('M'.code.toByte()).putInt(size).putInt(0).putInt(54)
        buf.putInt(40).putInt(b.width).putInt(b.height).putShort(1).putShort(24).putInt(0).putInt(row * b.height).putInt(2835).putInt(2835).putInt(0).putInt(0)
        val px = IntArray(b.width)
        for (y in b.height - 1 downTo 0) {
            b.getPixels(px, 0, b.width, 0, y, b.width, 1)
            for (p in px) buf.put((p and 255).toByte()).put(((p shr 8) and 255).toByte()).put(((p shr 16) and 255).toByte())
            repeat(row - b.width * 3) { buf.put(0) }
        }
        return buf.array()
    }

    /** A Windows icon with a PNG picture for each size (as the desktop writes them). */
    fun ico(b: Bitmap, sizes: List<Int>): ByteArray {
        val pngs = sizes.map { s ->
            val sq = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            val scale = min(s.toFloat() / b.width, s.toFloat() / b.height)
            val w = b.width * scale
            val h = b.height * scale
            Canvas(sq).drawBitmap(b, null, RectF((s - w) / 2, (s - h) / 2, (s + w) / 2, (s + h) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
            s to ByteArrayOutputStream().also { sq.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }
        val head = ByteBuffer.allocate(6 + 16 * pngs.size).order(ByteOrder.LITTLE_ENDIAN)
        head.putShort(0).putShort(1).putShort(pngs.size.toShort())
        var offset = 6 + 16 * pngs.size
        for ((s, data) in pngs) {
            head.put((if (s >= 256) 0 else s).toByte()).put((if (s >= 256) 0 else s).toByte()).put(0).put(0).putShort(1).putShort(32).putInt(data.size).putInt(offset)
            offset += data.size
        }
        return head.array() + pngs.flatMap { it.second.asIterable() }.toByteArray()
    }

    /** The highest quality whose file fits [bytes] (seven tries at most); null if even the lowest does not. */
    fun fitSize(b: Bitmap, format: ImgFormat, bytes: Long): Pair<ByteArray, Int>? {
        var low = 5
        var high = 95
        var best: Pair<ByteArray, Int>? = null
        repeat(7) {
            if (low > high) return best
            val q = (low + high) / 2
            val data = encode(b, format, q)
            if (data.size <= bytes) { best = data to q; low = q + 1 } else high = q - 1
        }
        return best
    }
}
