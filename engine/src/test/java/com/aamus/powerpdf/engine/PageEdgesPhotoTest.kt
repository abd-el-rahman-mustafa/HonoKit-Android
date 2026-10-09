package com.aamus.powerpdf.engine

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Page detection on real photos kept outside the repository (never committed). Skipped when the
 * folder is absent. Writes each photo with the found outline drawn on it, for a look by eye.
 * Folder: the PAGE_EDGES_PHOTOS environment variable, or tmp/scan in the project folder.
 */
class PageEdgesPhotoTest {
    @Test
    fun photos() {
        val dir = File(System.getenv("PAGE_EDGES_PHOTOS") ?: "../tmp/scan")
        val photos = dir.listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png") && !f.name.startsWith("found-") }?.sorted().orEmpty()
        assumeTrue(photos.isNotEmpty())
        for (f in photos) {
            val full = ImageIO.read(f) ?: continue
            // As on the phone: the longest side 320 pixels.
            val k = 320.0 / maxOf(full.width, full.height)
            val w = (full.width * k).toInt()
            val h = (full.height * k).toInt()
            val small = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            small.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                drawImage(full, 0, 0, w, h, null)
                dispose()
            }
            val px = small.getRGB(0, 0, w, h, null, 0, w)
            val t = System.nanoTime()
            val found = PageEdges.find(px, w, h)
            val ms = (System.nanoTime() - t) / 1_000_000
            println("${f.name}: ${found?.joinToString { "%.3f".format(it) } ?: "no page"} ($ms ms)")
            val out = BufferedImage(full.width, full.height, BufferedImage.TYPE_INT_RGB)
            out.createGraphics().apply {
                drawImage(full, 0, 0, null)
                if (found != null) {
                    color = Color(0, 200, 255)
                    stroke = BasicStroke(maxOf(3f, full.width / 200f))
                    val xs = IntArray(4) { (found[it * 2] * full.width).toInt() }
                    val ys = IntArray(4) { (found[it * 2 + 1] * full.height).toInt() }
                    drawPolygon(xs, ys, 4)
                }
                dispose()
            }
            ImageIO.write(out, "png", File(dir, "found-${f.nameWithoutExtension}.png"))
        }
    }
}
