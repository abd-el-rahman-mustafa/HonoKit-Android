package com.aamus.powerpdf.update

import com.aamus.powerpdf.BuildConfig
import org.json.JSONArray

/**
 * Where updates come from (decisions §36, §55): the releases of the app's own public repository,
 * HonoKit-Android (until 0.10.3 the shared HonoKit-Releases). Releases are tagged `android-vX.Y.Z`;
 * the phone lists the releases and picks the newest of its own tags, so the "Latest" mark does not
 * matter here.
 */
object UpdateFeed {
    const val REPO = com.aamus.powerpdf.ProjectLinks.RELEASES_REPO
    const val TAG_PREFIX = "android-v"
    const val API = "https://api.github.com/repos/$REPO/releases?per_page=30"

    /** A rehearsal build's feed on this PC (BuildConfig.UPDATE_FEED, docs/testing.md); else GitHub. */
    val feed: String get() = BuildConfig.UPDATE_FEED.ifEmpty { API }
    val rehearsal: Boolean get() = BuildConfig.UPDATE_FEED.isNotEmpty()
    const val PAGE = com.aamus.powerpdf.ProjectLinks.RELEASES

    data class Release(
        val version: String,
        val tag: String,
        val apkUrl: String,
        val apkName: String,
        val size: Long,
        /** Lower-case hex SHA-256 from GitHub's asset digest, when GitHub gives one. */
        val sha256: String?,
        val page: String,
    )

    /** "android-v0.6.0" → "0.6.0"; null for any other tag (the desktop's `v0.17.0` included). */
    fun versionOf(tag: String): String? =
        tag.takeIf { it.startsWith(TAG_PREFIX) }?.removePrefix(TAG_PREFIX)?.takeIf { parts(it) != null }

    /** True when [candidate] is a later version than [current] ("0.10.0" > "0.9.3"; "-dev" ignored). */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parts(candidate) ?: return false
        val b = parts(current.substringBefore('-')) ?: return true
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parts(version: String): List<Int>? =
        version.split('.').map { it.toIntOrNull() ?: return null }.takeIf { it.isNotEmpty() && it.size <= 4 }

    /** The newest published Android release with an APK, from GitHub's release list. */
    fun newest(json: String, allowHttp: Boolean = false): Release? {
        val list = JSONArray(json)
        var best: Release? = null
        for (i in 0 until list.length()) {
            val r = list.getJSONObject(i)
            if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue
            val tag = r.optString("tag_name")
            val version = versionOf(tag) ?: continue
            val assets = r.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val a = assets.getJSONObject(j)
                val name = a.optString("name")
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                val digest = a.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase()
                val release = Release(version, tag, a.optString("browser_download_url"), name, a.optLong("size"), digest, r.optString("html_url", PAGE))
                if ((release.apkUrl.startsWith("https://") || (allowHttp && release.apkUrl.startsWith("http://"))) && (best == null || isNewer(version, best.version))) best = release
                break
            }
        }
        return best
    }
}
