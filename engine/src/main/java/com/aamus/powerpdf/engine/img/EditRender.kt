package com.aamus.powerpdf.engine.img

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The phone's Photo editor (decisions §37, §40), the desktop's components/PhotoEditor.tsx on Android
 * Canvas: one function, [EditRender.render], draws the edited picture for the screen (from a small
 * copy) and for saving (from the full picture). Marks, strokes and the crop are fractions of the
 * picture, so both come out the same.
 */

enum class EditFilter { None, Vivid, Warm, Cool, Mono, Noir, Sepia, Vintage, Faded, Dramatic, Polaroid, Kodachrome, Negative }

data class Adjust(
    val brightness: Int = 0,
    val contrast: Int = 0,
    val saturation: Int = 0,
    val warmth: Int = 0,
    val hue: Int = 0,
    val fade: Int = 0,
    val vignette: Int = 0,
)

/** A line drawn with the finger, as fractions of the edited picture. */
data class Stroke(val points: List<Pair<Float, Float>>, val colour: Int, val size: Float)

/** Text or a sticker on the picture: its drawn stamp, centre and width as fractions, turn. */
data class EditLayer(val id: Int, val text: String, val colour: Int, val outline: Boolean, val sticker: Boolean, val stamp: Bitmap, val x: Float, val y: Float, val width: Float, val rotate: Float)

data class EditState(
    val adjust: Adjust = Adjust(),
    val filter: EditFilter = EditFilter.None,
    /** Quarter turns clockwise. */
    val turn: Int = 0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val straighten: Float = 0f,
    /** The crop, as fractions of the turned and straightened picture (dragged by its corners). */
    val crop: CropBox = CropBox.Full,
    /** The shape the crop keeps while dragged (width ÷ height); null = free. */
    val ratio: Float? = null,
    val layers: List<EditLayer> = emptyList(),
    val strokes: List<Stroke> = emptyList(),
    /** Frame thickness and corner radius in % of the shorter side. */
    val frame: Float = 0f,
    val frameColour: Int = Color.WHITE,
    val radius: Float = 0f,
)

object EditRender {
    /** The colour matrix of a filter and the adjustments together. */
    fun colourMatrix(filter: EditFilter, a: Adjust): ColorMatrix {
        val m = ColorMatrix()
        fun then(other: ColorMatrix) = m.postConcat(other)
        fun saturate(s: Float) = then(ColorMatrix().apply { setSaturation(s) })
        fun contrast(c: Float) = then(ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, 128 * (1 - c), 0f, c, 0f, 0f, 128 * (1 - c), 0f, 0f, c, 0f, 128 * (1 - c), 0f, 0f, 0f, 1f, 0f)))
        fun brightness(b: Float) = then(ColorMatrix(floatArrayOf(b, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
        fun channels(r: Float, g: Float, b: Float) = then(ColorMatrix().apply { setScale(r, g, b, 1f) })
        fun sepia(amount: Float) {
            val s = floatArrayOf(0.393f, 0.769f, 0.189f, 0.349f, 0.686f, 0.168f, 0.272f, 0.534f, 0.131f)
            val k = 1 - amount
            then(ColorMatrix(floatArrayOf(
                k + amount * s[0], amount * s[1], amount * s[2], 0f, 0f,
                amount * s[3], k + amount * s[4], amount * s[5], 0f, 0f,
                amount * s[6], amount * s[7], k + amount * s[8], 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )))
        }
        fun hue(deg: Float) {
            // The CSS hue-rotate matrix: turns colours round the grey axis, keeping their lightness.
            val r = deg * PI.toFloat() / 180
            val c = cos(r)
            val s = sin(r)
            then(ColorMatrix(floatArrayOf(
                0.213f + c * 0.787f - s * 0.213f, 0.715f - c * 0.715f - s * 0.715f, 0.072f - c * 0.072f + s * 0.928f, 0f, 0f,
                0.213f - c * 0.213f + s * 0.143f, 0.715f + c * 0.285f + s * 0.140f, 0.072f - c * 0.072f - s * 0.283f, 0f, 0f,
                0.213f - c * 0.213f - s * 0.787f, 0.715f - c * 0.715f + s * 0.715f, 0.072f + c * 0.928f + s * 0.072f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )))
        }
        when (filter) {
            EditFilter.None -> {}
            EditFilter.Vivid -> { saturate(1.45f); contrast(1.1f) }
            EditFilter.Warm -> { sepia(0.25f); saturate(1.25f); brightness(1.03f) }
            EditFilter.Cool -> { hue(-12f); saturate(1.1f); brightness(1.03f) }
            EditFilter.Mono -> saturate(0f)
            EditFilter.Noir -> { saturate(0f); contrast(1.45f); brightness(0.95f) }
            EditFilter.Sepia -> sepia(0.85f)
            EditFilter.Vintage -> { sepia(0.4f); contrast(0.9f); brightness(1.05f); saturate(0.85f) }
            EditFilter.Faded -> { contrast(0.85f); brightness(1.08f); saturate(0.8f) }
            EditFilter.Dramatic -> { contrast(1.35f); saturate(1.15f); brightness(0.95f) }
            EditFilter.Polaroid -> { contrast(1.1f); brightness(1.05f); sepia(0.15f); saturate(1.2f) }
            EditFilter.Kodachrome -> { contrast(1.2f); saturate(1.35f); sepia(0.1f); hue(-5f) }
            EditFilter.Negative -> then(ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f)))
        }
        if (a.brightness != 0) brightness(1 + a.brightness / 100f)
        if (a.contrast != 0) contrast(1 + a.contrast / 100f)
        if (a.saturation != 0) saturate(1 + a.saturation / 100f)
        if (a.hue != 0) hue(a.hue.toFloat())
        if (a.warmth != 0) channels(1 + a.warmth / 600f, 1f, 1 - a.warmth / 600f)
        if (a.fade != 0) {
            // Lifted blacks, softer contrast: a faded print.
            val f = a.fade / 100f * 0.3f
            then(ColorMatrix(floatArrayOf(1 - f, 0f, 0f, 0f, f * 255, 0f, 1 - f, 0f, 0f, f * 255, 0f, 0f, 1 - f, 0f, f * 255, 0f, 0f, 0f, 1f, 0f)))
        }
        return m
    }

    /** The turned picture's size (before the crop). */
    fun turnedSize(w: Int, h: Int, turn: Int): Pair<Int, Int> = if (turn % 2 == 0) w to h else h to w

    /** The crop box (fractions of the turned picture), kept valid. */
    fun cropBox(w: Int, h: Int, e: EditState): RectF {
        val b = e.crop
        val l = b.l.coerceIn(0f, 0.999f)
        val t = b.t.coerceIn(0f, 0.999f)
        return RectF(l, t, b.r.coerceIn(l + 0.001f, 1f), b.b.coerceIn(t + 0.001f, 1f))
    }

    /** The edited picture's size in pixels for a source of [w] × [h]. */
    fun outputSize(w: Int, h: Int, e: EditState): Pair<Int, Int> {
        val (tw, th) = turnedSize(w, h, e.turn)
        val box = cropBox(w, h, e)
        return max(1, (box.width() * tw).roundToInt()) to max(1, (box.height() * th).roundToInt())
    }

    /** Draws the edited picture. With [original] the edits are left out (Compare). */
    fun render(src: Bitmap, e: EditState, original: Boolean = false): Bitmap {
        if (original) return src
        val (ow, oh) = outputSize(src.width, src.height, e)
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val short = min(ow, oh).toFloat()
        val radius = e.radius / 100f * short
        if (radius > 0f) c.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, ow.toFloat(), oh.toFloat()), radius, radius, Path.Direction.CW) })
        drawPhoto(c, src, e)
        drawMarks(c, e, ow, oh)
        return out
    }

    /**
     * Only the photo: turned, straightened, cropped, coloured, vignette — without drawing, text,
     * stickers, frame or rounded corners. The screen shows this and draws the rest live on top
     * (in the same fractions), so a finger never waits for a full render.
     */
    fun photo(src: Bitmap, e: EditState): Bitmap {
        val (ow, oh) = outputSize(src.width, src.height, e)
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        drawPhoto(Canvas(out), src, e)
        return out
    }

    private fun drawPhoto(c: Canvas, src: Bitmap, e: EditState) {
        val (tw, th) = turnedSize(src.width, src.height, e.turn)
        val box = cropBox(src.width, src.height, e)
        val (ow, oh) = outputSize(src.width, src.height, e)
        // The picture: turned, flipped, straightened (enlarged so no corner shows), coloured.
        c.save()
        c.translate(-box.left * tw, -box.top * th)
        c.translate(tw / 2f, th / 2f)
        if (e.straighten != 0f) {
            val a = e.straighten * PI.toFloat() / 180
            val cover = max((tw * abs(cos(a)) + th * abs(sin(a))) / tw, (tw * abs(sin(a)) + th * abs(cos(a))) / th)
            c.rotate(e.straighten)
            c.scale(cover, cover)
        }
        c.scale(if (e.flipH) -1f else 1f, if (e.flipV) -1f else 1f)
        c.rotate(e.turn * 90f)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(colourMatrix(e.filter, e.adjust)) }
        c.drawBitmap(src, -src.width / 2f, -src.height / 2f, paint)
        c.restore()

        if (e.adjust.vignette > 0) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.shader = RadialGradient(ow / 2f, oh / 2f, hypot(ow.toFloat(), oh.toFloat()) / 2, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb((e.adjust.vignette * 1.9f).roundToInt().coerceIn(0, 255), 0, 0, 0)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, ow.toFloat(), oh.toFloat(), p)
        }
    }

    /** Drawing, then text and stickers, then the frame, in the edited picture's own coordinates. */
    private fun drawMarks(c: Canvas, e: EditState, ow: Int, oh: Int) {
        val short = min(ow, oh).toFloat()
        val radius = e.radius / 100f * short
        for (s in e.strokes) {
            if (s.points.isEmpty()) continue
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = s.colour
                style = Paint.Style.STROKE
                strokeWidth = s.size * ow
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            if (s.points.size == 1) {
                p.style = Paint.Style.FILL
                c.drawCircle(s.points[0].first * ow, s.points[0].second * oh, s.size * ow / 2, p)
            } else {
                val path = Path()
                path.moveTo(s.points[0].first * ow, s.points[0].second * oh)
                for ((x, y) in s.points.drop(1)) path.lineTo(x * ow, y * oh)
                c.drawPath(path, p)
            }
        }
        val lp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        for (l in e.layers) {
            val w = l.width * ow
            val h = w * l.stamp.height / l.stamp.width
            c.save()
            c.rotate(l.rotate, l.x * ow, l.y * oh)
            c.drawBitmap(l.stamp, null, RectF(l.x * ow - w / 2, l.y * oh - h / 2, l.x * ow + w / 2, l.y * oh + h / 2), lp)
            c.restore()
        }

        if (e.frame > 0f) {
            val f = e.frame / 100f * short
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = e.frameColour; style = Paint.Style.STROKE; strokeWidth = f * 2 }
            c.drawRoundRect(RectF(0f, 0f, ow.toFloat(), oh.toFloat()), radius, radius, p)
        }
    }
}
