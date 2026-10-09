package com.aamus.powerpdf.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { System, Light, Dark }

/** What Save does (desktop decisions §39): a copy by default, or over the file when Android allows it. */
enum class SaveMode { Copy, Overwrite }

/** Where copies go: Downloads › HonoPDF without asking, or a folder picked each time. */
enum class CopyPlace { Downloads, Ask }

data class AppSettings(
    val theme: ThemeMode = ThemeMode.System,
    val saveMode: SaveMode = SaveMode.Copy,
    val copyPlace: CopyPlace = CopyPlace.Downloads,
    /** Name written on comments and notes; empty = "HonoPDF user" equivalent in the UI language. */
    val author: String = "",
    /** Viewer pages with light text on a dark page. */
    val nightPages: Boolean = false,
    /** Keep the screen on while a document is open. */
    val keepScreenOn: Boolean = false,
    /** Page numbers and similar written with Arabic-Indic digits (٠١٢) by default in Arabic. */
    val arabicIndicDigits: Boolean = false,
    /** Ask GitHub once a day whether a newer version exists (decisions §36). */
    val checkUpdates: Boolean = true,
    /** Sites (and email domains) whose links open without asking, newest last (decisions §51). */
    val trustedLinkHosts: List<String> = emptyList(),
)

/** The app's settings, kept in this phone's private storage only. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val flow: StateFlow<AppSettings> = state.asStateFlow()
    val value: AppSettings get() = state.value

    private fun read() = AppSettings(
        theme = enumOr(prefs.getString("theme", null), ThemeMode.System),
        saveMode = enumOr(prefs.getString("saveMode", null), SaveMode.Copy),
        copyPlace = enumOr(prefs.getString("copyPlace", null), CopyPlace.Downloads),
        author = prefs.getString("author", "") ?: "",
        nightPages = prefs.getBoolean("nightPages", false),
        keepScreenOn = prefs.getBoolean("keepScreenOn", false),
        arabicIndicDigits = prefs.getBoolean("arabicIndicDigits", false),
        checkUpdates = prefs.getBoolean("checkUpdates", true),
        trustedLinkHosts = (prefs.getString("trustedLinkHosts", "") ?: "").split('\n').map { it.trim() }.filter { it.isNotEmpty() && it.length <= 253 }.distinct().takeLast(com.aamus.powerpdf.engine.LinkRules.MAX_TRUSTED_HOSTS),
    )

    fun update(change: (AppSettings) -> AppSettings) {
        val next = change(state.value)
        prefs.edit()
            .putString("theme", next.theme.name)
            .putString("saveMode", next.saveMode.name)
            .putString("copyPlace", next.copyPlace.name)
            .putString("author", next.author)
            .putBoolean("nightPages", next.nightPages)
            .putBoolean("keepScreenOn", next.keepScreenOn)
            .putBoolean("arabicIndicDigits", next.arabicIndicDigits)
            .putBoolean("checkUpdates", next.checkUpdates)
            .putString("trustedLinkHosts", next.trustedLinkHosts.joinToString("\n"))
            .apply()
        state.value = next
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback
}
