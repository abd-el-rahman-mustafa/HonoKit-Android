package com.aamus.powerpdf.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aamus.powerpdf.R
import com.aamus.powerpdf.ui.theme.Power
import com.aamus.powerpdf.update.Updates
import com.aamus.powerpdf.update.Updates.State

/** Update found → download → Android's install screen (decisions §36). Nothing happens without a tap. */
@Composable
fun UpdateDialog() {
    val open by Updates.dialog.collectAsStateWithLifecycle()
    val state by Updates.flow.collectAsStateWithLifecycle()
    val shown = open && state !is State.Idle && state !is State.Checking && state !is State.UpToDate
    LaunchedEffect(shown, open) { if (open && !shown) Updates.dialog.value = false }
    if (!shown) return

    val context = LocalContext.current
    val c = Power.colors
    val close = { Updates.dialog.value = false }
    @Composable
    fun body(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = c.muted)

    when (val s = state) {
        is State.Available -> AlertDialog(
            onDismissRequest = { Updates.later() },
            title = { Text(stringResource(R.string.update_title)) },
            text = { body(stringResource(R.string.update_body, s.release.version, Formatter.formatShortFileSize(context, s.release.size))) },
            confirmButton = { DialogButton(stringResource(R.string.update_now), { Updates.download(s.release) }) },
            dismissButton = { DialogButton(stringResource(R.string.update_later), { Updates.later() }) },
            containerColor = c.surface,
        )
        is State.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.update_downloading_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LinearProgressIndicator(
                        progress = { s.percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = c.accent,
                        trackColor = c.line,
                    )
                    body(stringResource(R.string.update_percent, s.percent))
                }
            },
            confirmButton = { DialogButton(stringResource(R.string.cancel), { Updates.cancel() }) },
            containerColor = c.surface,
        )
        is State.NeedsPermission -> AlertDialog(
            onDismissRequest = close,
            title = { Text(stringResource(R.string.update_permission_title)) },
            text = { body(stringResource(R.string.update_permission_body)) },
            confirmButton = { DialogButton(stringResource(R.string.update_install), { Updates.install() }) },
            dismissButton = { DialogButton(stringResource(R.string.cancel), close) },
            containerColor = c.surface,
        )
        is State.Installing -> AlertDialog(
            onDismissRequest = close,
            title = { Text(stringResource(R.string.update_title)) },
            text = { body(stringResource(R.string.update_installing)) },
            confirmButton = { DialogButton(stringResource(R.string.close), close) },
            containerColor = c.surface,
        )
        is State.Failed -> AlertDialog(
            onDismissRequest = close,
            title = { Text(stringResource(R.string.update_failed_title)) },
            text = { body(s.message) },
            confirmButton = {
                val release = s.release
                if (release != null) DialogButton(stringResource(R.string.update_try_again), { Updates.retry() })
                else DialogButton(stringResource(R.string.close), close)
            },
            dismissButton = if (s.release != null) ({ DialogButton(stringResource(R.string.close), close) }) else null,
            containerColor = c.surface,
        )
        else -> {}
    }
}
