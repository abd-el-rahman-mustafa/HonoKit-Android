package com.aamus.powerpdf.engine

import java.net.IDN

/**
 * Web links in a document (decisions §51, as the desktop's src/shared/links.ts, its §81). A PDF can
 * point anywhere, so a link opens only after the person says yes (or has said "always" for that
 * site), and only these kinds: a web page (http, https) or an email (mailto). Everything else —
 * file:, content:, intent:, javascript:, market:, tel:, sms: and the rest — never opens. (A link
 * written "www.example.com" comes back from MuPDF as "file://www.example.com" and is that web page.)
 * Plain Kotlin (no Android types), so the rules are tested on the JVM.
 */
data class SafeLink(
    /** The address as it will be opened (the site's name in ASCII). */
    val url: String,
    /**
     * What the person is asked about and what "always allow" remembers: the site's name in plain
     * ASCII (look-alike letters show as xn--…), or the email's domain.
     */
    val host: String,
    val kind: Kind,
    /** Where [host] starts in [url] (shown in bold). */
    val hostStart: Int,
) {
    enum class Kind { Web, Email }
}

object LinkRules {
    const val MAX_LENGTH = 2048

    /** Sites the person allowed to open without asking. */
    const val MAX_TRUSTED_HOSTS = 200

    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*):")
    // Many PDFs write "www.example.com" with no scheme; Acrobat opens those as web pages.
    private val BARE_WWW = Regex("^www\\.[^\\s/]+\\.[^\\s/]+", RegexOption.IGNORE_CASE)
    private const val FILE_PREFIX = "file://"
    private val HOST_CHARS =Regex("^[a-z0-9_\\-]+(\\.[a-z0-9_\\-]+)*\\.?$")

    /** The link as it may be opened, or null when it must never open. */
    fun safeLink(raw: String): SafeLink? {
        val text = raw.trim()
        if (text.isEmpty() || text.length > MAX_LENGTH) return null
        // Control characters and backslashes read differently by different programs: refused.
        if (text.any { it.code < 0x20 || it.code == 0x7F || it == '\\' }) return null
        if (BARE_WWW.containsMatchIn(text)) return web("https", "//$text")
        // MuPDF hands a link written "www.example.com" back as "file://www.example.com" (a relative
        // address made into a file one): the web page it means, never a file.
        if (text.startsWith(FILE_PREFIX, ignoreCase = true) && BARE_WWW.containsMatchIn(text.substring(FILE_PREFIX.length))) {
            return web("https", "//" + text.substring(FILE_PREFIX.length))
        }
        val scheme = SCHEME.find(text)?.groupValues?.get(1)?.lowercase()
        return when (scheme) {
            "http", "https" -> web(scheme, text.substring(scheme.length + 1))
            "mailto" -> email(text.substring("mailto:".length))
            else -> null
        }
    }

    /** [rest] is what follows "http:" ("//host/path…"). */
    private fun web(scheme: String, rest: String): SafeLink? {
        if (!rest.startsWith("//")) return null
        val after = rest.substring(2)
        val end = after.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) after.length else it }
        val authority = after.substring(0, end)
        val tail = after.substring(end)
        // "https://bank.example@evil.example" shows one site and opens another: refused.
        if (authority.isEmpty() || '@' in authority) return null
        var host: String
        var port = ""
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            host = authority.substring(0, close + 1).lowercase()
            val inside = host.substring(1, host.length - 1)
            if (inside.isEmpty() || !inside.all { it in '0'..'9' || it in 'a'..'f' || it == ':' || it == '.' }) return null
            val more = authority.substring(close + 1)
            if (more.isNotEmpty()) {
                if (!more.startsWith(":")) return null
                port = more.substring(1)
            }
        } else {
            val colon = authority.indexOf(':')
            host = if (colon < 0) authority else authority.substring(0, colon)
            if (colon >= 0) port = authority.substring(colon + 1)
            host = asciiHost(host) ?: return null
        }
        if (port.isNotEmpty() && (port.length > 5 || !port.all { it in '0'..'9' } || port.toInt() > 65535)) return null
        if (host.isEmpty()) return null
        val prefix = "$scheme://"
        val url = prefix + host + (if (port.isNotEmpty()) ":$port" else "") + encodeSpaces(tail)
        return SafeLink(url, host.trimEnd('.'), SafeLink.Kind.Web, prefix.length)
    }

    /** [rest] is what follows "mailto:" ("name@example.com?subject=…"). */
    private fun email(rest: String): SafeLink? {
        val stop = rest.indexOfFirst { it == ',' || it == '?' }.let { if (it < 0) rest.length else it }
        val first = percentDecode(rest.substring(0, stop)) ?: return null
        val at = first.lastIndexOf('@')
        if (at <= 0 || at == first.length - 1) return null
        val local = first.substring(0, at)
        if (local.any { it.isWhitespace() }) return null
        val domain = asciiHost(first.substring(at + 1)) ?: return null
        if (domain.isEmpty()) return null
        // Some mail apps take ?attach=/sdcard/… and attach that file: a document must not pick files
        // to send (desktop links.ts the same).
        val query = rest.substringAfter('?', "")
        if (query.split('&').any { it.substringBefore('=').trim().lowercase().let { k -> (percentDecode(k) ?: k).startsWith("attach") } }) return null
        val prefix = "mailto:" + encodeLocal(local) + "@"
        return SafeLink(prefix + domain + encodeSpaces(rest.substring(stop)), domain.trimEnd('.'), SafeLink.Kind.Email, prefix.length)
    }

    /** A host name in lower-case ASCII (IDNA: "аpple.com" in Cyrillic → "xn--pple-43d.com"), or null. */
    private fun asciiHost(name: String): String? {
        if (name.isEmpty() || name.any { it.isWhitespace() || it == '%' }) return null
        val ascii = try {
            IDN.toASCII(name, IDN.ALLOW_UNASSIGNED).lowercase()
        } catch (_: Exception) {
            return null
        }
        return if (HOST_CHARS.matches(ascii)) ascii else null
    }

    private fun encodeSpaces(s: String) = s.replace(" ", "%20")

    /** The part before "@" written back for a mailto: address (UTF-8, percent-encoded where needed). */
    private fun encodeLocal(s: String): String = buildString {
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val ch = b.toInt() and 0xFF
            val c = ch.toChar()
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in ".-_~!$'*+=^`{|}") append(c)
            else append('%').append("0123456789ABCDEF"[ch shr 4]).append("0123456789ABCDEF"[ch and 15])
        }
    }

    private fun percentDecode(s: String): String? {
        if ('%' !in s) return s
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                if (i + 2 >= s.length) return null
                val v = s.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                out.write(v)
                i += 3
            } else {
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return out.toString("UTF-8")
    }

    /** The trusted sites after "Always allow" for [host]: newest last, at most [MAX_TRUSTED_HOSTS]. */
    fun trust(list: List<String>, host: String): List<String> = (list.filter { it != host } + host).takeLast(MAX_TRUSTED_HOSTS)
}
