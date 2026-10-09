package com.aamus.powerpdf.engine

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * The page detector on real camera frames (the owner's phone, recorded with their consent; never
 * committed). Runs only when the folder in POWERPDF_FRAMES exists, like the desktop's external
 * tests: each frame is drawn with the outline found (green: confident page, orange: found but not
 * confident) into POWERPDF_FRAMES_OUT, and labels.txt (frame → page / none) gives the score.
 */
class PageEdgesFramesTest {

    @Test
    fun recordedFrames() {
        val dir = System.getenv("POWERPDF_FRAMES")?.let { File(it) }
        assumeTrue("POWERPDF_FRAMES not set", dir != null && dir.isDirectory)
        dir!!
        val out = File(System.getenv("POWERPDF_FRAMES_OUT") ?: "${dir.path}-out").apply { mkdirs() }
        val labels = File(dir, "labels.txt").takeIf { it.exists() }?.readLines()
            ?.mapNotNull { l -> l.trim().split(Regex("\\s+")).takeIf { it.size >= 2 }?.let { it[0] to it[1] } }?.toMap() ?: emptyMap()
        var hit = 0
        var miss = 0
        var falsePage = 0
        var rightNone = 0
        var time = 0L
        var autoHit = 0
        var autoFalse = 0
        val lines = StringBuilder()
        for (f in dir.listFiles { x -> x.name.endsWith(".png") }!!.sortedBy { it.name }) {
            val img = ImageIO.read(f)
            val w = img.width
            val h = img.height
            val px = IntArray(w * h)
            img.getRGB(0, 0, w, h, px, 0, w)
            val t0 = System.nanoTime()
            // POWERPDF_DETECTOR=old: the light-region search; otherwise the edge-and-line search.
            val old = System.getenv("POWERPDF_DETECTOR") == "old"
            val d: Any? = if (old) PageEdges.detect(px, w, h) else PageQuad.detect(px, w, h)
            val corners = (d as? PageEdges.Detection)?.corners ?: (d as? PageQuad.Found)?.corners
            time += System.nanoTime() - t0
            val name = f.nameWithoutExtension
            val page = (d as? PageEdges.Detection)?.confident == true || (d as? PageQuad.Found)?.confident == true
            val auto = (d as? PageQuad.Found)?.autoCapture == true
            if (labels[name] == "page" && auto) autoHit++
            if (labels[name] == "none" && auto) autoFalse++
            when (labels[name]) {
                "page" -> if (page) hit++ else miss++
                "none" -> if (page) falsePage++ else rightNone++
            }
            lines.append("$name ${labels[name] ?: "?"} ${d ?: "nothing"}\n")
            // The frame, scaled up, with what was found.
            val k = 2
            val draw = BufferedImage(w * k, h * k, BufferedImage.TYPE_INT_RGB)
            val g = draw.createGraphics()
            g.drawImage(img, 0, 0, w * k, h * k, null)
            if (corners != null) {
                g.color = if (page) Color(0, 200, 0) else Color(255, 140, 0)
                g.stroke = BasicStroke(3f)
                val xs = IntArray(4) { (corners[it * 2] * w * k).toInt() }
                val ys = IntArray(4) { (corners[it * 2 + 1] * h * k).toInt() }
                g.drawPolygon(xs, ys, 4)
            }
            g.color = when (labels[name]) {
                "page" -> if (page) Color(0, 160, 0) else Color(220, 0, 0)
                "none" -> if (page) Color(220, 0, 0) else Color(0, 160, 0)
                else -> Color.GRAY
            }
            g.fillRect(0, 0, w * k, 18)
            g.color = Color.WHITE
            g.drawString("$name ${labels[name] ?: "?"}", 4, 14)
            g.dispose()
            ImageIO.write(draw, "png", File(out, "$name.png"))
        }
        val n = dir.listFiles { x -> x.name.endsWith(".png") }!!.size
        val summary = "frames $n: pages found $hit of ${hit + miss}, false pages $falsePage of ${falsePage + rightNone}; auto capture: pages $autoHit, false $autoFalse, ${time / 1_000_000 / maxOf(1, n)} ms a frame (JVM)"
        File(out, "result.txt").writeText("$summary\n$lines")
        println(summary)
    }
}
