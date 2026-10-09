package com.aamus.powerpdf.engine

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Follows the page outline frame by frame for the scanner's live camera (docs/research-scan.md
 * M5): smooths it over the last few frames, decides when the phone is held still over a page, and
 * says when to take the picture by itself. Pure logic, tested on the JVM.
 *
 * Outlines are 8 numbers: the corners top-left, top-right, bottom-right, bottom-left as fractions
 * of the frame (x, y each), as [PageEdges.find] gives them.
 */
class ScanTracker(
    /** How long the outline must stay still before the picture is taken. */
    private val steadyMillis: Long = 800,
    /** A corner moving less than this (fraction of the frame) between frames counts as still. */
    private val stillMove: Float = 0.02f,
    /** The outline's area changing less than this share counts as still. */
    private val stillArea: Float = 0.15f,
) {
    enum class State {
        /** No page in view. */
        Searching,
        /** A page, but the phone moves. */
        Moving,
        /** Held still: [progress] runs from 0 to 1. */
        Steady,
        /** Take the picture now (reported once per page). */
        Capture,
        /** Just taken: waiting for the next page (the outline goes away or changes clearly). */
        Taken,
    }

    class Reading(val state: State, val outline: FloatArray?, val progress: Float)

    private val recent = ArrayDeque<FloatArray>()
    /** Whether a page was found in each of the last few frames (docs/research-page-detection.md §6). */
    private val hits = ArrayDeque<Boolean>()

    private fun hit(found: Boolean) {
        hits.addLast(found)
        while (hits.size > WINDOW) hits.removeFirst()
    }
    private var still: Long? = null
    private var taken: FloatArray? = null
    private var misses = 0

    /** A new frame's outline (null: no page found) at [now] milliseconds. */
    fun next(outline: FloatArray?, now: Long): Reading {
        hit(outline != null)
        if (outline == null) {
            // A frame or two without a page (a reflection, motion blur) does not lose it.
            misses++
            if (misses > 3) {
                recent.clear()
                still = null
                taken = null
            }
            val last = recent.lastOrNull()
            return Reading(if (last == null) State.Searching else if (taken != null) State.Taken else State.Moving, smoothed(), 0f)
        }
        misses = 0
        val previous = recent.lastOrNull()
        recent.addLast(outline)
        while (recent.size > 3) recent.removeFirst()
        val smooth = smoothed()!!
        val calm = previous != null && moved(previous, outline) < stillMove && areaChange(previous, outline) < stillArea
        // After a picture, the next one waits for a different page: the outline gone, or moved well away.
        val t = taken
        if (t != null) {
            if (moved(t, smooth) > stillMove * 5 || areaChange(t, smooth) > stillArea * 2) taken = null
            else return Reading(State.Taken, smooth, 0f)
        }
        // Held still over something found in nearly every recent frame: a page that flickers in
        // and out (an arm moving past, a shadow) never starts the count.
        if (!calm || hits.size < WINDOW || hits.count { it } < WINDOW - 1) {
            still = null
            return Reading(State.Moving, smooth, 0f)
        }
        val since = still ?: now.also { still = it }
        val progress = ((now - since).toFloat() / steadyMillis).coerceIn(0f, 1f)
        if (progress >= 1f) {
            taken = smooth
            still = null
            return Reading(State.Capture, smooth, 1f)
        }
        return Reading(State.Steady, smooth, progress)
    }

    /** The picture was taken by hand: the same page is not taken again by itself. */
    fun tookPicture() {
        taken = smoothed()
        still = null
    }

    fun reset() {
        hits.clear()
        recent.clear()
        still = null
        taken = null
        misses = 0
    }

    /** The average of the last few outlines (less jitter on screen). */
    private fun smoothed(): FloatArray? {
        if (recent.isEmpty()) return null
        val out = FloatArray(8)
        for (o in recent) for (i in 0 until 8) out[i] += o[i]
        for (i in 0 until 8) out[i] /= recent.size
        return out
    }

    companion object {
        /** A page must be found in all but one of this many recent frames before the count starts. */
        const val WINDOW = 6

        /** The largest distance any corner moved. */
        fun moved(a: FloatArray, b: FloatArray): Float =
            (0 until 4).maxOf { hypot(a[it * 2] - b[it * 2], a[it * 2 + 1] - b[it * 2 + 1]) }

        /** The outline's area (shoelace formula), as a fraction of the frame. */
        fun area(q: FloatArray): Float {
            var s = 0f
            for (i in 0 until 4) {
                val j = (i + 1) % 4
                s += q[i * 2] * q[j * 2 + 1] - q[j * 2] * q[i * 2 + 1]
            }
            return abs(s) / 2f
        }

        fun areaChange(a: FloatArray, b: FloatArray): Float {
            val x = area(a)
            val y = area(b)
            return if (maxOf(x, y) <= 0f) 1f else abs(x - y) / maxOf(x, y)
        }
    }
}
