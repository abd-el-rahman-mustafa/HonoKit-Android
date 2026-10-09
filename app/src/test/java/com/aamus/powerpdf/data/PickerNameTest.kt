package com.aamus.powerpdf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** Names for pictures from the photo picker (UX review finding 12). */
class PickerNameTest {
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        Calendar.getInstance().apply { clear(); set(y, m - 1, d, h, min) }.timeInMillis

    @Test fun recognisesPickerUris() {
        assertTrue(PickerName.isPicker("media", "/picker/0/com.android.providers.media.photopicker/media/1000000063"))
        assertTrue(PickerName.isPicker("media", "/picker_get_content/0/media/1000000063"))
        assertFalse(PickerName.isPicker("media", "/external/images/media/1000000063"))
        assertFalse(PickerName.isPicker("com.android.providers.downloads.documents", "/document/msf:42"))
        assertFalse(PickerName.isPicker("media", null))
    }

    @Test fun numberOnlyNames() {
        assertTrue(PickerName.isNumberOnly("1000000063.jpg"))
        assertTrue(PickerName.isNumberOnly("1000000063"))
        assertFalse(PickerName.isNumberOnly("IMG_20261004_112600.jpg"))
        assertFalse(PickerName.isNumberOnly("2026.jpg".replace("2026", "20")))
        assertFalse(PickerName.isNumberOnly("holiday.png"))
    }

    @Test fun aPickerNumberBecomesPhotoAndTheDateTaken() {
        val taken = at(2026, 10, 4, 11, 26)
        assertEquals("Photo 2026-10-04 11.26.jpg", PickerName.nameFor("1000000063.jpg", picker = true, takenMillis = taken, mime = "image/jpeg", word = "Photo"))
        assertEquals("صورة 2026-10-04 11.26.jpg", PickerName.nameFor("1000000063.jpg", picker = true, takenMillis = taken, mime = "image/jpeg", word = "صورة"))
    }

    @Test fun theExtensionComesFromTheTypeWhenTheNameHasNone() {
        val taken = at(2026, 1, 2, 3, 4)
        assertEquals("Photo 2026-01-02 03.04.webp", PickerName.nameFor("1000000063", picker = true, takenMillis = taken, mime = "image/webp", word = "Photo"))
    }

    @Test fun noDateTakenUsesNow() {
        val now = at(2026, 10, 4, 9, 5)
        assertEquals("Photo 2026-10-04 09.05.png", PickerName.nameFor("1000000077.png", picker = true, takenMillis = null, mime = null, word = "Photo", nowMillis = now))
    }

    @Test fun realNamesAndOtherProvidersAreKept() {
        assertEquals("holiday.jpg", PickerName.nameFor("holiday.jpg", picker = true, takenMillis = 1L, mime = "image/jpeg", word = "Photo"))
        assertEquals("1000000063.jpg", PickerName.nameFor("1000000063.jpg", picker = false, takenMillis = 1L, mime = "image/jpeg", word = "Photo"))
    }
}
