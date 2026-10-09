package com.aamus.powerpdf.engine

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Finds a sheet of paper in a camera frame by its edges, the way published live scanners do
 * (docs/research-page-detection.md, tier a): text is erased by a grey closing, the remaining
 * edges are found (gradient, thinning, hysteresis), straight lines are collected by a Hough
 * transform per direction with the frame's own borders as extra candidates (a page touching the
 * frame), every quadrilateral of two near-horizontal and two near-vertical lines is checked for a
 * plausible shape, and scored by how much real edge lies along its sides, how little the edges run
 * on past its corners, and how much lighter it is inside than out. Plain Kotlin on a ~240 × 320
 * frame; no library. Replaces the light-region search of [PageEdges] for the live camera, which
 * failed on light desks and on pages filling the frame (the owner's frames, 2026-10-02).
 */
object PageQuad {

    /** What was found: corners as fractions (top-left, top-right, bottom-right, bottom-left). */
    class Found(
        val corners: FloatArray,
        val score: Float,
        /** Sides with an edge measured along them (not along the frame's border). */
        val measured: Int,
        /** Measured sides with enough edge along them. */
        val supported: Int,
        /** Measured sides lighter inside than outside. */
        val lighter: Int,
        val area: Float,
        val supports: FloatArray,
        val contrasts: FloatArray,
    ) {
        val confident: Boolean
            // Every side that can be seen must be a real edge, lighter inside (a sheet in view shows
            // all its edges); one weak side only when three others are clear (a shadow, a finger).
            // Two strong sides with two weak ones were a false capture on the phone (2026-10-02).
            get() {
                val weak = measured - supported
                val dull = measured - lighter
                return supported >= 2 && lighter >= 2 &&
                    (weak == 0 || (weak == 1 && supported >= 3)) &&
                    (dull == 0 || (dull == 1 && lighter >= 3)) &&
                    (area >= 0.3f || measured >= 3) && score >= MIN_SCORE
            }

        /**
         * Sure enough to take the picture by itself: [confident] with at most one side along the
         * frame's edge. A light band of desk between a keyboard and a mouse pad, its ends running
         * off the frame, has two clear edges and was taken as a page (the owner's phone,
         * 2026-10-02); a page held so close that two sides leave the frame gets its outline and
         * the shutter instead.
         */
        val autoCapture: Boolean
            get() = confident && measured >= 3 && area >= AUTO_AREA

        override fun toString() = String.format(
            java.util.Locale.US, "score %.2f sides %d/%d lighter %d area %.2f support %s contrast %s → %s",
            score, supported, measured, lighter, area,
            supports.joinToString(",", "[", "]") { "%.2f".format(java.util.Locale.US, it) },
            contrasts.joinToString(",", "[", "]") { "%.2f".format(java.util.Locale.US, it) },
            if (confident) "page" else "no",
        )
    }

    const val MIN_SCORE = 1.6f

    /**
     * The least share of the frame a page must fill to be taken by itself: the owner's real pages
     * filled 34 % or more when held for a scan; light patches of desk framed by a keyboard, a bag
     * or a cable that passed the edge checks were 22 % at most (frames of 2026-10-02).
     */
    const val AUTO_AREA = 0.28f
    private const val MIN_SUPPORT = 0.55f

    /** Lighter inside than out by this share of full light (real page sides: median 0.23, 9 in 10 above 0.15; a lamp glow 0.06). */
    private const val MIN_CONTRAST = 0.10f

    private class Line(val theta: Double, val rho: Double, val weight: Float, val border: Boolean) {
        val c = cos(theta)
        val s = sin(theta)
    }

    /**
     * Whether four corners from elsewhere (the page model) make a sheet's shape that may be taken
     * by itself: inside the frame (3 % slack), convex with corners of 90° ± 30°, opposite sides
     * within 2:1, and filling at least [AUTO_AREA] of the frame, like [Found.autoCapture].
     */
    fun plausible(corners: FloatArray): Boolean {
        if (corners.size < 8) return false
        val p = (0 until 4).map { corners[it * 2].toDouble() to corners[it * 2 + 1].toDouble() }
        if (p.any { it.first < -0.03 || it.second < -0.03 || it.first > 1.03 || it.second > 1.03 }) return false
        for (k in 0 until 4) {
            val a = p[(k + 3) % 4]
            val b = p[k]
            val c = p[(k + 1) % 4]
            val v1x = a.first - b.first
            val v1y = a.second - b.second
            val v2x = c.first - b.first
            val v2y = c.second - b.second
            val len = sqrt(v1x * v1x + v1y * v1y) * sqrt(v2x * v2x + v2y * v2y)
            if (len < 1e-9) return false
            val angle = Math.toDegrees(acos(((v1x * v2x + v1y * v2y) / len).coerceIn(-1.0, 1.0)))
            if (angle < 60 || angle > 120) return false
        }
        val len = (0 until 4).map { hypot(p[(it + 1) % 4].first - p[it].first, p[(it + 1) % 4].second - p[it].second) }
        if (min(len[0], len[2]) / max(len[0], len[2]) < 0.5 || min(len[1], len[3]) / max(len[1], len[3]) < 0.5) return false
        return polygonArea(p) >= AUTO_AREA
    }

    fun detect(argb: IntArray, w: Int, h: Int): Found? {
        if (w < 32 || h < 32 || argb.size < w * h) return null
        val n = w * h
        val lum = IntArray(n) { val c = argb[it]; (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000 }
        // Text, rules and pictures on the page are darker and thin: a closing (max, then min)
        // fills them with the paper's light, so the page is flat inside and its edge remains.
        // Wide enough for a heading or a dark band at 240 px (a page edge borders a large dark area and stays).
        val r = max(3, min(w, h) / 60)
        val closed = rank(rank(lum, w, h, r, true), w, h, r, false)
        blur(closed, w, h)
        val edges = edgeMap(closed, w, h)
        val diag = hypot(w.toDouble(), h.toDouble())
        val horizontal = lines(edges.mag, edges.horizontal, w, h, diag, Math.toRadians(90.0)) +
            listOf(Line(Math.PI / 2, 0.0, 0f, true), Line(Math.PI / 2, (h - 1).toDouble(), 0f, true))
        val vertical = lines(edges.mag, edges.vertical, w, h, diag, 0.0) +
            listOf(Line(0.0, 0.0, 0f, true), Line(0.0, (w - 1).toDouble(), 0f, true))

        // Where each line crosses the middle of the frame: which of a pair is top (left).
        fun yMid(l: Line) = if (abs(l.s) < 1e-6) Double.MAX_VALUE else (l.rho - w / 2.0 * l.c) / l.s
        fun xMid(l: Line) = if (abs(l.c) < 1e-6) Double.MAX_VALUE else (l.rho - h / 2.0 * l.s) / l.c
        fun quad(sides: List<Line>): Found? {
            val (top, right, bottom, left) = sides
            val tl = cross(top, left) ?: return null
            val tr = cross(top, right) ?: return null
            val br = cross(bottom, right) ?: return null
            val bl = cross(bottom, left) ?: return null
            return evaluate(listOf(tl, tr, br, bl), sides, edges, closed, w, h, diag)
        }
        // Every pairing, keeping the few best for refinement.
        val ranked = ArrayList<Pair<Found, List<Line>>>()
        for (i in horizontal.indices) for (j in i + 1 until horizontal.size) {
            val (top, bottom) = if (yMid(horizontal[i]) <= yMid(horizontal[j])) horizontal[i] to horizontal[j] else horizontal[j] to horizontal[i]
            for (k in vertical.indices) for (l in k + 1 until vertical.size) {
                val (left, right) = if (xMid(vertical[k]) <= xMid(vertical[l])) vertical[k] to vertical[l] else vertical[l] to vertical[k]
                val sides = listOf(top, right, bottom, left)
                val f = quad(sides) ?: continue
                ranked.add(f to sides)
            }
        }
        ranked.sortByDescending { it.first.score }
        // The Hough lines are 1° apart: each side of the best few is fitted again to the edge
        // pixels along it, then scored again (a side a little off its edge loses support).
        var best: Found? = null
        for ((found, sides) in ranked.take(5)) {
            val refined = quad(List(4) { k -> refine(sides[k], k % 2 == 0, found.corners, k, edges, w, h) })
            val pick = if (refined != null && refined.score >= found.score) refined else found
            if (best == null || pick.score > best.score) best = pick
        }
        return best
    }

    /**
     * [line] (side [k] of the quad with [corners]) fitted again to the edge pixels of its
     * direction within 3 px of it, between its corners: least squares across the line.
     */
    private fun refine(line: Line, horizontalSide: Boolean, corners: FloatArray, k: Int, e: Edges, w: Int, h: Int): Line {
        if (line.border) return line
        val map = if (horizontalSide) e.horizontal else e.vertical
        val ax = corners[k * 2] * w
        val ay = corners[k * 2 + 1] * h
        val bx = corners[((k + 1) % 4) * 2] * w
        val by = corners[((k + 1) % 4) * 2 + 1] * h
        val len = hypot((bx - ax).toDouble(), (by - ay).toDouble())
        if (len < 10) return line
        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0
        var n = 0
        val steps = len.toInt()
        for (s in 0..steps) {
            val t = 0.05 + 0.9 * s / steps
            val px = ax + (bx - ax) * t
            val py = ay + (by - ay) * t
            for (o in -3..3) {
                val x = (px + line.c * o).roundToInt()
                val y = (py + line.s * o).roundToInt()
                if (x !in 0 until w || y !in 0 until h || !map[y * w + x]) continue
                sx += x; sy += y; sxx += x.toDouble() * x; syy += y.toDouble() * y; sxy += x.toDouble() * y
                n++
            }
        }
        if (n < 12) return line
        val mx = sx / n
        val my = sy / n
        val cxx = sxx / n - mx * mx
        val cyy = syy / n - my * my
        val cxy = sxy / n - mx * my
        // The direction of least spread is the line's normal.
        val along = 0.5 * kotlin.math.atan2(2 * cxy, cxx - cyy)
        var theta = along + Math.PI / 2
        // Keep the normal on the same side as before (θ near the old one, modulo π).
        while (theta - line.theta > Math.PI / 2) theta -= Math.PI
        while (line.theta - theta > Math.PI / 2) theta += Math.PI
        if (abs(theta - line.theta) > Math.toRadians(6.0)) return line
        return Line(theta, mx * cos(theta) + my * sin(theta), line.weight, false)
    }

    /** The intersection of two lines, or null when nearly parallel. */
    private fun cross(a: Line, b: Line): Pair<Double, Double>? {
        val det = a.c * b.s - a.s * b.c
        if (abs(det) < 1e-6) return null
        val x = (a.rho * b.s - b.rho * a.s) / det
        val y = (a.c * b.rho - b.c * a.rho) / det
        return x to y
    }

    private class Edges(val mag: IntArray, val horizontal: BooleanArray, val vertical: BooleanArray)

    /**
     * Gradient, thinning along the gradient, hysteresis with thresholds from the frame itself (a
     * dim scene still has edges), short runs dropped; split by direction.
     */
    private fun edgeMap(v: IntArray, w: Int, h: Int): Edges {
        val n = w * h
        val gx = IntArray(n)
        val gy = IntArray(n)
        val mag = IntArray(n)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            gx[i] = v[i + 1] - v[i - 1]
            gy[i] = v[i + w] - v[i - w]
            mag[i] = abs(gx[i]) + abs(gy[i])
        }
        val thin = IntArray(n)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val m = mag[i]
            if (m == 0) continue
            val ax = abs(gx[i])
            val ay = abs(gy[i])
            val (a, b) = when {
                ay * 5 < ax * 2 -> mag[i - 1] to mag[i + 1]
                ax * 5 < ay * 2 -> mag[i - w] to mag[i + w]
                (gx[i] > 0) == (gy[i] > 0) -> mag[i - w - 1] to mag[i + w + 1]
                else -> mag[i - w + 1] to mag[i + w - 1]
            }
            if (m >= a && m >= b) thin[i] = m
        }
        val nonZero = thin.filter { it > 0 }.sorted()
        val median = if (nonZero.isEmpty()) 0 else nonZero[nonZero.size / 2]
        val high = max(10, median * 2)
        val low = max(5, high / 2)
        val keep = BooleanArray(n)
        val queue = IntArray(n)
        var tail = 0
        for (i in 0 until n) if (thin[i] >= high) { keep[i] = true; queue[tail++] = i }
        var head = 0
        while (head < tail) {
            val i = queue[head++]
            val x = i % w
            val y = i / w
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx
                val yy = y + dy
                if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                val j = yy * w + xx
                if (!keep[j] && thin[j] >= low) { keep[j] = true; queue[tail++] = j }
            }
        }
        // Short runs are text remnants and noise, not a sheet's edge.
        val minRun = max(8, (min(w, h) * 0.06).roundToInt())
        val seen = BooleanArray(n)
        for (start in 0 until n) {
            if (!keep[start] || seen[start]) continue
            var t = 0
            queue[t++] = start
            seen[start] = true
            var hd = 0
            while (hd < t) {
                val i = queue[hd++]
                val x = i % w
                val y = i / w
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx
                    val yy = y + dy
                    if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                    val j = yy * w + xx
                    if (keep[j] && !seen[j]) { seen[j] = true; queue[t++] = j }
                }
            }
            if (t < minRun) for (k in 0 until t) keep[queue[k]] = false
        }
        val horizontal = BooleanArray(n) { keep[it] && abs(gy[it]) >= abs(gx[it]) }
        val vertical = BooleanArray(n) { keep[it] && abs(gx[it]) > abs(gy[it]) }
        return Edges(IntArray(n) { if (keep[it]) mag[it] else 0 }, horizontal, vertical)
    }

    /**
     * The strongest straight lines among [on] pixels whose normal lies within ±35° of [normal]
     * (Hough, 1° × 1 px, votes weighted by edge strength): up to 8, each a clear peak.
     */
    private fun lines(mag: IntArray, on: BooleanArray, w: Int, h: Int, diag: Double, normal: Double): List<Line> {
        val span = 35
        val thetas = DoubleArray(2 * span + 1) { normal + Math.toRadians((it - span).toDouble()) }
        val cs = DoubleArray(thetas.size) { cos(thetas[it]) }
        val sn = DoubleArray(thetas.size) { sin(thetas[it]) }
        val offset = diag.toInt() + 1
        val rhos = 2 * offset + 1
        val acc = IntArray(thetas.size * rhos)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (!on[i]) continue
            val m = mag[i]
            for (t in thetas.indices) {
                val rho = (x * cs[t] + y * sn[t]).roundToInt() + offset
                acc[t * rhos + rho] += m
            }
        }
        val top = acc.maxOrNull() ?: 0
        if (top <= 0) return emptyList()
        val out = ArrayList<Line>()
        val used = BooleanArray(acc.size)
        repeat(8) {
            var bi = -1
            var bv = 0
            for (k in acc.indices) if (!used[k] && acc[k] > bv) { bv = acc[k]; bi = k }
            if (bi < 0 || bv < top * 0.15) return out
            val t = bi / rhos
            val rho = bi % rhos
            out.add(Line(thetas[t], (rho - offset).toDouble(), bv.toFloat() / top, false))
            for (dt in -4..4) for (dr in -8..8) {
                val tt = t + dt
                val rr = rho + dr
                if (tt in thetas.indices && rr in 0 until rhos) used[tt * rhos + rr] = true
            }
        }
        return out
    }

    /** Shape checks and the score for the quad [p] (top-left, top-right, bottom-right, bottom-left). */
    private fun evaluate(p: List<Pair<Double, Double>>, sides: List<Line>, e: Edges, lum: IntArray, w: Int, h: Int, diag: Double): Found? {
        val slack = diag * 0.08
        if (p.any { it.first < -slack || it.second < -slack || it.first > w - 1 + slack || it.second > h - 1 + slack }) return null
        // Convex, with corners near square (a sheet seen at an angle: 90° ± 30°).
        for (k in 0 until 4) {
            val a = p[(k + 3) % 4]
            val b = p[k]
            val c = p[(k + 1) % 4]
            val v1x = a.first - b.first
            val v1y = a.second - b.second
            val v2x = c.first - b.first
            val v2y = c.second - b.second
            val len = sqrt(v1x * v1x + v1y * v1y) * sqrt(v2x * v2x + v2y * v2y)
            if (len < 1e-6) return null
            val angle = Math.toDegrees(acos(((v1x * v2x + v1y * v2y) / len).coerceIn(-1.0, 1.0)))
            if (angle < 60 || angle > 120) return null
        }
        val area = polygonArea(p) / (w.toDouble() * h)
        if (area < 0.12 || area > 0.98) return null
        val len = (0 until 4).map { hypot(p[(it + 1) % 4].first - p[it].first, p[(it + 1) % 4].second - p[it].second) }
        if (min(len[0], len[2]) / max(len[0], len[2]) < 0.5 || min(len[1], len[3]) / max(len[1], len[3]) < 0.5) return null
        val aspect = min(len[0] + len[2], len[1] + len[3]) / max(len[0] + len[2], len[1] + len[3])
        if (aspect < 0.3) return null

        val cx = p.sumOf { it.first } / 4
        val cy = p.sumOf { it.second } / 4
        val supports = FloatArray(4) { -1f }
        val contrasts = FloatArray(4)
        var measured = 0
        var supported = 0
        var lighter = 0
        var score = 0.0
        for (k in 0 until 4) {
            val a = p[k]
            val b = p[(k + 1) % 4]
            val horizontalSide = k % 2 == 0
            val map = if (horizontalSide) e.horizontal else e.vertical
            if (sides[k].border) {
                score += 0.35
                continue
            }
            val sl = hypot(b.first - a.first, b.second - a.second)
            val dx = (b.first - a.first) / sl
            val dy = (b.second - a.second) / sl
            var nx = -dy
            var ny = dx
            val mx = (a.first + b.first) / 2
            val my = (a.second + b.second) / 2
            if (nx * (mx - cx) + ny * (my - cy) < 0) { nx = -nx; ny = -ny }
            // Support: an edge of this direction within 2 px at points along the side (inside the frame).
            var inFrame = 0
            var hits = 0
            val diffs = ArrayList<Int>()
            for (s in 1..39) {
                val t = s / 40.0
                val px = a.first + (b.first - a.first) * t
                val py = a.second + (b.second - a.second) * t
                if (px < 1 || py < 1 || px > w - 2 || py > h - 2) continue
                inFrame++
                var hit = false
                for (o in -2..2) {
                    val x = (px + nx * o).roundToInt()
                    val y = (py + ny * o).roundToInt()
                    if (x in 0 until w && y in 0 until h && map[y * w + x]) { hit = true; break }
                }
                if (hit) hits++
                val ix = (px - nx * 4).roundToInt()
                val iy = (py - ny * 4).roundToInt()
                val ox = (px + nx * 4).roundToInt()
                val oy = (py + ny * 4).roundToInt()
                if (ix in 0 until w && iy in 0 until h && ox in 0 until w && oy in 0 until h) diffs.add(lum[iy * w + ix] - lum[oy * w + ox])
            }
            if (inFrame < 12) {
                // Mostly outside the picture: like a border side.
                score += 0.35
                continue
            }
            measured++
            val support = hits.toFloat() / inFrame
            supports[k] = support
            val contrast = if (diffs.isEmpty()) 0f else diffs.sorted()[diffs.size / 2] / 255f
            contrasts[k] = contrast
            // A strong step with a somewhat broken edge (a wavy sheet on a dark chair) is a side too.
            if (support >= MIN_SUPPORT || (support >= 0.4f && contrast >= 0.25f)) supported++
            if (contrast >= MIN_CONTRAST) lighter++
            // Edges that carry on past the corners: the side is part of something longer (a desk edge, a keyboard).
            var past = 0
            var pastIn = 0
            for (end in 0..1) for (s in 1..10) {
                val px = if (end == 0) a.first - dx * s * 1.5 else b.first + dx * s * 1.5
                val py = if (end == 0) a.second - dy * s * 1.5 else b.second + dy * s * 1.5
                if (px < 1 || py < 1 || px > w - 2 || py > h - 2) continue
                pastIn++
                for (o in -1..1) {
                    val x = (px + nx * o).roundToInt()
                    val y = (py + ny * o).roundToInt()
                    if (map[y * w + x]) { past++; break }
                }
            }
            val continuation = if (pastIn == 0) 0f else past.toFloat() / pastIn
            score += support - 0.5 * continuation + 3.0 * max(0f, min(contrast, 0.25f))
        }
        // A mild preference for larger pages (people fill the frame with what they scan).
        score += 0.15 * ln(area / 0.12)
        val out = FloatArray(8)
        for (k in 0 until 4) {
            out[k * 2] = (p[k].first / w).toFloat().coerceIn(0f, 1f)
            out[k * 2 + 1] = (p[k].second / h).toFloat().coerceIn(0f, 1f)
        }
        return Found(out, score.toFloat(), measured, supported, lighter, area.toFloat(), supports, contrasts)
    }

    private fun polygonArea(p: List<Pair<Double, Double>>): Double {
        var s = 0.0
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            s += a.first * b.second - b.first * a.second
        }
        return abs(s) / 2
    }

    /** The largest ([max]) or smallest value within [r] pixels, in a square window. */
    private fun rank(v: IntArray, w: Int, h: Int, r: Int, max: Boolean): IntArray {
        val tmp = IntArray(v.size)
        val out = IntArray(v.size)
        for (y in 0 until h) for (x in 0 until w) {
            var m = if (max) 0 else 255
            for (k in maxOf(0, x - r)..minOf(w - 1, x + r)) m = if (max) maxOf(m, v[y * w + k]) else minOf(m, v[y * w + k])
            tmp[y * w + x] = m
        }
        for (y in 0 until h) for (x in 0 until w) {
            var m = if (max) 0 else 255
            for (k in maxOf(0, y - r)..minOf(h - 1, y + r)) m = if (max) maxOf(m, tmp[k * w + x]) else minOf(m, tmp[k * w + x])
            out[y * w + x] = m
        }
        return out
    }

    /** 3 × 3 box blur in place. */
    private fun blur(v: IntArray, w: Int, h: Int) {
        val src = v.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0
            var c = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx
                val yy = y + dy
                if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                s += src[yy * w + xx]
                c++
            }
            v[y * w + x] = s / c
        }
    }
}
