package com.aamus.powerpdf.engine

import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * A pen stroke as a smooth curve through the points drawn (desktop src/shared/smooth.ts,
 * decisions §82 there, §52 here): each stretch between two points is the Catmull-Rom curve through
 * them, written as a cubic Bézier (control points a sixth of the neighbours' difference away) and
 * sampled about every [step] units.
 *
 * MuPDF draws an Ink annotation's /InkList as straight lines from point to point, and so do other
 * readers that make their own appearance from the list; a list that already follows the curve shows
 * the same smooth stroke everywhere. Every point drawn stays in the list, exactly (rounded to 0.01).
 * Strokes of one or two points are returned as they are. The pen preview draws the same list, so
 * what is seen while drawing is what is stored.
 */
object Smooth {
    fun stroke(points: List<Pair<Float, Float>>, step: Float = 1.5f, limit: Int = 4000): List<Pair<Float, Float>> {
        val p = points.filterIndexed { i, q -> i == 0 || hypot((q.first - points[i - 1].first).toDouble(), (q.second - points[i - 1].second).toDouble()) > 1e-6 }
        if (p.size < 3) return points
        var length = 0.0
        for (i in 1 until p.size) length += hypot((p[i].first - p[i - 1].first).toDouble(), (p[i].second - p[i - 1].second).toDouble())
        // A very long stroke is sampled more coarsely so the list stays a sensible size.
        val each = max(step.toDouble(), length / limit)
        fun round(v: Double): Float = ((v * 100).roundToLong() / 100.0).toFloat()
        val out = ArrayList<Pair<Float, Float>>()
        out.add(round(p[0].first.toDouble()) to round(p[0].second.toDouble()))
        for (i in 0 until p.size - 1) {
            val p1 = p[i]
            val p2 = p[i + 1]
            val p0 = p.getOrNull(i - 1) ?: p1
            val p3 = p.getOrNull(i + 2) ?: p2
            val c1x = p1.first + (p2.first - p0.first) / 6.0
            val c1y = p1.second + (p2.second - p0.second) / 6.0
            val c2x = p2.first - (p3.first - p1.first) / 6.0
            val c2y = p2.second - (p3.second - p1.second) / 6.0
            val n = max(1, min(64, ceil(hypot((p2.first - p1.first).toDouble(), (p2.second - p1.second).toDouble()) / each).toInt()))
            for (k in 1..n) {
                if (k == n) {
                    out.add(round(p2.first.toDouble()) to round(p2.second.toDouble()))
                    continue
                }
                val t = k.toDouble() / n
                val u = 1 - t
                val a = u * u * u
                val b = 3 * u * u * t
                val c = 3 * u * t * t
                val d = t * t * t
                out.add(round(a * p1.first + b * c1x + c * c2x + d * p2.first) to round(a * p1.second + b * c1y + c * c2y + d * p2.second))
            }
        }
        return out
    }
}
