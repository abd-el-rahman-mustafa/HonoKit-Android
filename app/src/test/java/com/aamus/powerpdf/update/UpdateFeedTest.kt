package com.aamus.powerpdf.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateFeedTest {
    @Test
    fun versions() {
        assertEquals("0.6.0", UpdateFeed.versionOf("android-v0.6.0"))
        assertNull(UpdateFeed.versionOf("v0.17.0"))
        assertNull(UpdateFeed.versionOf("android-vbeta"))
        assertTrue(UpdateFeed.isNewer("0.10.0", "0.9.3"))
        assertTrue(UpdateFeed.isNewer("0.6.0", "0.5.0-dev"))
        assertTrue(UpdateFeed.isNewer("1.0", "0.99.99"))
        assertFalse(UpdateFeed.isNewer("0.5.0", "0.5.0"))
        assertFalse(UpdateFeed.isNewer("0.5", "0.5.0"))
        assertFalse(UpdateFeed.isNewer("0.4.9", "0.5.0"))
    }

    private fun release(tag: String, apk: String?, draft: Boolean = false, pre: Boolean = false, digest: String? = null) = """
        {"tag_name":"$tag","draft":$draft,"prerelease":$pre,"html_url":"https://github.com/x/y/releases/tag/$tag",
         "assets":[${apk?.let { """{"name":"$it","size":123,"browser_download_url":"https://github.com/x/y/releases/download/$tag/$it"${digest?.let { d -> ""","digest":"$d"""" } ?: ""}}""" } ?: ""}]}
    """.trimIndent()

    @Test
    fun picksTheNewestAndroidReleaseOnly() {
        // GitHub lists newest first; the desktop's release sits among them and is marked Latest.
        val json = "[" + listOf(
            release("android-v0.8.0", "HonoKit-0.8.0.apk", draft = true),
            release("android-v0.7.0", "HonoKit-0.7.0.apk", pre = true),
            release("v0.17.0", "HonoKit-Setup-0.17.0.exe"),
            release("android-v0.5.0", "HonoKit-0.5.0.apk"),
            release("android-v0.6.0", "HonoKit-0.6.0.apk", digest = "sha256:ABCDEF"),
            release("android-v0.9.0", null),
        ).joinToString(",") + "]"
        val r = UpdateFeed.newest(json)!!
        assertEquals("0.6.0", r.version)
        assertEquals("android-v0.6.0", r.tag)
        assertEquals("HonoKit-0.6.0.apk", r.apkName)
        assertEquals("abcdef", r.sha256)
        assertEquals(123L, r.size)
    }

    @Test
    fun noAndroidRelease() {
        assertNull(UpdateFeed.newest("[" + release("v0.17.0", "HonoKit-Setup-0.17.0.exe") + "]"))
        assertNull(UpdateFeed.newest("[]"))
    }
}
