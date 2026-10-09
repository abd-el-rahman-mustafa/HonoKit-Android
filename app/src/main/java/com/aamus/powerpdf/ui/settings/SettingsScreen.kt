package com.aamus.powerpdf.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aamus.powerpdf.AppViewModel
import com.aamus.powerpdf.BuildConfig
import com.aamus.powerpdf.ProjectLinks
import com.aamus.powerpdf.R
import com.aamus.powerpdf.Screen
import com.aamus.powerpdf.data.CopyPlace
import com.aamus.powerpdf.data.SaveMode
import com.aamus.powerpdf.data.ThemeMode
import com.aamus.powerpdf.ui.BottomSpace
import com.aamus.powerpdf.ui.DialogButton
import com.aamus.powerpdf.ui.Field
import com.aamus.powerpdf.ui.Group
import com.aamus.powerpdf.ui.GroupDivider
import com.aamus.powerpdf.ui.IconAction
import com.aamus.powerpdf.ui.ListRow
import com.aamus.powerpdf.ui.LocalModel
import com.aamus.powerpdf.ui.RadioRow
import com.aamus.powerpdf.ui.SectionTitle
import com.aamus.powerpdf.ui.Segmented
import com.aamus.powerpdf.ui.SwitchRow
import com.aamus.powerpdf.ui.Sym
import com.aamus.powerpdf.ui.theme.Power
import com.aamus.powerpdf.update.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
private fun ScreenBar(title: String) {
    val model = LocalModel.current
    val c = Power.colors
    Column(Modifier.fillMaxWidth().background(c.surface).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(R.drawable.ic_arrow_back, stringResource(R.string.back), { model.pop() })
            Text(title, style = MaterialTheme.typography.titleLarge, color = c.ink)
        }
        HorizontalDivider(color = c.line)
    }
}

@Composable
fun SettingsScreen() {
    val model = LocalModel.current
    val prefs by model.settings.flow.collectAsStateWithLifecycle()
    val update by Updates.flow.collectAsStateWithLifecycle()
    val c = Power.colors
    val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    val lang = when {
        current.startsWith("ar") -> "ar"
        current.startsWith("en") -> "en"
        else -> ""
    }
    var authorOpen by remember { mutableStateOf(false) }
    var clearOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        ScreenBar(stringResource(R.string.settings_title))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionTitle(stringResource(R.string.section_general))
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.language), style = MaterialTheme.typography.labelLarge, color = c.muted)
                Segmented(
                    listOf("" to stringResource(R.string.lang_system), "en" to stringResource(R.string.lang_en), "ar" to stringResource(R.string.lang_ar)),
                    lang,
                    { tag -> AppCompatDelegate.setApplicationLocales(if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)) },
                )
                Text(stringResource(R.string.theme), style = MaterialTheme.typography.labelLarge, color = c.muted)
                Segmented(
                    listOf(ThemeMode.System to stringResource(R.string.theme_system), ThemeMode.Light to stringResource(R.string.theme_light), ThemeMode.Dark to stringResource(R.string.theme_dark)),
                    prefs.theme,
                    { t ->
                        model.settings.update { it.copy(theme = t) }
                        com.aamus.powerpdf.PowerPdfApp.applyTheme(t)
                    },
                )
            }

            SectionTitle(stringResource(R.string.section_saving))
            Group {
                RadioRow(stringResource(R.string.save_mode_copy), prefs.saveMode == SaveMode.Copy, { model.settings.update { it.copy(saveMode = SaveMode.Copy) } }, stringResource(R.string.save_mode_copy_desc))
                GroupDivider()
                RadioRow(stringResource(R.string.save_mode_over), prefs.saveMode == SaveMode.Overwrite, { model.settings.update { it.copy(saveMode = SaveMode.Overwrite) } }, stringResource(R.string.save_mode_over_desc))
            }
            Spacer(Modifier.height(12.dp))
            Group {
                Text(stringResource(R.string.copy_place), style = MaterialTheme.typography.labelLarge, color = c.muted, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
                RadioRow(stringResource(R.string.downloads_place), prefs.copyPlace == CopyPlace.Downloads, { model.settings.update { it.copy(copyPlace = CopyPlace.Downloads) } })
                GroupDivider()
                RadioRow(stringResource(R.string.copy_place_ask), prefs.copyPlace == CopyPlace.Ask, { model.settings.update { it.copy(copyPlace = CopyPlace.Ask) } })
            }

            SectionTitle(stringResource(R.string.section_viewer))
            Group {
                SwitchRow(stringResource(R.string.night_pages), prefs.nightPages, { v -> model.settings.update { it.copy(nightPages = v) } }, stringResource(R.string.night_pages_desc), icon = R.drawable.ic_dark_mode)
                GroupDivider()
                SwitchRow(stringResource(R.string.keep_screen_on), prefs.keepScreenOn, { v -> model.settings.update { it.copy(keepScreenOn = v) } }, stringResource(R.string.keep_screen_on_desc), icon = R.drawable.ic_brightness_6)
                GroupDivider()
                LinksRow(prefs.trustedLinkHosts, onForget = {
                    model.settings.update { it.copy(trustedLinkHosts = emptyList()) }
                    model.say(model.str(R.string.settings_links_forgotten))
                })
            }

            SectionTitle(stringResource(R.string.section_comments))
            Group {
                ListRow(
                    stringResource(R.string.author),
                    description = prefs.author.ifBlank { stringResource(R.string.default_author) },
                    icon = R.drawable.ic_edit,
                    onClick = { authorOpen = true },
                )
                GroupDivider()
                SwitchRow(stringResource(R.string.arabic_digits), prefs.arabicIndicDigits, { v -> model.settings.update { it.copy(arabicIndicDigits = v) } }, stringResource(R.string.arabic_digits_desc), icon = R.drawable.ic_format_list_numbered)
            }

            SectionTitle(stringResource(R.string.recent_files_setting))
            Group {
                ListRow(stringResource(R.string.clear_recent), description = stringResource(R.string.clear_recent_desc), icon = R.drawable.ic_history, onClick = { clearOpen = true })
            }

            SectionTitle(stringResource(R.string.section_updates))
            Group {
                SwitchRow(stringResource(R.string.update_auto), prefs.checkUpdates, { v -> model.settings.update { it.copy(checkUpdates = v) } }, stringResource(R.string.update_auto_desc), icon = R.drawable.ic_update)
                GroupDivider()
                ListRow(
                    stringResource(R.string.update_check_now),
                    description = when (val u = update) {
                        Updates.State.Idle -> null
                        Updates.State.Checking -> stringResource(R.string.update_checking)
                        Updates.State.UpToDate -> stringResource(R.string.update_none)
                        is Updates.State.Available -> stringResource(R.string.update_available_line, u.release.version)
                        is Updates.State.Downloading -> stringResource(R.string.update_downloading_title) + " · " + stringResource(R.string.update_percent, u.percent)
                        is Updates.State.NeedsPermission -> stringResource(R.string.update_permission_title)
                        is Updates.State.Installing -> stringResource(R.string.update_installing)
                        is Updates.State.Failed -> u.message
                    },
                    icon = R.drawable.ic_download,
                    enabled = update !is Updates.State.Checking,
                    onClick = {
                        val u = update
                        // Something in progress or found: show it; otherwise ask GitHub now.
                        if (u is Updates.State.Available || u is Updates.State.Downloading || u is Updates.State.NeedsPermission ||
                            u is Updates.State.Installing || (u is Updates.State.Failed && u.release != null)
                        ) Updates.dialog.value = true
                        else Updates.check(manual = true)
                    },
                )
            }

            SectionTitle(stringResource(R.string.section_about))
            Group {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.logo), null, Modifier.size(48.dp))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium, color = c.ink)
                        Text(stringResource(R.string.version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall, color = c.muted)
                    }
                }
                GroupDivider()
                ListRow(stringResource(R.string.privacy_line), icon = R.drawable.ic_lock, iconTint = c.ok)
                GroupDivider()
                ListRow(stringResource(R.string.licence_line), icon = R.drawable.ic_description)
                GroupDivider()
                ListRow(stringResource(R.string.licences), description = stringResource(R.string.licences_desc), icon = R.drawable.ic_info, onClick = { model.push(Screen.Licences) }) {
                    Sym(R.drawable.ic_chevron_right, null, tint = c.faint)
                }
                GroupDivider()
                // Source code and Report a problem (decisions §54): open GitHub in the browser, only on a tap.
                val context = LocalContext.current
                ListRow(stringResource(R.string.about_source), description = stringResource(R.string.about_source_desc), icon = R.drawable.ic_code, onClick = { openWeb(context, ProjectLinks.SOURCE, model) }) {
                    Sym(R.drawable.ic_open_in_new, null, tint = c.faint)
                }
                GroupDivider()
                ListRow(stringResource(R.string.about_report), description = stringResource(R.string.about_report_desc), icon = R.drawable.ic_bug_report, onClick = { openWeb(context, reportProblemUrl(context), model) }) {
                    Sym(R.drawable.ic_open_in_new, null, tint = c.faint)
                }
            }
            BottomSpace(32.dp)
        }
    }

    if (authorOpen) {
        var name by remember { mutableStateOf(prefs.author) }
        AlertDialog(
            onDismissRequest = { authorOpen = false },
            title = { Text(stringResource(R.string.author)) },
            text = { Field(name, { name = it.take(60) }, stringResource(R.string.author), placeholder = stringResource(R.string.default_author), error = null) },
            confirmButton = { DialogButton(stringResource(R.string.save), { model.settings.update { it.copy(author = name.trim()) }; authorOpen = false }) },
            dismissButton = { DialogButton(stringResource(R.string.cancel), { authorOpen = false }) },
            containerColor = c.surface,
        )
    }
    if (clearOpen) {
        AlertDialog(
            onDismissRequest = { clearOpen = false },
            title = { Text(stringResource(R.string.clear_recent)) },
            text = { Text(stringResource(R.string.clear_recent_confirm)) },
            confirmButton = {
                DialogButton(stringResource(R.string.clear), {
                    model.recents.clear()
                    clearOpen = false
                    model.say(model.str(R.string.cleared))
                }, danger = true)
            },
            dismissButton = { DialogButton(stringResource(R.string.cancel), { clearOpen = false }) },
            containerColor = c.surface,
        )
    }
}

/**
 * "Links in documents" (decisions §51, desktop Settings): HonoKit asks before a web or email link
 * opens; the sites allowed with "Always allow" are listed (newest first) with "Ask again for all".
 */
@Composable
private fun LinksRow(trusted: List<String>, onForget: () -> Unit) {
    val c = Power.colors
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = if (trusted.isEmpty()) 12.dp else 4.dp)) {
        Sym(R.drawable.ic_language, null, tint = c.muted, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_links), style = MaterialTheme.typography.bodyLarge, color = c.ink)
            val sites = trusted.asReversed().take(3).joinToString(", ") + if (trusted.size > 3) ", …" else ""
            Text(
                if (trusted.isEmpty()) stringResource(R.string.settings_links_ask)
                else stringResource(R.string.settings_links_trusted, trusted.size, com.aamus.powerpdf.ui.bidi(sites)),
                style = MaterialTheme.typography.bodySmall,
                color = c.muted,
            )
            if (trusted.isNotEmpty()) {
                androidx.compose.material3.TextButton(
                    onClick = onForget,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp, vertical = 8.dp),
                ) {
                    Text(stringResource(R.string.settings_links_forget), style = MaterialTheme.typography.labelLarge, color = c.accent)
                }
            }
        }
    }
}

/** The licences of what the app ships: MuPDF (AGPL), the fonts (OFL), Material Symbols (Apache). */
@Composable
fun LicencesScreen() {
    val context = LocalContext.current
    val c = Power.colors
    val ofl by produceState("", Unit) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open("licenses/OFL-IBMPlexSansArabic.txt").bufferedReader().readText() + "\n\n" +
                    context.assets.open("licenses/OFL-Noto.txt").bufferedReader().readText() + "\n\n" +
                    context.assets.open("licenses/OFL-ArefRuqaa.txt").bufferedReader().readText()
            }.getOrDefault("")
        }
    }
    Column(Modifier.fillMaxSize().background(c.bg)) {
        ScreenBar(stringResource(R.string.licences))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            @Composable
            fun entry(title: String, body: String) {
                Group {
                    Column(Modifier.padding(16.dp)) {
                        Text(title, style = MaterialTheme.typography.titleSmall, color = c.ink)
                        Spacer(Modifier.height(4.dp))
                        Text(body, style = MaterialTheme.typography.bodySmall, color = c.muted)
                    }
                }
            }
            entry("HonoKit", "Copyright © 2026 aamus. GNU Affero General Public License v3.0. You may use, study, share and change this app; whoever receives it may ask for its source code.")
            entry("MuPDF 1.28.5", "Copyright © Artifex Software, Inc. GNU Affero General Public License v3.0. https://mupdf.com")
            entry("IBM Plex Sans Arabic", "Copyright © IBM Corp. SIL Open Font License 1.1.")
            entry("Noto Sans", "Copyright © The Noto Project Authors. SIL Open Font License 1.1.")
            entry("Aref Ruqaa", "Copyright © The Aref Ruqaa Project Authors. SIL Open Font License 1.1. Used for typed Arabic signatures.")
            entry("Material Symbols", "Copyright © Google LLC. Apache License 2.0.")
            entry("AndroidX, Jetpack Compose, Kotlin", "Copyright © The Android Open Source Project, JetBrains. Apache License 2.0.")
            entry("ONNX Runtime 1.30", "Copyright © Microsoft Corporation. MIT License. Runs the text recognition and page-finding models on the phone.")
            entry("PaddleOCR models", "PP-OCRv4 text detection and PP-OCRv5 Arabic recognition. Copyright © PaddlePaddle Authors. Apache License 2.0. Used for Recognize text and Image to text.")
            entry(
                "DocAligner (LCNet-100 heatmap model)",
                "Copyright © DocsaidLab. Apache License 2.0. https://github.com/DocsaidLab/DocAligner. Used to find the page in the scanner's camera. " +
                    "Trained on SmartDoc 2015 (CC BY 4.0), MIDV-500 and MIDV-2020 (CC BY-SA 2.5), CORD (CC BY 4.0) and the authors' own synthetic images.",
            )
            if (ofl.isNotEmpty()) entry("SIL Open Font License 1.1", ofl)
            BottomSpace(24.dp)
        }
    }
}

/**
 * The issue form with what HonoKit knows filled in (decisions §54): its version, the Android
 * release and SDK level, the phone's maker and model, and the language the app shows (with
 * "system" when it follows the phone's). Nothing about the person or their files.
 */
private fun reportProblemUrl(context: Context): String {
    val chosen = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    val shown = context.resources.configuration.locales[0].toLanguageTag()
    val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercaseChar() }
    val model = Build.MODEL.orEmpty()
    return ProjectLinks.reportProblem(
        version = BuildConfig.VERSION_NAME,
        android = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
        device = if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model".trim(),
        language = if (chosen.isEmpty()) "$shown (system)" else shown,
    )
}

/** Opens [url] in the browser; a phone without one gets a message instead of a crash. */
private fun openWeb(context: Context, url: String, model: AppViewModel) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        model.say(model.str(R.string.link_no_app))
    } catch (e: SecurityException) {
        android.util.Log.w("PowerPDF", "about link", e)
        model.say(model.str(R.string.link_no_app))
    }
}
