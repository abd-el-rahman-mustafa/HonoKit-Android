package com.aamus.powerpdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings › About's links (decisions §54). */
class ProjectLinksTest {
    @Test
    fun theReportLinkFillsTheFormFieldsAndNothingElse() {
        val url = ProjectLinks.reportProblem("0.10.4", "Android 15 (SDK 35)", "Google Pixel 8", "ar")
        assertEquals(
            "https://github.com/abd-el-rahman-mustafa/HonoKit-Android/issues/new?template=bug_report.yml" +
                "&version=0.10.4&android=Android%2015%20%28SDK%2035%29&device=Google%20Pixel%208&language=ar",
            url,
        )
        val names = url.substringAfter('?').split('&').map { it.substringBefore('=') }
        assertEquals(listOf("template", "version", "android", "device", "language"), names)
    }

    @Test
    fun valuesAreEncodedSoTheyCannotAddFields() {
        val url = ProjectLinks.reportProblem("1.0&title=x", "A+B", "Maker Model#1", "ar-EG (system)")
        assertFalse(url.contains("&title="))
        assertTrue(url.contains("version=1.0%26title%3Dx"))
        assertTrue(url.contains("android=A%2BB"))
        assertTrue(url.contains("device=Maker%20Model%231"))
        assertTrue(url.contains("language=ar-EG%20%28system%29"))
    }

    @Test
    fun theSourceAndReleasesPointToThePublicRepositories() {
        assertEquals("https://github.com/abd-el-rahman-mustafa/HonoKit-Android", ProjectLinks.SOURCE)
        assertEquals("https://github.com/abd-el-rahman-mustafa/HonoKit-Android/releases", ProjectLinks.RELEASES)
    }
}
