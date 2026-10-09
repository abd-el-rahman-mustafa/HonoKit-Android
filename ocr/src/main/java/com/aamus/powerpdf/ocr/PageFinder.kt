package com.aamus.powerpdf.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min

/**
 * The page's four corners from a small trained model, for the scanner's camera when the edge
 * search (engine PageQuad) is not sure: DocAligner's LCNet-100 heatmap model (DocsaidLab,
 * Apache-2.0; decisions §31). On the owner's frames it found the pages lying on a newspaper stack,
 * which have hardly any edge. Not thread-safe: one finder per thread. [close] releases the weights.
 */
class PageFinder private constructor(private val env: OrtEnvironment, private val session: OrtSession) : AutoCloseable {

    /** Corners as fractions of the frame (top-left, top-right, bottom-right, bottom-left), and each corner's peak. */
    class Corners(val corners: FloatArray, val peaks: FloatArray) {
        val confidence: Float get() = peaks.minOrNull() ?: 0f
    }

    /** The corners in [argb] ([w] × [h]), or null when a corner is not found at all. */
    fun find(argb: IntArray, w: Int, h: Int): Corners? {
        val planes = PageFinderLogic.planes(argb, w, h)
        val input = OnnxTensor.createTensor(env, FloatBuffer.wrap(planes), longArrayOf(1, 3, PageFinderLogic.SIZE.toLong(), PageFinderLogic.SIZE.toLong()))
        try {
            session.run(mapOf(session.inputNames.first() to input)).use { result ->
                val out = result[0] as OnnxTensor
                val shape = out.info.shape
                val buf = out.floatBuffer
                val data = FloatArray(buf.remaining())
                buf.get(data)
                return PageFinderLogic.corners(data, shape[1].toInt(), shape[2].toInt(), shape[3].toInt(), w, h)
            }
        } finally {
            input.close()
        }
    }

    override fun close() = session.close()

    companion object {
        val MODEL = Models.File("docaligner_lcnet100_h_e_bifpn_256_fp32.onnx", "f4117b786e3a18470f3865c93f3c2bd69d9b998edd60f385574a5c665e79594e")

        /**
         * Opens the model, or null on a 32-bit phone (no onnxruntime there; decisions §29) or when
         * the file is damaged: the scanner then uses the edge search alone.
         */
        fun open(context: Context, threads: Int = 2): PageFinder? = runCatching {
            val env = OrtEnvironment.getEnvironment()
            val bytes = context.applicationContext.assets.open("ocr/${MODEL.name}").use { it.readBytes() }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            if (digest != MODEL.sha256) throw OcrException("The page-finding model is damaged.")
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads)
                setInterOpNumThreads(1)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            PageFinder(env, env.createSession(bytes, options))
        }.onFailure { android.util.Log.w("PowerPDF", "page finder: not available", it) }.getOrNull()
    }
}

/**
 * The plain arithmetic around the page model (JVM-tested): the frame centred on a grey square of its
 * long side (keeping its shape found 75 of 113 of the owner's pages, stretching it 45), RGB scaled
 * to 0..1 as DocAligner's own code does; and each corner's heatmap read as the centre of its largest
 * spot above 0.3 (their post-processing), mapped back to the frame.
 */
object PageFinderLogic {
    const val SIZE = 256
    private const val PAD = 0.5f
    private const val SPOT = 0.3f

    fun planes(argb: IntArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(3 * SIZE * SIZE) { PAD }
        val side = max(w, h).toFloat()
        val ox = (w - side) / 2f
        val oy = (h - side) / 2f
        val plane = SIZE * SIZE
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val gx = (x + 0.5f) * side / SIZE - 0.5f + ox
            val gy = (y + 0.5f) * side / SIZE - 0.5f + oy
            if (gx < -0.5f || gy < -0.5f || gx > w - 0.5f || gy > h - 0.5f) continue
            val fx = gx.coerceIn(0f, (w - 1).toFloat())
            val fy = gy.coerceIn(0f, (h - 1).toFloat())
            val x0 = fx.toInt()
            val y0 = fy.toInt()
            val x1 = min(w - 1, x0 + 1)
            val y1 = min(h - 1, y0 + 1)
            val dx = fx - x0
            val dy = fy - y0
            val p00 = argb[y0 * w + x0]
            val p10 = argb[y0 * w + x1]
            val p01 = argb[y1 * w + x0]
            val p11 = argb[y1 * w + x1]
            for (c in 0 until 3) {
                val shift = 16 - 8 * c
                val a = ((p00 shr shift) and 255) * (1 - dx) + ((p10 shr shift) and 255) * dx
                val b = ((p01 shr shift) and 255) * (1 - dx) + ((p11 shr shift) and 255) * dx
                out[c * plane + y * SIZE + x] = (a * (1 - dy) + b * dy) / 255f
            }
        }
        return out
    }

    /** [heat] holds [k] maps of [hh] × [ww]; corners as fractions of the [w] × [h] frame, or null. */
    fun corners(heat: FloatArray, k: Int, hh: Int, ww: Int, w: Int, h: Int): PageFinder.Corners? {
        if (k < 4) return null
        val side = max(w, h).toFloat()
        val ox = (w - side) / 2f
        val oy = (h - side) / 2f
        val out = FloatArray(8)
        val peaks = FloatArray(4)
        val seen = BooleanArray(hh * ww)
        val stack = IntArray(hh * ww)
        for (c in 0 until 4) {
            val off = c * hh * ww
            java.util.Arrays.fill(seen, false)
            var peak = 0f
            for (i in 0 until hh * ww) peak = max(peak, heat[off + i])
            peaks[c] = peak
            var bestN = 0
            var bx = 0.0
            var by = 0.0
            for (s in 0 until hh * ww) {
                if (seen[s] || heat[off + s] < SPOT) continue
                var top = 0
                stack[top++] = s
                seen[s] = true
                var n = 0
                var sx = 0.0
                var sy = 0.0
                while (top > 0) {
                    val i = stack[--top]
                    val x = i % ww
                    val y = i / ww
                    n++; sx += x; sy += y
                    for (j in intArrayOf(i - 1, i + 1, i - ww, i + ww)) {
                        if (j < 0 || j >= hh * ww || seen[j] || heat[off + j] < SPOT) continue
                        if (kotlin.math.abs(j % ww - x) > 1) continue
                        seen[j] = true
                        stack[top++] = j
                    }
                }
                if (n > bestN) {
                    bestN = n
                    bx = (sx / n + 0.5) / ww
                    by = (sy / n + 0.5) / hh
                }
            }
            if (bestN == 0) return null
            out[c * 2] = ((bx * side + ox) / w).toFloat()
            out[c * 2 + 1] = ((by * side + oy) / h).toFloat()
        }
        return PageFinder.Corners(out, peaks)
    }
}
