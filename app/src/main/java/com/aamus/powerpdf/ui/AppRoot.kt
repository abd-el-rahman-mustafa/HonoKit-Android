package com.aamus.powerpdf.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aamus.powerpdf.AppViewModel
import com.aamus.powerpdf.R
import com.aamus.powerpdf.Screen
import com.aamus.powerpdf.SystemRequest
import com.aamus.powerpdf.ui.home.HomeScreen
import com.aamus.powerpdf.ui.settings.LicencesScreen
import com.aamus.powerpdf.ui.settings.SettingsScreen
import com.aamus.powerpdf.ui.theme.Power
import com.aamus.powerpdf.ui.tools.ToolScreen
import com.aamus.powerpdf.viewer.ViewerScreen

val LocalModel = staticCompositionLocalOf<AppViewModel> { error("no model") }

/** Starts an activity (share sheet, other apps) from anywhere in the tree. */
val LocalLauncher = staticCompositionLocalOf<(Intent) -> Unit> { {} }

@Composable
fun AppRoot(model: AppViewModel) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(model) {
        model.messages.collect { m ->
            val result = snackbar.showSnackbar(
                message = m.text,
                actionLabel = m.action,
                withDismissAction = m.action == null && m.long,
                duration = if (m.action != null || m.long) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) m.onAction?.invoke()
        }
    }

    LaunchedEffect(model) { com.aamus.powerpdf.DebugBus.events.collect { model.debug(it) } }

    // Once a day at most, when the person has not turned it off (decisions §36).
    LaunchedEffect(Unit) { com.aamus.powerpdf.update.Updates.autoCheck(model.settings.value.checkUpdates) }

    // What the model asks the activity for.
    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri: Uri? ->
        (model.systemRequest as? SystemRequest.CreateDocument)?.done?.invoke(uri)
    }
    val createText = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        (model.systemRequest as? SystemRequest.CreateDocument)?.done?.invoke(uri)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        (model.systemRequest as? SystemRequest.StoragePermission)?.done?.invoke(ok)
    }
    LaunchedEffect(model.systemRequest) {
        when (val r = model.systemRequest) {
            is SystemRequest.CreateDocument -> if (r.mime.startsWith("text/")) createText.launch(r.name) else createDoc.launch(r.name)
            is SystemRequest.StoragePermission -> permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            null -> {}
        }
    }

    val launcher: (Intent) -> Unit = { intent ->
        try {
            context.startActivity(intent)
        } catch (_: Throwable) {
            model.say(context.getString(R.string.error_no_app))
        }
    }

    CompositionLocalProvider(LocalModel provides model, LocalLauncher provides launcher) {
        Box(Modifier.fillMaxSize().background(Power.colors.bg)) {
            // Screens that handle Back themselves (a viewer with unsaved changes) register their own handler.
            BackHandler(enabled = model.stack.size > 1) { model.pop() }

            AnimatedContent(
                targetState = model.current,
                transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
                contentKey = { it.key() },
                label = "screens",
            ) { screen ->
                when (screen) {
                    // Home (rail or bottom bar) and the reader (pages run under the side bars,
                    // their controls keep clear) place themselves around the side bars.
                    Screen.Home -> HomeScreen()
                    is Screen.Viewer -> ViewerScreen(screen)
                    // The rest keep clear of the navigation buttons and a cut-out at the side when
                    // the phone is held sideways (UX review finding 4).
                    is Screen.ImageViewer -> SideSafe(Color.Black) { com.aamus.powerpdf.ui.image.ImageViewerScreen(screen) }
                    is Screen.Tool -> SideSafe(Power.colors.bg) { ToolScreen(screen) }
                    is Screen.Result -> SideSafe(Power.colors.bg) { com.aamus.powerpdf.ui.tools.ResultScreen(screen) }
                    Screen.Settings -> SideSafe(Power.colors.bg) { SettingsScreen() }
                    Screen.Licences -> SideSafe(Power.colors.bg) { LicencesScreen() }
                }
            }

            SnackbarHost(
                snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .imePadding()
                    // Above what each screen keeps at the bottom: Home's Open button and tabs, the
                    // viewer's floating tools, a tool's main button.
                    .padding(
                        bottom = when (model.current) {
                            Screen.Home -> 168.dp
                            is Screen.Viewer -> (model.snackbarLift ?: 84).dp
                            is Screen.ImageViewer -> 88.dp
                            is Screen.Tool, is Screen.Result -> 88.dp
                            else -> 16.dp
                        }
                    ),
            ) { data ->
                Snackbar(
                    data,
                    // A long message or large text: the action goes under it instead of squeezing it
                    // into a narrow column.
                    actionOnNewLine = isLargeFont() || data.visuals.message.length > 60,
                    shape = RoundedCornerShape(12.dp),
                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    actionColor = MaterialTheme.colorScheme.inversePrimary,
                    modifier = Modifier.widthIn(max = 560.dp),
                )
            }

            model.busy?.let { Busy(it) }
            ChooserHost()
            model.passwordRequest?.let { PasswordDialog(it.name, it.wrong, it.done) }
            UpdateDialog()
        }
    }
}

/** A screen kept clear of the side bars in landscape; [bg] fills the strip beside it. */
@Composable
private fun SideSafe(bg: Color, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(bg)
            .windowInsetsPadding(SideBars.only(androidx.compose.foundation.layout.WindowInsetsSides.Horizontal)),
    ) { content() }
}

private fun Screen.key(): String = when (this) {
    Screen.Home -> "home"
    is Screen.Viewer -> "viewer-$sessionId"
    is Screen.ImageViewer -> "image-${uris.hashCode()}"
    is Screen.Tool -> "tool-$tool-$sessionId-${inputs.size}"
    is Screen.Result -> "result-$tool-${files.hashCode()}"
    Screen.Settings -> "settings"
    Screen.Licences -> "licences"
}

/** A quiet "working" card over everything; touches do not go through. */
@Composable
fun Busy(text: String) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.28f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Power.colors.surface)
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(Modifier.size(24.dp), color = Power.colors.accent, strokeWidth = 3.dp)
            Spacer(Modifier.width(16.dp))
            Text(text, style = MaterialTheme.typography.bodyLarge, color = Power.colors.ink)
        }
    }
}

@Composable
fun PasswordDialog(name: String, wrong: Boolean, done: (String?) -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { done(null) },
        title = { Text(stringResource(R.string.password_title)) },
        text = {
            Column {
                Text(stringResource(R.string.password_prompt, bidi(name)), style = MaterialTheme.typography.bodyMedium, color = Power.colors.muted)
                Spacer(Modifier.padding(6.dp))
                Field(
                    value = value,
                    onChange = { value = it },
                    label = stringResource(R.string.password),
                    password = true,
                    error = if (wrong) stringResource(R.string.wrong_password) else null,
                    onDone = { if (value.isNotEmpty()) done(value) },
                )
            }
        },
        confirmButton = { DialogButton(stringResource(R.string.open), { done(value) }, enabled = value.isNotEmpty()) },
        dismissButton = { DialogButton(stringResource(R.string.cancel), { done(null) }) },
        containerColor = Power.colors.surface,
    )
}
