package com.aamus.powerpdf.engine

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Finds a sheet of paper in a photo, for the scanner's automatic crop (docs/decisions.md §15): the
 * page's four corners, or null when no page clearly stands out (the picture is then kept whole).
 *
 * Paper is light and colourless against most backgrounds (a desk, a table, a bag). Each pixel is
 * scored by lightness minus colourfulness, blurred, and split in two by Otsu's threshold. The light
 * region nearest the middle of the photo is the page; its convex hull fills the holes the writing
 * leaves, and the page's corners are the four hull points that enclose the largest area. Plain
 * Kotlin on a small picture (about 256 pixels a side): a fraction of a second, no library.
 */
object PageEdges {

    /**
     * [argb] holds the pixels of a [w]×[h] picture, row by row. Returns x,y pairs as fractions of the
     * picture: top-left, top-right, bottom-right, bottom-left.
     */
    fun find(argb: IntArray, w: Int, h: Int): FloatArray? = detect(argb, w, h)?.corners

    /**
     * What the live camera needs (owner, 2026-10-02: auto capture fired with no paper in view —
     * a wall, the floor or a lamp is "a light region" too): the corners, and how much the region
     * looks like a sheet of paper. [confident] only when it fills its outline and its sides are
     * real edges; only then does the camera take the picture by itself. A page held close often
     * touches the frame (the phone, 2026-10-02): a side along the frame cannot be measured and is
     * not held against it, and one weak side (a shadow, a light table) is allowed.
     */
    class Detection(
        /** The corners as [find] gives them. */
        val corners: FloatArray,
        /** The share of the outline the light region fills (paper: nearly all of it). */
        val fill: Float,
        /** The share of the light region outside the outline (a glow or a blob spills over). */
        val spill: Float,
        /** How many sides have a clear light-to-dark step across them. */
        val edgedSides: Int,
        /** How many sides could be measured (not lying along the frame's edge). */
        val measuredSides: Int,
        /** A corner at the frame's edge: the region runs off the picture. */
        val touchesBorder: Boolean,
        /** The outline's area as a share of the picture. */
        val area: Float,
        /** Each side's typical step (inside minus outside, of 255); -1 when not measured. */
        val steps: IntArray = IntArray(0),
    ) {
        val confident: Boolean
            get() = fill >= MIN_FILL && spill <= MAX_SPILL && area in MIN_AREA..MAX_AREA &&
                measuredSides >= 2 && edgedSides >= 2 && edgedSides >= measuredSides - 1

        override fun toString() = String.format(
            java.util.Locale.US, "fill %.2f spill %.2f sides %d/%d %s border %b area %.2f → %s",
            fill, spill, edgedSides, measuredSides, steps.joinToString(",", "[", "]"), touchesBorder, area, if (confident) "page" else "no",
        )
    }

    const val MIN_FILL = 0.88f
    const val MAX_SPILL = 0.12f
    const val MIN_AREA = 0.12f
    const val MAX_AREA = 0.92f

    /** [find] with the paper check ([Detection]). */
    fun detect(argb: IntArray, w: Int, h: Int): Detection? {
        if (w < 16 || h < 16 || argb.size < w * h) return null
        val n = w * h
        // Light and colourless scores high: white paper yes, a beige table less, a wooden one no.
        val score = IntArray(n)
        val red = IntArray(n)
        val green = IntArray(n)
        val blue = IntArray(n)
        for (i in 0 until n) {
            val c = argb[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            red[i] = r; green[i] = g; blue[i] = b
            val lum = (299 * r + 587 * g + 114 * b) / 1000
            val chroma = max(r, max(g, b)) - min(r, min(g, b))
            score[i] = (lum - chroma * 6 / 10).coerceIn(0, 255)
        }
        blur(score, w, h, 2)
        blur(score, w, h, 2)
        for (v in listOf(red, green, blue)) { blur(v, w, h, 1); blur(v, w, h, 1) }
        val edge = edges(red, green, blue, w, h)

        val t = otsu(score) ?: return null
        val light = BooleanArray(n) { score[it] > t }
        // With the colour-edge stop, and without it: a busy background (a patterned cloth) is full
        // of edges that keep the flood out, and then the page would swallow some of it. The stop
        // only ever adds thin stripes to the page, so a much larger page means it misled.
        val guarded = attempt(light, edge, EDGE, w, h)
        val plain = attempt(light, edge, Int.MAX_VALUE, w, h)
        val chosen = when {
            guarded == null -> plain
            plain == null -> guarded
            polygonArea(guarded.quad) > polygonArea(plain.quad) * 1.12 -> plain
            else -> guarded
        } ?: return null
        val ordered = order(chosen.quad)
        val out = FloatArray(8)
        for (k in 0 until 4) {
            out[k * 2] = ((ordered[k].x + 0.5) / w).toFloat().coerceIn(0f, 1f)
            out[k * 2 + 1] = ((ordered[k].y + 0.5) / h).toFloat().coerceIn(0f, 1f)
        }
        return assess(out, ordered, chosen.region, score, w, h)
    }

    /** The paper check of [Detection] for the outline [q] (pixels, ordered) of [region]. */
    private fun assess(corners: FloatArray, q: List<P>, region: BooleanArray, score: IntArray, w: Int, h: Int): Detection {
        // Fill and spill: pixel by pixel, inside the outline or not.
        var inQuad = 0
        var regionIn = 0
        var regionOut = 0
        for (y in 0 until h) for (x in 0 until w) {
            val inside = insideQuad(x + 0.5, y + 0.5, q)
            val r = region[y * w + x]
            if (inside) {
                inQuad++
                if (r) regionIn++
            } else if (r) regionOut++
        }
        val fill = if (inQuad == 0) 0f else regionIn.toFloat() / inQuad
        val spill = if (regionIn + regionOut == 0) 1f else regionOut.toFloat() / (regionIn + regionOut)
        // Each side: points along it, the light just inside against just outside.
        val diag = hypot(w.toDouble(), h.toDouble())
        val cx = q.sumOf { it.x } / 4
        val cy = q.sumOf { it.y } / 4
        var edged = 0
        var measured = 0
        val sideSteps = IntArray(4) { -1 }
        for (k in 0 until 4) {
            val a = q[k]
            val b = q[(k + 1) % 4]
            val len = hypot(b.x - a.x, b.y - a.y)
            if (len < 1e-6) continue
            var nx = -(b.y - a.y) / len
            var ny = (b.x - a.x) / len
            // The normal points away from the middle of the outline.
            val mx = (a.x + b.x) / 2
            val my = (a.y + b.y) / 2
            if (nx * (mx - cx) + ny * (my - cy) < 0) {
                nx = -nx
                ny = -ny
            }
            val dIn = max(2.0, diag * 0.02)
            val dOut = max(3.0, diag * 0.03)
            val diffs = ArrayList<Int>()
            for (s in 1..19) {
                val t = s / 20.0
                val px = a.x + (b.x - a.x) * t
                val py = a.y + (b.y - a.y) * t
                val ix = (px - nx * dIn).toInt()
                val iy = (py - ny * dIn).toInt()
                val ox = (px + nx * dOut).toInt()
                val oy = (py + ny * dOut).toInt()
                // Along the frame's edge the outside is not in the picture: not measured.
                if (ix !in 0 until w || iy !in 0 until h || ox !in 0 until w || oy !in 0 until h) continue
                diffs.add(score[iy * w + ix] - score[oy * w + ox])
            }
            if (diffs.size >= 8) {
                measured++
                // The typical step along the side (a shadow or a pen over part of it does not decide).
                val typical = diffs.sorted()[diffs.size / 2]
                sideSteps[k] = typical
                if (typical >= STEP) edged++
            }
        }
        val margin = 0.02f
        val border = (0 until 4).any { k -> corners[k * 2] < margin || corners[k * 2] > 1 - margin || corners[k * 2 + 1] < margin || corners[k * 2 + 1] > 1 - margin }
        return Detection(corners, fill, spill, edged, measured, border, (polygonArea(q) / (w.toDouble() * h)).toFloat(), sideSteps)
    }

    /** Lighter by this much (of 255, after the blur) just inside a side than just outside: a real edge. */
    private const val STEP = 14

    private fun insideQuad(x: Double, y: Double, q: List<P>): Boolean {
        var sign = 0
        for (i in 0 until 4) {
            val a = q[i]
            val b = q[(i + 1) % 4]
            val c = (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x)
            val s = if (c > 0) 1 else if (c < 0) -1 else 0
            if (s != 0) {
                if (sign != 0 && s != sign) return false
                sign = s
            }
        }
        return true
    }

    internal data class P(val x: Double, val y: Double)

    /** A found outline and the light region it came from. */
    private class Candidate(val quad: List<P>, val region: BooleanArray)

    /** One search for the page, the background flood stopping at colour edges of [edgeLimit]. */
    private fun attempt(light: BooleanArray, edge: IntArray, edgeLimit: Int, w: Int, h: Int): Candidate? {
        val n = w * h
        // The page is what the dark background, flooded in from the photo's edges, cannot reach:
        // writing, tables and pictures inside the page are enclosed by paper and stay page.
        val page = enclosed(light, edge, edgeLimit, w, h)
        // Thin bridges (a light cable, a reflection) would join the page to the background.
        val eroded = erode(erode(page, w, h), w, h)
        val region = pickRegion(eroded, w, h) ?: return null
        if (region.size < n * 0.08) return null
        // Undo the erosion around the chosen region only, so its outline is the page's own edge.
        val restored = restore(region, page, w, h)
        val outline = boundary(restored, w, h)
        val hull = hull(outline)
        if (hull.size < 4) return null
        val quad = largestQuad(simplify(hull)) ?: return null
        val refined = straighten(quad, outline, hypot(w.toDouble(), h.toDouble()))
        val area = polygonArea(refined)
        // Nearly the whole photo: the page fills it already, or nothing stood out.
        if (area < n * 0.10 || area > n * 0.95) return null
        if (!sensible(refined)) return null
        return Candidate(refined, restored)
    }

    /** Box blur of radius [r], rows then columns. */
    private fun blur(v: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(v.size)
        for (y in 0 until h) {
            var sum = 0
            val row = y * w
            for (x in -r..r) sum += v[row + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[row + x] = sum / (2 * r + 1)
                sum += v[row + (x + r + 1).coerceAtMost(w - 1)] - v[row + (x - r).coerceAtLeast(0)]
            }
        }
        for (x in 0 until w) {
            var sum = 0
            for (y in -r..r) sum += tmp[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                v[y * w + x] = sum / (2 * r + 1)
                sum += tmp[(y + r + 1).coerceAtMost(h - 1) * w + x] - tmp[(y - r).coerceAtLeast(0) * w + x]
            }
        }
    }

    /**
     * Otsu's threshold, or null when the picture does not fall into two clear groups (its
     * separability — between-group variance over total variance — is low).
     */
    private fun otsu(v: IntArray): Int? {
        val hist = IntArray(256)
        for (x in v) hist[x]++
        val total = v.size.toDouble()
        var sumAll = 0.0
        for (i in 0..255) sumAll += i * hist[i].toDouble()
        val mean = sumAll / total
        var variance = 0.0
        for (i in 0..255) variance += hist[i] * (i - mean) * (i - mean)
        variance /= total
        if (variance < 60.0) return null
        var wB = 0.0
        var sumB = 0.0
        var best = -1.0
        var at = -1
        for (i in 0..255) {
            wB += hist[i]
            if (wB == 0.0) continue
            val wF = total - wB
            if (wF == 0.0) break
            sumB += i * hist[i].toDouble()
            val mB = sumB / wB
            val mF = (sumAll - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF) / (total * total)
            if (between > best) {
                best = between
                at = i
            }
        }
        if (at < 0 || best / variance < 0.45) return null
        return at
    }

    /** Colour change at each pixel: the largest over red, green and blue of |dx| + |dy|. */
    private fun edges(r: IntArray, g: IntArray, b: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            var m = 0
            for (v in arrayOf(r, g, b)) m = max(m, abs(v[i + 1] - v[i - 1]) + abs(v[i + w] - v[i - w]))
            out[i] = m
        }
        return out
    }

    /**
     * Everything except the background: dark pixels connected to the photo's edge. The flood does
     * not cross a strong colour edge, so a dark band printed along the page's edge (a letterhead
     * stripe) stays page; one pass of growing afterwards takes in the thin lines it stopped at in
     * the background itself (wood grain, a cable).
     */
    private fun enclosed(light: BooleanArray, edge: IntArray, edgeLimit: Int, w: Int, h: Int): BooleanArray {
        val outside = BooleanArray(light.size)
        val queue = IntArray(light.size)
        var tail = 0
        fun seed(i: Int) {
            if (!light[i] && !outside[i] && edge[i] < edgeLimit) {
                outside[i] = true
                queue[tail++] = i
            }
        }
        for (x in 0 until w) { seed(x); seed((h - 1) * w + x) }
        for (y in 0 until h) { seed(y * w); seed(y * w + w - 1) }
        var head = 0
        while (head < tail) {
            val i = queue[head++]
            val x = i % w
            val y = i / w
            if (x > 0) seed(i - 1)
            if (x < w - 1) seed(i + 1)
            if (y > 0) seed(i - w)
            if (y < h - 1) seed(i + w)
        }
        repeat(2) {
            val grown = outside.copyOf()
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                if (outside[i] || light[i]) continue
                if ((x > 0 && outside[i - 1]) || (x < w - 1 && outside[i + 1]) || (y > 0 && outside[i - w]) || (y < h - 1 && outside[i + w])) grown[i] = true
            }
            System.arraycopy(grown, 0, outside, 0, grown.size)
        }
        // A dark patch the flood could not enter is page only when paper mostly surrounds it
        // (writing, a picture, a stripe along the edge); an object lying on the table touching
        // the page (a pen) is surrounded by table.
        val seen = BooleanArray(light.size)
        val patch = IntArray(light.size)
        for (start in light.indices) {
            if (light[start] || outside[start] || seen[start]) continue
            var head = 0
            var tail = 0
            patch[tail++] = start
            seen[start] = true
            var paper = 0
            var table = 0
            while (head < tail) {
                val i = patch[head++]
                val x = i % w
                val y = i / w
                for (j in intArrayOf(if (x > 0) i - 1 else -1, if (x < w - 1) i + 1 else -1, if (y > 0) i - w else -1, if (y < h - 1) i + w else -1)) {
                    if (j < 0) continue
                    when {
                        light[j] -> paper++
                        outside[j] -> table++
                        !seen[j] -> { seen[j] = true; patch[tail++] = j }
                    }
                }
            }
            if (table > 0 && paper < (paper + table) * 0.3) for (k in 0 until tail) outside[patch[k]] = true
        }
        return BooleanArray(light.size) { !outside[it] }
    }

    /** A colour change this strong (after blurring) is an edge the background flood does not cross. */
    private const val EDGE = 28

    private fun erode(m: BooleanArray, w: Int, h: Int): BooleanArray {
        val out = BooleanArray(m.size)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            out[i] = m[i] && m[i - 1] && m[i + 1] && m[i - w] && m[i + w]
        }
        // The picture's own edge stays: a page often runs off it.
        for (x in 0 until w) {
            out[x] = m[x] && m[x + w]
            out[(h - 1) * w + x] = m[(h - 1) * w + x] && m[(h - 2) * w + x]
        }
        for (y in 0 until h) {
            out[y * w] = m[y * w] && m[y * w + 1]
            out[y * w + w - 1] = m[y * w + w - 1] && m[y * w + w - 2]
        }
        return out
    }

    /**
     * The light region that is the page: the one with the most pixels in the middle of the photo
     * (people aim at the page), or failing that the largest. Returns its pixel indices.
     */
    private fun pickRegion(m: BooleanArray, w: Int, h: Int): IntArray? {
        val label = IntArray(m.size) { -1 }
        val queue = IntArray(m.size)
        val sizes = ArrayList<Int>()
        val central = ArrayList<Int>()
        val cx0 = w / 3
        val cx1 = w - w / 3
        val cy0 = h / 3
        val cy1 = h - h / 3
        for (start in m.indices) {
            if (!m[start] || label[start] >= 0) continue
            val id = sizes.size
            var head = 0
            var tail = 0
            queue[tail++] = start
            label[start] = id
            var count = 0
            var middle = 0
            while (head < tail) {
                val i = queue[head++]
                count++
                val x = i % w
                val y = i / w
                if (x in cx0 until cx1 && y in cy0 until cy1) middle++
                if (x > 0 && m[i - 1] && label[i - 1] < 0) { label[i - 1] = id; queue[tail++] = i - 1 }
                if (x < w - 1 && m[i + 1] && label[i + 1] < 0) { label[i + 1] = id; queue[tail++] = i + 1 }
                if (y > 0 && m[i - w] && label[i - w] < 0) { label[i - w] = id; queue[tail++] = i - w }
                if (y < h - 1 && m[i + w] && label[i + w] < 0) { label[i + w] = id; queue[tail++] = i + w }
            }
            sizes.add(count)
            central.add(middle)
        }
        if (sizes.isEmpty()) return null
        val minSize = m.size * 0.08
        val byMiddle = sizes.indices.filter { sizes[it] >= minSize }.maxByOrNull { central[it] }
        val id = if (byMiddle != null && central[byMiddle] > 0) byMiddle else sizes.indices.maxBy { sizes[it] }
        val out = IntArray(sizes[id])
        var k = 0
        for (i in label.indices) if (label[i] == id) out[k++] = i
        return out
    }

    /** The region grown back by the two pixels erosion took, within the page mask. */
    private fun restore(region: IntArray, page: BooleanArray, w: Int, h: Int): BooleanArray {
        var m = BooleanArray(page.size)
        for (i in region) m[i] = true
        repeat(2) {
            val next = m.copyOf()
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                if (m[i] || !page[i]) continue
                if ((x > 0 && m[i - 1]) || (x < w - 1 && m[i + 1]) || (y > 0 && m[i - w]) || (y < h - 1 && m[i + w])) next[i] = true
            }
            m = next
        }
        return m
    }

    /** The region's outline pixels (a neighbour outside the region, or the picture's edge). */
    private fun boundary(inRegion: BooleanArray, w: Int, h: Int): List<P> {
        val out = ArrayList<P>()
        for (i in inRegion.indices) {
            if (!inRegion[i]) continue
            val x = i % w
            val y = i / w
            val edge = x == 0 || y == 0 || x == w - 1 || y == h - 1 ||
                !inRegion[i - 1] || !inRegion[i + 1] || !inRegion[i - w] || !inRegion[i + w]
            if (edge) out.add(P(x.toDouble(), y.toDouble()))
        }
        return out
    }

    private fun cross(o: P, a: P, b: P) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

    /** Convex hull, Andrew's monotone chain. */
    internal fun hull(points: List<P>): List<P> {
        if (points.size < 3) return points
        val pts = points.sortedWith(compareBy<P> { it.x }.thenBy { it.y })
        val lower = ArrayList<P>()
        for (p in pts) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0) lower.removeAt(lower.size - 1)
            lower.add(p)
        }
        val upper = ArrayList<P>()
        for (p in pts.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0) upper.removeAt(upper.size - 1)
            upper.add(p)
        }
        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)
        return lower + upper
    }

    /** Drops hull points that barely bend the outline, and caps their number (the search is cubic). */
    private fun simplify(hull: List<P>): List<P> {
        var pts = hull
        val kept = ArrayList<P>()
        for (i in pts.indices) {
            val a = pts[(i - 1 + pts.size) % pts.size]
            val b = pts[i]
            val c = pts[(i + 1) % pts.size]
            val bend = abs(cross(a, b, c)) / (hypot(b.x - a.x, b.y - a.y) * hypot(c.x - b.x, c.y - b.y)).coerceAtLeast(1e-9)
            if (bend > 0.02) kept.add(b)
        }
        pts = if (kept.size >= 4) kept else pts
        if (pts.size > 160) {
            val step = pts.size / 160.0
            pts = (0 until 160).map { pts[(it * step).toInt()] }
        }
        return pts
    }

    private fun triangle(a: P, b: P, c: P) = abs(cross(a, b, c)) / 2

    /** A straight line through [p] along the unit direction ([dx], [dy]). */
    private class Line(val p: P, val dx: Double, val dy: Double) {
        fun distance(q: P) = abs((q.x - p.x) * dy - (q.y - p.y) * dx)
    }

    /** The line that best fits [pts] (least squares across the line: the points' main axis). */
    private fun fit(pts: List<P>): Line {
        val mx = pts.sumOf { it.x } / pts.size
        val my = pts.sumOf { it.y } / pts.size
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (q in pts) {
            sxx += (q.x - mx) * (q.x - mx); syy += (q.y - my) * (q.y - my); sxy += (q.x - mx) * (q.y - my)
        }
        val angle = 0.5 * kotlin.math.atan2(2 * sxy, sxx - syy)
        return Line(P(mx, my), kotlin.math.cos(angle), kotlin.math.sin(angle))
    }

    private fun intersect(a: Line, b: Line): P? {
        val det = a.dx * b.dy - a.dy * b.dx
        if (abs(det) < 0.05) return null
        val t = ((b.p.x - a.p.x) * b.dy - (b.p.y - a.p.y) * b.dx) / det
        return P(a.p.x + t * a.dx, a.p.y + t * a.dy)
    }

    /**
     * Corners where the page's sides meet: a straight line is fitted to the outline along each
     * side of [quad], and neighbouring lines are crossed. A corner hidden by a dark print, a fold
     * or a finger is found where the edges point. A corner that would move far keeps its place.
     */
    private fun straighten(quad: List<P>, outline: List<P>, diagonal: Double): List<P> {
        val lines = (0 until 4).map { k ->
            val a = quad[k]
            val b = quad[(k + 1) % 4]
            val len = hypot(b.x - a.x, b.y - a.y)
            if (len < 1e-6) return quad
            var line = Line(a, (b.x - a.x) / len, (b.y - a.y) / len)
            // Points along the middle of this side, in a band around it.
            val near = outline.filter { q ->
                val t = ((q.x - a.x) * line.dx + (q.y - a.y) * line.dy) / len
                t in 0.08..0.92 && line.distance(q) < diagonal * 0.025
            }
            if (near.size >= 12) {
                // The longest straight stretch wins: of the lines through two points far apart,
                // the one most points lie on (a ragged or hidden part of the edge then does not
                // tilt it), fitted again to those points.
                val along = near.sortedBy { (it.x - a.x) * line.dx + (it.y - a.y) * line.dy }
                val step = maxOf(1, along.size / 40)
                val samples = along.indices.step(step).map { along[it] }
                val tight = diagonal * 0.005
                var best: List<P> = emptyList()
                for (i in samples.indices) for (j in i + 1 until samples.size) {
                    val p = samples[i]
                    val q = samples[j]
                    val d = hypot(q.x - p.x, q.y - p.y)
                    if (d < len * 0.25) continue
                    val candidate = Line(p, (q.x - p.x) / d, (q.y - p.y) / d)
                    val on = near.filter { candidate.distance(it) < tight }
                    if (on.size > best.size) best = on
                }
                if (best.size >= 12) {
                    // Kept pointing the same way as the side, so positions along it read from a to b.
                    val fitted = fit(best)
                    line = if (fitted.dx * (b.x - a.x) + fitted.dy * (b.y - a.y) < 0) Line(fitted.p, -fitted.dx, -fitted.dy) else fitted
                }
            }
            line
        }
        return (0 until 4).map { k ->
            val corner = intersect(lines[(k + 3) % 4], lines[k])
            if (corner == null || hypot(corner.x - quad[k].x, corner.y - quad[k].y) > diagonal * 0.08) quad[k] else corner
        }
    }

    /** The four hull points enclosing the largest area (they keep the hull's order). */
    internal fun largestQuad(pts: List<P>): List<P>? {
        val n = pts.size
        if (n < 4) return null
        var best = -1.0
        var result: List<P>? = null
        for (i in 0 until n) for (k in i + 2 until n) {
            // Best third corner on each side of the diagonal i–k.
            var left = -1.0
            var lj = -1
            for (j in i + 1 until k) {
                val a = triangle(pts[i], pts[j], pts[k])
                if (a > left) { left = a; lj = j }
            }
            var right = -1.0
            var rl = -1
            var l = k + 1
            while (l < n + i) {
                val a = triangle(pts[k], pts[l % n], pts[i])
                if (a > right) { right = a; rl = l % n }
                l++
            }
            if (lj < 0 || rl < 0) continue
            if (left + right > best) {
                best = left + right
                result = listOf(pts[i], pts[lj], pts[k], pts[rl])
            }
        }
        return result
    }

    private fun polygonArea(p: List<P>): Double {
        var s = 0.0
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            s += a.x * b.y - b.x * a.y
        }
        return abs(s) / 2
    }

    /** A page seen at an angle: no corner sharper than 40° or blunter than 140°. */
    private fun sensible(q: List<P>): Boolean {
        for (i in 0 until 4) {
            val a = q[(i + 3) % 4]
            val b = q[i]
            val c = q[(i + 1) % 4]
            val v1x = a.x - b.x; val v1y = a.y - b.y
            val v2x = c.x - b.x; val v2y = c.y - b.y
            val len = sqrt(v1x * v1x + v1y * v1y) * sqrt(v2x * v2x + v2y * v2y)
            if (len < 1e-6) return false
            val angle = Math.toDegrees(acos(((v1x * v2x + v1y * v2y) / len).coerceIn(-1.0, 1.0)))
            if (angle < 40 || angle > 140) return false
        }
        return true
    }

    /** Clockwise on screen (y down), starting from the top-left corner. */
    private fun order(q: List<P>): List<P> {
        var s = 0.0
        for (i in q.indices) {
            val a = q[i]
            val b = q[(i + 1) % q.size]
            s += a.x * b.y - b.x * a.y
        }
        // With y down, a positive shoelace sum runs clockwise on screen.
        val cw = if (s > 0) q else q.asReversed()
        val first = cw.indices.minBy { cw[it].x + cw[it].y }
        return (0 until 4).map { cw[(first + it) % 4] }
    }
}
