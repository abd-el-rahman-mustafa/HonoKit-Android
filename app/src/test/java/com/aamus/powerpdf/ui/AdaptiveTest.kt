package com.aamus.powerpdf.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Landscape, tablet and large-text layout rules (decisions §43). */
class AdaptiveTest {
    @Test fun railWhenHeightIsCompactOrWindowIsWide() {
        // A 1080 × 2400 phone at 420 dpi: 411 × 914 dp.
        assertFalse(Adaptive.useRail(411, 914 - 24))
        assertTrue(Adaptive.useRail(914, 411 - 24))
        // A small phone sideways, a tablet either way, a foldable opened in portrait.
        assertTrue(Adaptive.useRail(640, 360))
        assertTrue(Adaptive.useRail(1280, 800))
        assertTrue(Adaptive.useRail(900, 1200))
        assertFalse(Adaptive.useRail(700, 900))
    }

    @Test fun columnsFitTheWidth() {
        assertEquals(4, Adaptive.columns(380f, 88f))
        assertEquals(2, Adaptive.columns(380f, 150f))
        assertEquals(1, Adaptive.columns(100f, 150f))
        assertEquals(1, Adaptive.columns(0f, 88f))
        assertEquals(4, Adaptive.columns(2000f, 88f, max = 4))
    }

    @Test fun fourToolsNeverLeaveOneAlone() {
        assertEquals(4, Adaptive.evenColumns(380f, 88f, 4))
        // Three would fit: two rows of two instead of three and one.
        assertEquals(2, Adaptive.evenColumns(340f, 110f, 4))
        assertEquals(2, Adaptive.evenColumns(250f, 110f, 4))
        assertEquals(1, Adaptive.evenColumns(100f, 110f, 4))
    }

    @Test fun cellsAreWiderThanTheWidestWord() {
        // Normal text: the usual 88 dp tile.
        assertEquals(88f, Adaptive.minToolCell(60f), 0.01f)
        // 200 %: "background" measured at 118 dp needs a 134 dp tile.
        assertEquals(134f, Adaptive.minToolCell(118f), 0.01f)
    }

    @Test fun homeRowKeepsFourAcrossAtNormalText() {
        // The owner's phone at display size "large": sw375, 343 dp inside the card. "Watermark"
        // in the tile's 12 sp label is about 63 dp: four across (it was two rows of two).
        assertEquals(4, Adaptive.rowColumns(343f, 63f, 4))
        // A 360 dp phone in Arabic, a long word of 70 dp: still four.
        assertEquals(4, Adaptive.rowColumns(328f, 70f, 4))
        // 200 %: the word doubles, two rows of two (never three and one).
        assertEquals(2, Adaptive.rowColumns(343f, 126f, 4))
        assertEquals(1, Adaptive.rowColumns(160f, 160f, 4))
    }

    @Test fun hubGridIsFourAcrossOnA375Phone() {
        // 375 dp less the grid's 24 dp padding, the old 88 dp floor gave three columns.
        assertEquals(4, Adaptive.columns(351f, Adaptive.minToolCell(63f, Adaptive.HUB_PADDING, Adaptive.HUB_FLOOR)))
        assertEquals(4, Adaptive.columns(336f, Adaptive.minToolCell(63f, Adaptive.HUB_PADDING, Adaptive.HUB_FLOOR)))
    }

    @Test fun wordsSplitOnSpacesOnly() {
        assertEquals(listOf("Fill", "&", "Sign", "Combine", "files"), Adaptive.words(listOf("Fill & Sign", "Combine files")))
        assertEquals(listOf("إزالة", "الخلفية"), Adaptive.words(listOf("إزالة الخلفية")))
        assertEquals(emptyList<String>(), Adaptive.words(listOf("  ")))
    }
}
