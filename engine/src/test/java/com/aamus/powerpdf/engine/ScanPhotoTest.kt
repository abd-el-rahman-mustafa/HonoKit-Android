package com.aamus.powerpdf.engine

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * A whole scan of one real photo, as the app makes it (find the page on a 320 px copy, straighten,
 * clean): POWERPDF_PHOTO (a camera JPEG, never committed), POWERPDF_FOCAL35 (its lens, 35 mm
 * equivalent, optional), the result into POWERPDF_PHOTO_OUT. For tuning against other scanners.
 */
class ScanPhotoTest {

    @Test
    fun scanOnePhoto() {
        val file = System.getenv("POWERPDF_PHOTO")?.let { File(it) }
        assumeTrue("POWERPDF_PHOTO not set", file != null && file.isFile)
        val img = ImageIO.read(file!!)
        val w = img.width
        val h = img.height
        val k = 320.0 / maxOf(w, h)
        val sw = (w * k).roundToInt()
        val sh = (h * k).roundToInt()
        val small = BufferedImage(sw, sh, BufferedImage.TYPE_INT_RGB)
        small.createGraphics().apply { drawImage(img.getScaledInstance(sw, sh, Image.SCALE_AREA_AVERAGING), 0, 0, null); dispose() }
        val spx = IntArray(sw * sh)
        small.getRGB(0, 0, sw, sh, spx, 0, sw)
        val found = PageQuad.detect(spx, sw, sh)
        println("found $found")
        val c = found!!.corners
        val focal = System.getenv("POWERPDF_FOCAL35")?.toDoubleOrNull()?.let { it / PageShape.FILM_DIAGONAL_MM }
        val sides = PageShape.sideAspect(c, w, h)
        val guess = PageShape.aspect(c, w, h)
        val real = PageShape.aspect(c, w, h, focal)
        println("aspect: sides %.4f, corners only %.4f, with lens %.4f (A4 %.4f)".format(sides, guess, real, 210 / 297.0))
        // Straighten: the larger of the measured sizes, in the real shape.
        fun len(a: Int, b: Int) = hypot((c[a * 2] - c[b * 2]) * w.toDouble(), (c[a * 2 + 1] - c[b * 2 + 1]) * h.toDouble())
        val across = maxOf(len(0, 1), len(3, 2))
        val down = maxOf(len(0, 3), len(1, 2))
        var ow = maxOf(across, down * real)
        var oh = ow / real
        val cap = 2400.0 / maxOf(ow, oh)
        if (cap < 1) { ow *= cap; oh *= cap }
        val outW = ow.roundToInt()
        val outH = oh.roundToInt()
        val src = IntArray(w * h)
        img.getRGB(0, 0, w, h, src, 0, w)
        val px = warp(src, w, h, c, outW, outH)
        val sharpen = System.getenv("POWERPDF_SHARPEN")?.toFloatOrNull() ?: 0f
        ScanFilters.apply(px, outW, outH, ScanLook.valueOf(System.getenv("POWERPDF_LOOK") ?: "Auto"), sharpen = sharpen)
        val out = BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB)
        out.setRGB(0, 0, outW, outH, px, 0, outW)
        val dest = File(System.getenv("POWERPDF_PHOTO_OUT") ?: "${file.path}-scan.png")
        ImageIO.write(out, "png", dest)
        println("wrote $dest ${outW}x$outH, snaps to ${ScanFilters.snapPaper(outW, outH)}")
    }

    /** Output pixel → source pixel through the homography that maps the unit square to the corners. */
    private fun warp(src: IntArray, w: Int, h: Int, c: FloatArray, ow: Int, oh: Int): IntArray {
        val x = DoubleArray(4) { c[it * 2] * w.toDouble() }
        val y = DoubleArray(4) { c[it * 2 + 1] * h.toDouble() }
        // Square (0,0),(1,0),(1,1),(0,1) → TL, TR, BR, BL (Heckbert).
        val dx1 = x[1] - x[2]; val dx2 = x[3] - x[2]; val sx = x[0] - x[1] + x[2] - x[3]
        val dy1 = y[1] - y[2]; val dy2 = y[3] - y[2]; val sy = y[0] - y[1] + y[2] - y[3]
        val den = dx1 * dy2 - dx2 * dy1
        val g = (sx * dy2 - dx2 * sy) / den
        val hh = (dx1 * sy - sx * dy1) / den
        val a = x[1] - x[0] + g * x[1]; val b = x[3] - x[0] + hh * x[3]
        val d = y[1] - y[0] + g * y[1]; val e = y[3] - y[0] + hh * y[3]
        val out = IntArray(ow * oh)
        for (j in 0 until oh) for (i in 0 until ow) {
            val u = (i + 0.5) / ow
            val v = (j + 0.5) / oh
            val z = g * u + hh * v + 1
            val px = (a * u + b * v + x[0]) / z - 0.5
            val py = (d * u + e * v + y[0]) / z - 0.5
            val x0 = floor(px).toInt().coerceIn(0, w - 2)
            val y0 = floor(py).toInt().coerceIn(0, h - 2)
            val fx = (px - x0).coerceIn(0.0, 1.0)
            val fy = (py - y0).coerceIn(0.0, 1.0)
            var r = 0.0; var gg = 0.0; var bb = 0.0
            for ((p, wt) in listOf(src[y0 * w + x0] to (1 - fx) * (1 - fy), src[y0 * w + x0 + 1] to fx * (1 - fy), src[(y0 + 1) * w + x0] to (1 - fx) * fy, src[(y0 + 1) * w + x0 + 1] to fx * fy)) {
                r += ((p shr 16) and 255) * wt; gg += ((p shr 8) and 255) * wt; bb += (p and 255) * wt
            }
            out[j * ow + i] = (0xFF shl 24) or (r.roundToInt() shl 16) or (gg.roundToInt() shl 8) or bb.roundToInt()
        }
        return out
    }
}
