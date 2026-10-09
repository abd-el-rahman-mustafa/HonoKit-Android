package com.aamus.powerpdf.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Layout rules for landscape phones, tablets and large font sizes (decisions §43; UX review
 * findings 3–5). Pure so they can be unit-tested; the composables below read the window.
 */
object Adaptive {
    /** Below this height (dp) a window is "compact height": a phone held sideways (Material's window size classes). */
    const val COMPACT_HEIGHT = 480

    /** From this width (dp) there is room for two panes side by side (Material's "medium" width). */
    const val MEDIUM_WIDTH = 600

    /** From this width (dp) a rail is used even when the window is tall (Material's "expanded" width). */
    const val EXPANDED_WIDTH = 840

    /** A font scale above this is "large text": door subtitles go, tool grids get fewer, wider columns. */
    const val LARGE_FONT = 1.3f

    /**
     * A navigation rail instead of the bottom bar: when the height is compact (a phone sideways,
     * where a bottom bar and a top bar leave one row of content) or the window is very wide.
     * Material's NavigationSuiteScaffold keeps a bottom bar on a landscape phone by default, which
     * is the case this is for.
     */
    fun useRail(widthDp: Int, heightDp: Int): Boolean = heightDp < COMPACT_HEIGHT || widthDp >= EXPANDED_WIDTH

    fun compactHeight(heightDp: Int): Boolean = heightDp < COMPACT_HEIGHT

    /** How many cells of at least [minCellDp] fit in [availableDp], between 1 and [max]. */
    fun columns(availableDp: Float, minCellDp: Float, max: Int = Int.MAX_VALUE): Int {
        if (availableDp <= 0f || minCellDp <= 0f) return 1
        return (availableDp / minCellDp).toInt().coerceIn(1, maxOf(1, max))
    }

    /**
     * Columns for a fixed set of [count] items (Home's four tools): as many as fit, but only a
     * number that leaves no lone item on the last row (4 → 4, 2 or 1; never 3 with one left over).
     */
    fun evenColumns(availableDp: Float, minCellDp: Float, count: Int): Int {
        val fit = columns(availableDp, minCellDp, count)
        return (fit downTo 1).first { count % it == 0 }
    }

    /**
     * The narrowest a tool tile may be so that no word of any label is broken in the middle:
     * the widest single word ([widestWordDp], measured in the label's style) plus the tile's
     * padding, and never narrower than [floorDp].
     */
    fun minToolCell(widestWordDp: Float, paddingDp: Float = 16f, floorDp: Float = 88f): Float = maxOf(floorDp, widestWordDp + paddingDp)

    /**
     * Columns for Home's one row of tiles (the pinned tools, the doors; decisions §44): four
     * across whenever the widest word of *these* labels fits a quarter — labels wrap at word
     * boundaries onto two lines — so a 360–375 dp phone at normal text keeps one row of four.
     * Only large text drops to two rows of two (the phone at sw375 showed 2 × 2 big tiles with
     * room to spare: the old rule measured every tool's name against an 88 dp floor).
     */
    fun rowColumns(availableDp: Float, widestWordDp: Float, count: Int): Int =
        evenColumns(availableDp, minToolCell(widestWordDp, paddingDp = ROW_PADDING, floorDp = ROW_FLOOR), count)

    /** A row tile's room around its label (4 dp each side and a little to spare) and its narrowest size. */
    const val ROW_PADDING = 10f
    const val ROW_FLOOR = 64f

    /** The hubs' grid: tiles of at least this width, so a 360–375 dp phone shows four columns at normal text. */
    const val HUB_FLOOR = 80f
    const val HUB_PADDING = 12f

    /** The words of some labels, as a line breaker sees them (spaces only; "&" and "PDF" are words too). */
    fun words(labels: List<String>): List<String> = labels.flatMap { it.split(' ', ' ', '\n') }.filter { it.isNotBlank() }
}

/** Whether this window is a phone held sideways (compact height). */
@Composable
fun isCompactHeight(): Boolean = Adaptive.compactHeight(LocalConfiguration.current.screenHeightDp)

/** Whether the person chose large text (above 130 %). */
@Composable
fun isLargeFont(): Boolean = LocalDensity.current.fontScale > Adaptive.LARGE_FONT

/**
 * The side bars a control at a screen edge must keep clear of in landscape: the navigation
 * buttons (on the left or right when the phone is sideways) and a camera cut-out.
 */
val SideBars: WindowInsets
    @Composable get() = WindowInsets.navigationBars.union(WindowInsets.displayCutout)

/**
 * The narrowest tool tile for these label string ids, measured in the tile's label style at the
 * person's font size, so a word never breaks across lines ("Comme / nt" at 200 %).
 */
@Composable
fun rememberToolCellMin(titles: List<Int>, floor: Dp = Adaptive.HUB_FLOOR.dp, padding: Dp = Adaptive.HUB_PADDING.dp): Dp {
    val widest = rememberWidestWord(titles)
    return Adaptive.minToolCell(widest.value, paddingDp = padding.value, floorDp = floor.value).dp
}

/** The widest word of these labels in the tool tile's label style, at the person's font size. */
@Composable
fun rememberWidestWord(titles: List<Int>): Dp {
    val context = LocalContext.current
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelMedium
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    return remember(titles, density.fontScale, density.density, config.locales, style) {
        val words = Adaptive.words(titles.map { context.getString(it) })
        val widest = words.maxOfOrNull { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width } ?: 0
        with(density) { widest.toDp() }
    }
}

/**
 * Fades the edge of a sideways-scrolling row where more is hidden (a toolbar wider than the
 * screen with large text or a narrow phone), so a cut label reads as "scroll for more". Put it
 * before horizontalScroll([state]) so the fade stays on the visible edges; mirrors in Arabic.
 */
fun androidx.compose.ui.Modifier.edgeFade(state: androidx.compose.foundation.ScrollState, width: Dp = 28.dp): androidx.compose.ui.Modifier = this
    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val w = width.toPx().coerceAtMost(size.width / 3)
        val rtl = layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl
        val clear = androidx.compose.ui.graphics.Color.Transparent
        val solid = androidx.compose.ui.graphics.Color.Black
        fun fade(left: Boolean) {
            val x0 = if (left) 0f else size.width - w
            drawRect(
                androidx.compose.ui.graphics.Brush.horizontalGradient(if (left) listOf(clear, solid) else listOf(solid, clear), startX = x0, endX = x0 + w),
                topLeft = androidx.compose.ui.geometry.Offset(x0, 0f),
                size = androidx.compose.ui.geometry.Size(w, size.height),
                blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
            )
        }
        // The scroll value counts from the start edge: the left in English, the right in Arabic.
        if (state.value > 0) fade(left = !rtl)
        if (state.value < state.maxValue) fade(left = rtl)
    }
