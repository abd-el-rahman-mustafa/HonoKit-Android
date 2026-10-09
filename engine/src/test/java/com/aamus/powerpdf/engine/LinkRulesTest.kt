package com.aamus.powerpdf.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which links in a document may open, and how they are shown (decisions §51, desktop links.ts). */
class LinkRulesTest {
    private fun ok(raw: String): SafeLink {
        val l = LinkRules.safeLink(raw)
        assertNotNull("should open: $raw", l)
        return l!!
    }

    private fun refused(raw: String) = assertNull("must not open: $raw", LinkRules.safeLink(raw))

    @Test
    fun webPagesOpen() {
        val l = ok("https://www.example.com/path?q=1#top")
        assertEquals("https://www.example.com/path?q=1#top", l.url)
        assertEquals("www.example.com", l.host)
        assertEquals(SafeLink.Kind.Web, l.kind)
        assertEquals("www.example.com", l.url.substring(l.hostStart, l.hostStart + l.host.length))
        assertEquals("http://example.org", ok("http://example.org").url)
        assertEquals("example.org", ok("  HTTP://Example.ORG/A  ").host)
        assertEquals("https://example.com:8443/x", ok("https://example.com:8443/x").url)
        assertEquals("[::1]", ok("http://[::1]:8080/").host)
        assertEquals("https://example.com/a%20b", ok("https://example.com/a b").url)
    }

    @Test
    fun wwwWithoutSchemeIsAWebPage() {
        val l = ok("www.example.com/page")
        assertEquals("https://www.example.com/page", l.url)
        assertEquals("www.example.com", l.host)
        assertEquals("https://www.example.com", ok("WWW.Example.com").url)
        assertEquals("www.example.com", ok("WWW.Example.com").host)
        refused("example.com")
        refused("www.example")
    }

    @Test
    fun mupdfsFileFormOfWwwIsTheWebPage() {
        // MuPDF turns the relative address "www.example.org/page" into "file://www.example.org/page".
        val l = ok("file://www.example.org/page")
        assertEquals("https://www.example.org/page", l.url)
        assertEquals("www.example.org", l.host)
        refused("file:///www.example.org/page")
        refused("file://server.example.org/share")
        refused("file://www.bank.example@evil.example/")
    }

    @Test
    fun userInfoIsRefused() {
        refused("https://bank.example@evil.example")
        refused("https://bank.example:secret@evil.example/login")
        refused("http://user@example.com")
        refused("www.bank.example@evil.example")
        refused("https://evil.example\\@bank.example")
    }

    @Test
    fun otherSchemesNeverOpen() {
        refused("javascript:alert(1)")
        refused("JavaScript:alert(1)")
        refused("file:///sdcard/Download/x.pdf")
        refused("content://com.example.provider/secret")
        refused("intent://scan/#Intent;scheme=zxing;package=com.example;end")
        refused("market://details?id=com.example")
        refused("tel:+15551234567")
        refused("sms:+15551234567")
        refused("data:text/html,<script>alert(1)</script>")
        refused("ftp://example.com/file")
        refused("../other.pdf")
        refused("other.pdf#page=2")
        refused("")
        refused("   ")
    }

    @Test
    fun malformedWebAddressesAreRefused() {
        refused("https:example.com")
        refused("https://")
        refused("https:///path")
        refused("https://exa mple.com")
        refused("https://%65xample.com")
        refused("https://example.com:99999/")
        refused("https://example.com:80a/")
        refused("https://exa\tmple.com")
        refused("https://[::1/")
    }

    @Test
    fun tooLongIsRefused() {
        val long = "https://example.com/" + "a".repeat(LinkRules.MAX_LENGTH)
        refused(long)
        ok("https://example.com/" + "a".repeat(LinkRules.MAX_LENGTH - 30))
    }

    @Test
    fun lookAlikeLettersShowAsPunycode() {
        // "аpple.com" with a Cyrillic а (U+0430) is not apple.com.
        val cyrillic = String(Character.toChars(0x0430)) + "pple.com"
        val l = ok("https://$cyrillic/login")
        assertTrue(l.host, l.host.startsWith("xn--"))
        assertEquals("https://${l.host}/login", l.url)
        // Arabic domain names too.
        val arabic = String(intArrayOf(0x0645, 0x062B, 0x0627, 0x0644), 0, 4) + ".example"
        assertTrue(ok("https://$arabic").host.startsWith("xn--"))
    }

    @Test
    fun emailUsesTheDomain() {
        val l = ok("mailto:Someone@Example.COM?subject=Hello%20there")
        assertEquals(SafeLink.Kind.Email, l.kind)
        assertEquals("example.com", l.host)
        assertEquals("mailto:Someone@example.com?subject=Hello%20there", l.url)
        assertEquals("example.com", l.url.substring(l.hostStart, l.hostStart + l.host.length))
        assertEquals("example.org", ok("MAILTO:a@example.org,b@other.example").host)
        assertEquals("example.com", ok("mailto:first%2Elast@example.com").host)
        refused("mailto:")
        refused("mailto:a@example.com?attach=/sdcard/Download/secret.pdf")
        refused("mailto:a@example.com?subject=x&Attachment=file:///data/x")
        refused("mailto:nobody")
        refused("mailto:@example.com")
        refused("mailto:someone@")
        refused("mailto:some one@example.com")
        refused("mailto:a@exa mple.com")
        val cyrillic = String(Character.toChars(0x0430)) + "pple.com"
        assertTrue(ok("mailto:x@$cyrillic").host.startsWith("xn--"))
    }

    @Test
    fun trustedSitesKeepTheNewestTwoHundred() {
        var list = emptyList<String>()
        for (i in 1..205) list = LinkRules.trust(list, "site$i.example")
        assertEquals(LinkRules.MAX_TRUSTED_HOSTS, list.size)
        assertEquals("site6.example", list.first())
        assertEquals("site205.example", list.last())
        list = LinkRules.trust(list, "site6.example")
        assertEquals(200, list.size)
        assertEquals("site6.example", list.last())
        assertEquals(1, list.count { it == "site6.example" })
    }
}
