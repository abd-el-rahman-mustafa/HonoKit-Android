package com.aamus.powerpdf.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The image tools' offline AI on the phone (decisions §34): the desktop's four small models
 * (desktop decisions §70), same pre- and post-processing, on onnxruntime (64-bit phones, §29).
 *   realesr-general-x4v3  upscale ×4 (×2 = ×4 then halved), in tiles
 *   u2netp                background mask
 *   YuNet 2026may         faces, searched at several sizes
 *   YOLOv9-t 384 plates   number plates
 */
class ImageAi private constructor(private val env: OrtEnvironment, private val context: Context, private val threads: Int) : AutoCloseable {
    private val sessions = HashMap<String, OrtSession>()

    private fun session(name: String): OrtSession = sessions.getOrPut(name) {
        val bytes = context.assets.open("ai/$name").use { it.readBytes() }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        env.createSession(bytes, options)
    }

    private fun run(model: String, data: FloatArray, dims: LongArray): Map<String, FloatArray> {
        val s = session(model)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), dims).use { input ->
            s.run(mapOf(s.inputNames.first() to input)).use { result ->
                return result.associate { (name, value) ->
                    val t = value as OnnxTensor
                    val buf = t.floatBuffer
                    name to FloatArray(buf.remaining()).also { buf.get(it) }
                }
            }
        }
    }

    /** RGBA pixels → planar float RGB (or BGR), multiplied by [scale]. */
    private fun planar(px: IntArray, w: Int, h: Int, scale: Float, bgr: Boolean = false): FloatArray {
        val out = FloatArray(3 * w * h)
        val plane = w * h
        for (i in 0 until plane) {
            val p = px[i]
            val r = ((p shr 16) and 255) * scale
            val g = ((p shr 8) and 255) * scale
            val b = (p and 255) * scale
            out[i] = if (bgr) b else r
            out[plane + i] = g
            out[2 * plane + i] = if (bgr) r else b
        }
        return out
    }

    // --- upscale ---------------------------------------------------------------------------

    /** ×2 or ×4 in 128 px tiles with 12 px of context (the seams then match); transparency enlarged smoothly. */
    fun upscale(src: Bitmap, factor: Int, progress: (Float) -> Unit = {}): Bitmap {
        val tile = 128
        val pad = 12
        val s = 4
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w * s, h * s, Bitmap.Config.ARGB_8888)
        val cols = (w + tile - 1) / tile
        val rows = (h + tile - 1) / tile
        var done = 0
        for (ty in 0 until rows) for (tx in 0 until cols) {
            val x0 = tx * tile
            val y0 = ty * tile
            val x1 = min(w, x0 + tile)
            val y1 = min(h, y0 + tile)
            val px0 = max(0, x0 - pad)
            val py0 = max(0, y0 - pad)
            val pw = min(w, x1 + pad) - px0
            val ph = min(h, y1 + pad) - py0
            val px = IntArray(pw * ph)
            src.getPixels(px, 0, pw, px0, py0, pw, ph)
            val t = run(MODEL_UPSCALE, planar(px, pw, ph, 1f / 255), longArrayOf(1, 3, ph.toLong(), pw.toLong())).values.first()
            val plane = pw * s * ph * s
            val cw = (x1 - x0) * s
            val ch = (y1 - y0) * s
            val row = IntArray(cw)
            for (yy in 0 until ch) {
                val sy = (y0 - py0) * s + yy
                for (xx in 0 until cw) {
                    val si = sy * pw * s + (x0 - px0) * s + xx
                    val r = (t[si] * 255).roundToInt().coerceIn(0, 255)
                    val g = (t[plane + si] * 255).roundToInt().coerceIn(0, 255)
                    val b = (t[2 * plane + si] * 255).roundToInt().coerceIn(0, 255)
                    row[xx] = Color.argb(255, r, g, b)
                }
                out.setPixels(row, 0, cw, x0 * s, y0 * s + yy, cw, 1)
            }
            done++
            progress(done.toFloat() / (rows * cols))
        }
        if (src.hasAlpha()) {
            // The original transparency, enlarged smoothly and copied in row by row (a mask drawn
            // with DST_IN left the corners black on the emulator).
            val big = Bitmap.createScaledBitmap(src, w * s, h * s, true)
            val a = IntArray(w * s)
            val o = IntArray(w * s)
            for (y in 0 until h * s) {
                big.getPixels(a, 0, w * s, 0, y, w * s, 1)
                out.getPixels(o, 0, w * s, 0, y, w * s, 1)
                for (x in o.indices) o[x] = (o[x] and 0x00ffffff) or (a[x] and 0xff000000.toInt())
                out.setPixels(o, 0, w * s, 0, y, w * s, 1)
            }
        }
        return if (factor == 4) out else Bitmap.createScaledBitmap(out, w * 2, h * 2, true)
    }

    // --- background ------------------------------------------------------------------------

    /** The subject kept, the background transparent (or [background] when given). */
    fun removeBackground(src: Bitmap, background: Int? = null): Bitmap {
        val n = 320
        val small = Bitmap.createScaledBitmap(src, n, n, true)
        val px = IntArray(n * n)
        small.getPixels(px, 0, n, 0, 0, n, n)
        var mx = 1
        for (p in px) mx = max(mx, max((p shr 16) and 255, max((p shr 8) and 255, p and 255)))
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        val input = FloatArray(3 * n * n)
        for (i in 0 until n * n) {
            val p = px[i]
            val ch = intArrayOf((p shr 16) and 255, (p shr 8) and 255, p and 255)
            for (c in 0..2) input[c * n * n + i] = (ch[c].toFloat() / mx - mean[c]) / std[c]
        }
        val s = session(MODEL_BACKGROUND)
        val pred = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, n.toLong(), n.toLong())).use { t ->
            s.run(mapOf(s.inputNames.first() to t)).use { r -> val b = (r.get(s.outputNames.first()).get() as OnnxTensor).floatBuffer; FloatArray(b.remaining()).also { b.get(it) } }
        }
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (v in pred) { lo = min(lo, v); hi = max(hi, v) }
        val mask = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val mp = IntArray(n * n) { i ->
            // A gentle curve, as on the desktop: haze becomes background, near-certain subject solid.
            val v = ((pred[i] - lo) / max(1e-6f, hi - lo) * 255 - 40) * 255 / 175
            Color.argb(v.roundToInt().coerceIn(0, 255), 0, 0, 0)
        }
        mask.setPixels(mp, 0, n, 0, 0, n, n)
        val big = Bitmap.createScaledBitmap(mask, src.width, src.height, true)
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(src, 0f, 0f, null)
        c.drawBitmap(big, 0f, 0f, Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN) })
        if (background == null) return out
        val flat = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply { drawColor(background); drawBitmap(out, 0f, 0f, null) }
        return flat
    }

    // --- faces and plates ------------------------------------------------------------------

    /** A find, in fractions of the picture. */
    data class Found(val x: Float, val y: Float, val w: Float, val h: Float, val face: Boolean, val score: Float)

    private fun iou(a: Found, b: Found): Float {
        val ix = max(0f, min(a.x + a.w, b.x + b.w) - max(a.x, b.x))
        val iy = max(0f, min(a.y + a.h, b.y + b.h) - max(a.y, b.y))
        val inter = ix * iy
        return inter / max(1e-9f, a.w * a.h + b.w * b.h - inter)
    }

    /** Faces with YuNet at several sizes (it finds ~10–300 px faces), merged. */
    fun faces(src: Bitmap, threshold: Float = 0.6f): List<Found> {
        val longest = max(src.width, src.height)
        val sizes = listOf(320, 640, 1280).filter { it <= max(320, longest) }.let { if (longest <= 1280) it + longest else it }.distinct()
        val found = ArrayList<Found>()
        for (size in sizes) {
            val scale = size.toFloat() / longest
            val w = max(32, (src.width * scale).roundToInt())
            val h = max(32, (src.height * scale).roundToInt())
            val pw = (w + 31) / 32 * 32
            val ph = (h + 31) / 32 * 32
            val canvas = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
            Canvas(canvas).apply { drawColor(Color.BLACK); drawBitmap(src, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG)) }
            val px = IntArray(pw * ph)
            canvas.getPixels(px, 0, pw, 0, 0, pw, ph)
            val out = run(MODEL_FACES, planar(px, pw, ph, 1f, bgr = true), longArrayOf(1, 3, ph.toLong(), pw.toLong()))
            for (stride in intArrayOf(8, 16, 32)) {
                val cls = out["cls_$stride"]!!
                val obj = out["obj_$stride"]!!
                val box = out["bbox_$stride"]!!
                val cols = pw / stride
                for (i in cls.indices) {
                    val score = sqrt(cls[i].coerceIn(0f, 1f) * obj[i].coerceIn(0f, 1f))
                    if (score < threshold) continue
                    val cx = (i % cols + box[i * 4]) * stride
                    val cy = (i / cols + box[i * 4 + 1]) * stride
                    val bw = exp(box[i * 4 + 2]) * stride
                    val bh = exp(box[i * 4 + 3]) * stride
                    found.add(Found((cx - bw / 2) / w, (cy - bh / 2) / h, bw / w, bh / h, true, score))
                }
            }
        }
        val kept = ArrayList<Found>()
        for (f in found.sortedByDescending { it.score }) if (kept.none { iou(it, f) > 0.3f }) kept.add(f)
        return kept
    }

    /** Number plates with YOLOv9-t (its graph already merges overlapping finds). */
    fun plates(src: Bitmap, threshold: Float = 0.4f): List<Found> {
        val n = 384
        val scale = n.toFloat() / max(src.width, src.height)
        val w = (src.width * scale).roundToInt()
        val h = (src.height * scale).roundToInt()
        val left = (n - w) / 2
        val top = (n - h) / 2
        val canvas = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        Canvas(canvas).apply { drawColor(Color.rgb(114, 114, 114)); drawBitmap(src, null, RectF(left.toFloat(), top.toFloat(), (left + w).toFloat(), (top + h).toFloat()), Paint(Paint.FILTER_BITMAP_FLAG)) }
        val px = IntArray(n * n)
        canvas.getPixels(px, 0, n, 0, 0, n, n)
        val rows = run(MODEL_PLATES, planar(px, n, n, 1f / 255), longArrayOf(1, 3, n.toLong(), n.toLong())).values.first()
        val out = ArrayList<Found>()
        // Each row: batch, x1, y1, x2, y2, class, score — in the 384 px letterboxed picture.
        var i = 0
        while (i + 6 < rows.size) {
            val score = rows[i + 6]
            if (score >= threshold) {
                val x1 = max(0f, (rows[i + 1] - left) / w)
                val y1 = max(0f, (rows[i + 2] - top) / h)
                val x2 = min(1f, (rows[i + 3] - left) / w)
                val y2 = min(1f, (rows[i + 4] - top) / h)
                if (x2 > x1 && y2 > y1) out.add(Found(x1, y1, x2 - x1, y2 - y1, false, score))
            }
            i += 7
        }
        return out
    }

    override fun close() {
        sessions.values.forEach { it.close() }
        sessions.clear()
    }

    companion object {
        const val MODEL_UPSCALE = "realesr-general-x4v3.onnx"
        const val MODEL_BACKGROUND = "u2netp.onnx"
        const val MODEL_FACES = "face_detection_yunet_2026may.onnx"
        const val MODEL_PLATES = "yolo-v9-t-384-license-plates-end2end.onnx"

        /** Opens the models' runtime, or throws [OcrException.Unsupported] on a 32-bit phone (decisions §29). */
        fun open(context: Context, threads: Int = 4): ImageAi {
            val env = try {
                OrtEnvironment.getEnvironment()
            } catch (e: UnsatisfiedLinkError) {
                throw OcrException.Unsupported()
            }
            return ImageAi(env, context.applicationContext, threads)
        }
    }
}
