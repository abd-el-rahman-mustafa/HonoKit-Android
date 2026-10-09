package com.aamus.powerpdf

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.aamus.powerpdf.data.ThemeMode
import com.aamus.powerpdf.data.Files
import com.aamus.powerpdf.data.RecentsStore
import com.aamus.powerpdf.data.SettingsStore
import com.aamus.powerpdf.data.SignatureStore
import com.aamus.powerpdf.engine.StampFonts
import com.aamus.powerpdf.update.Updates

class PowerPdfApp : Application() {
    lateinit var settings: SettingsStore
        private set
    lateinit var recents: RecentsStore
        private set
    lateinit var signatures: SignatureStore
        private set
    /** Home's pinned tools (decisions §44). */
    lateinit var pins: com.aamus.powerpdf.data.PinsStore
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsStore(this)
        recents = RecentsStore(this)
        signatures = SignatureStore(this)
        pins = com.aamus.powerpdf.data.PinsStore(this, ToolId.entries.map { it.name }.toSet())
        Files.cleanUp(this)
        StampFonts.init(this)
        Updates.init(this)
        applyTheme(settings.value.theme)
    }

    companion object {
        lateinit var instance: PowerPdfApp
            private set

        /** The window, dialogs and system bars follow the app's theme, not only Compose. */
        fun applyTheme(mode: ThemeMode) {
            AppCompatDelegate.setDefaultNightMode(
                when (mode) {
                    ThemeMode.System -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    ThemeMode.Light -> AppCompatDelegate.MODE_NIGHT_NO
                    ThemeMode.Dark -> AppCompatDelegate.MODE_NIGHT_YES
                }
            )
        }
    }
}
