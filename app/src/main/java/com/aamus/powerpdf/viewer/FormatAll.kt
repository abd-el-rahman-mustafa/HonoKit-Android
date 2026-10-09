package com.aamus.powerpdf.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aamus.powerpdf.R
import com.aamus.powerpdf.engine.FontKind
import com.aamus.powerpdf.engine.FormatAll
import com.aamus.powerpdf.ui.DialogButton
import com.aamus.powerpdf.ui.Hint
import com.aamus.powerpdf.ui.Segmented
import com.aamus.powerpdf.ui.Swatch
import com.aamus.powerpdf.ui.theme.Power

/**
 * Format all text (desktop decisions §48): a new typeface, a scaled or single size, one colour,
 * for this page or all pages. Returns the change and whether it is for every page.
 */
@Composable
fun FormatAllDialog(current: Int, onDismiss: () -> Unit, onApply: (FormatAll, Boolean) -> Unit) {
    val c = Power.colors
    var kind by remember { mutableStateOf<FontKind?>(null) }
    var size by remember { mutableStateOf(0) } // 0 keep, 1 smaller, 2 larger, 3..: one size
    var colour by remember { mutableStateOf<Int?>(null) }
    var allPages by remember { mutableStateOf(true) }
    val sizes = listOf(10f, 11f, 12f, 14f)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.format_all)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.typeface), style = MaterialTheme.typography.labelLarge, color = c.muted)
                Segmented(
                    listOf(null to stringResource(R.string.keep), FontKind.Sans to stringResource(R.string.typeface_sans), FontKind.Serif to stringResource(R.string.typeface_serif), FontKind.Mono to stringResource(R.string.typeface_mono)),
                    kind, { kind = it },
                )
                Text(stringResource(R.string.size), style = MaterialTheme.typography.labelLarge, color = c.muted)
                Segmented(listOf(0 to stringResource(R.string.keep), 1 to stringResource(R.string.smaller), 2 to stringResource(R.string.larger)), size, { size = it })
                Segmented(sizes.mapIndexed { i, s -> (3 + i) to "${s.toInt()} pt" }, size, { size = it })
                Text(stringResource(R.string.colour), style = MaterialTheme.typography.labelLarge, color = c.muted)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // "Keep" is a choice like the swatches, shown chosen when no colour is.
                    val keepOn = colour == null
                    Text(
                        stringResource(R.string.keep),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (keepOn) c.accent else c.ink,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(if (keepOn) c.accentSoft else Color.Transparent)
                            .border(1.dp, if (keepOn) c.accent else c.line, androidx.compose.foundation.shape.CircleShape)
                            .clickable { colour = null }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                    listOf(0xFF111111.toInt(), 0xFF1473E6.toInt(), 0xFF1B3A6B.toInt(), 0xFFE34850.toInt()).forEach { col ->
                        Swatch(Color(col), selected = colour == col, onClick = { colour = col }, size = 28.dp)
                    }
                }
                Segmented(listOf(true to stringResource(R.string.pages_all), false to stringResource(R.string.pages_current, current + 1)), allPages, { allPages = it })
                Hint(stringResource(R.string.format_all_note))
            }
        },
        confirmButton = {
            DialogButton(stringResource(R.string.apply), {
                val change = FormatAll(
                    kind = kind,
                    size = if (size >= 3) sizes[size - 3] else null,
                    scale = when (size) {
                        1 -> 0.9f
                        2 -> 1.12f
                        else -> null
                    },
                    colour = colour,
                )
                onApply(change, allPages)
            }, enabled = kind != null || size != 0 || colour != null)
        },
        dismissButton = { DialogButton(stringResource(R.string.cancel), onDismiss) },
        containerColor = c.surface,
    )
}
