package com.aamus.powerpdf.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aamus.powerpdf.R
import com.aamus.powerpdf.ui.theme.Power

/** A Material Symbols icon from res/drawable. */
@Composable
fun Sym(@DrawableRes id: Int, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = Color.Unspecified, size: Dp = 24.dp) {
    Icon(
        painter = painterResource(id),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        tint = if (tint == Color.Unspecified) androidx.compose.material3.LocalContentColor.current else tint,
    )
}

/** An icon button with a long-press tooltip naming it (every icon-only control has one). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun IconAction(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    tint: Color = Color.Unspecified,
) {
    val c = Power.colors
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
            colors = if (selected) IconButtonDefaults.iconButtonColors(containerColor = c.accentSoft, contentColor = c.accent)
            else IconButtonDefaults.iconButtonColors(),
        ) {
            Sym(icon, label, tint = if (!enabled) Color.Unspecified else tint)
        }
    }
}

/** The one primary action of a screen: a blue pill that names what it does ("Combine 3 files"). */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, @DrawableRes icon: Int? = null) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = CircleShape,
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Power.colors.accent, contentColor = Power.colors.accentInk),
    ) {
        if (icon != null) {
            Sym(icon, null, size = 20.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 2, textAlign = TextAlign.Center)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, @DrawableRes icon: Int? = null) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = CircleShape,
        border = BorderStroke(1.dp, Power.colors.line),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Power.colors.ink),
    ) {
        if (icon != null) {
            Sym(icon, null, size = 20.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 2, textAlign = TextAlign.Center)
    }
}

/** A text button for dialogs; [danger] colours a destructive verb ("Delete"). */
@Composable
fun DialogButton(text: String, onClick: () -> Unit, danger: Boolean = false, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(text, color = if (!enabled) Power.colors.faint else if (danger) Power.colors.danger else Power.colors.accent, style = MaterialTheme.typography.labelLarge)
    }
}

/** UPPERCASE-free section heading, like the desktop's panels but in phone proportions. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = Power.colors.muted,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
    )
}

/** A card-like group of rows (Settings, tool options). */
@Composable
fun Group(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = Power.colors
    Column(
        modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(16.dp))
    ) { content() }
}

@Composable
fun GroupDivider() = HorizontalDivider(Modifier.padding(start = 56.dp), color = Power.colors.line)

/** A row with an optional icon, a title, a description and something at the end. */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    @DrawableRes icon: Int? = null,
    iconTint: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val c = Power.colors
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Sym(icon, null, tint = if (iconTint == Color.Unspecified) c.muted else iconTint)
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) c.ink else c.faint)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, description: String? = null, @DrawableRes icon: Int? = null) {
    val c = Power.colors
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Sym(icon, null, tint = c.muted)
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = c.ink)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = Color.White, uncheckedBorderColor = c.faint, uncheckedThumbColor = c.faint, uncheckedTrackColor = c.sunken),
        )
    }
}

@Composable
fun RadioRow(title: String, selected: Boolean, onSelect: () -> Unit, description: String? = null) {
    val c = Power.colors
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, colors = androidx.compose.material3.RadioButtonDefaults.colors(selectedColor = c.accent, unselectedColor = c.faint))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = c.ink)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
    }
}

/**
 * A segmented control whose labels never get cut: when they do not fit side by side, the choices
 * stack as a list (desktop decisions §41).
 */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val c = Power.colors
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth()) {
        val longest = options.maxOf { it.second.length }
        val perItem = maxWidth / options.size
        val fits = perItem >= (longest * 8 + 28).dp
        if (fits) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(CircleShape)
                    .background(c.sunken)
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                options.forEach { (value, label) ->
                    val on = value == selected
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(CircleShape)
                            .background(if (on) c.surface else Color.Transparent)
                            .then(if (on) Modifier.border(1.dp, c.line, CircleShape) else Modifier)
                            .selectable(selected = on, role = Role.RadioButton) { onSelect(value) }
                            .heightIn(min = 40.dp)
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (on) c.ink else c.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Visible,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, c.line, RoundedCornerShape(12.dp))
            ) {
                options.forEachIndexed { i, (value, label) ->
                    if (i > 0) HorizontalDivider(color = c.line)
                    RadioRow(label, value == selected, { onSelect(value) })
                }
            }
        }
    }
}

/** A text field with one soft focus ring (no double borders; desktop feedback 2026-09-30). */
@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    error: String? = null,
    password: Boolean = false,
    number: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    imeAction: androidx.compose.ui.text.input.ImeAction = androidx.compose.ui.text.input.ImeAction.Done,
    onDone: (() -> Unit)? = null,
) {
    val c = Power.colors
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, color = c.faint) } },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = minLines,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
        visualTransformation = if (password && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (password) KeyboardType.Password else if (number) KeyboardType.Number else KeyboardType.Text,
            imeAction = imeAction,
            autoCorrectEnabled = !password,
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone?.invoke() }, onGo = { onDone?.invoke() }),
        trailingIcon = if (password) {
            {
                IconAction(
                    if (visible) R.drawable.ic_visibility_off else R.drawable.ic_visibility,
                    stringResource(if (visible) R.string.hide_password else R.string.show_password),
                    { visible = !visible },
                )
            }
        } else null,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = c.accent,
            unfocusedBorderColor = c.line,
            focusedLabelColor = c.accent,
            cursorColor = c.accent,
            focusedContainerColor = c.surface,
            unfocusedContainerColor = c.surface,
        ),
    )
}

/** A thin handle bar used at the top of bottom sheets. */
@Composable
fun SheetTitle(title: String, modifier: Modifier = Modifier, trailing: @Composable (RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Power.colors.ink, modifier = Modifier.weight(1f))
        trailing?.invoke(this)
    }
}

/** A round colour swatch; [selected] adds an accent ring. */
@Composable
fun Swatch(color: Color, selected: Boolean, onClick: () -> Unit, size: Dp = 32.dp, label: String? = null) {
    val c = Power.colors
    val name = label ?: stringResource(ColourWord.of(color.toArgb()).label)
    Box(
        Modifier
            .size(size + 8.dp)
            .clip(CircleShape)
            .border(2.dp, if (selected) c.accent else Color.Transparent, CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(4.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, c.faint.copy(alpha = 0.7f), CircleShape)
            // Every swatch is named (its colour, when no label is given) for screen readers.
            .semantics { contentDescription = name }
    )
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Power.colors.muted, modifier = modifier)
}

/** Space that keeps the last row clear of the navigation bar. */
@Composable
fun BottomSpace(extra: Dp = 16.dp) {
    Spacer(Modifier.navigationBarsPadding().height(extra))
}

/** Keeps a file name's own direction inside a sentence in the other script (U+2068 … U+2069). */
fun bidi(text: String): String = "\u2068$text\u2069"
