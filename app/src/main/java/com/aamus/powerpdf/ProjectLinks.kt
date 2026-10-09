package com.aamus.powerpdf

import java.net.URLEncoder

/**
 * Every web address HonoKit knows of its own (decisions §54, §36): the source code, the issue form
 * and the releases the updater reads. Opened only when the person taps Settings › About › Source
 * code or Report a problem; nothing is sent by the app itself.
 */
object ProjectLinks {
    const val OWNER = "abd-el-rahman-mustafa"
    const val SOURCE_REPO = "$OWNER/HonoKit-Android"
    /** Releases live next to the source since 0.10.4 (decisions §55); 0.10.4 itself was also put in the
     *  old shared HonoKit-Releases so copies that still read it find it and move here. */
    const val RELEASES_REPO = SOURCE_REPO

    const val SOURCE = "https://github.com/$SOURCE_REPO"
    const val ISSUES = "$SOURCE/issues"
    const val RELEASES = "https://github.com/$RELEASES_REPO/releases"

    /** The issue form .github/ISSUE_TEMPLATE/bug_report.yml; its field ids match the query names. */
    const val BUG_TEMPLATE = "bug_report.yml"

    /**
     * A new issue from the bug-report form with the fields the app knows filled in: the version, the
     * Android release, the phone's maker and model, and the app's language. Nothing else.
     */
    fun reportProblem(version: String, android: String, device: String, language: String): String =
        "$ISSUES/new?" + listOf(
            "template" to BUG_TEMPLATE,
            "version" to version,
            "android" to android,
            "device" to device,
            "language" to language,
        ).joinToString("&") { (k, v) -> "$k=${encode(v)}" }

    /** Percent-encoding for a query value (a space is %20, not +). */
    fun encode(value: String): String =
        URLEncoder.encode(value.trim(), "UTF-8").replace("+", "%20")
}
