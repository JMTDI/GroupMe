package com.jmtditech.groupme.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import java.util.Date

/** True on very small screens (e.g. 240x320 feature phones): tighter spacing, smaller text. */
val LocalCompact = compositionLocalOf { false }

fun formatTime(ctx: Context, epochSec: Long): String {
    if (epochSec <= 0) return ""
    val ms = epochSec * 1000
    val d = Date(ms)
    return if (DateUtils.isToday(ms)) DateFormat.getTimeFormat(ctx).format(d)
    else DateFormat.getMediumDateFormat(ctx).format(d)
}

/** Opens a URL if anything on the device can handle it (feature phones often have no browser). */
fun openUrl(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/**
 * Visible D-pad focus indicator. Place it BEFORE clickable()/focusable() in the modifier chain
 * (and after any background so the ring draws on top).
 */
@Composable
fun Modifier.focusRing(shape: Shape = RoundedCornerShape(6.dp)): Modifier {
    var focused by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.tertiary
    return this
        .onFocusChanged { focused = it.hasFocus }
        .then(
            if (focused) Modifier
                .background(color.copy(alpha = 0.18f), shape)
                .border(2.dp, color, shape)
            else Modifier
        )
}

@Composable
fun Avatar(url: String?, name: String, size: Dp = 44.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                name.firstOrNull()?.uppercase() ?: "?",
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
fun CompactBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val compact = LocalCompact.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .statusBarsPadding()
            .height(if (compact) 36.dp else 48.dp)
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            PadIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
        }
        Text(
            title,
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
            style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        actions()
    }
}

@Composable
fun PadIcon(
    icon: ImageVector,
    desc: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val s = if (LocalCompact.current) 30.dp else 40.dp
    val tint = if (enabled) LocalContentColor.current else LocalContentColor.current.copy(alpha = 0.38f)
    Box(
        modifier
            .size(s)
            .focusRing(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, Modifier.size(20.dp), tint = tint)
    }
}

@Composable
fun PadButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = true,
) {
    val shape = RoundedCornerShape(8.dp)
    val scheme = MaterialTheme.colorScheme
    val bg = if (primary) scheme.primary.copy(alpha = if (enabled) 1f else 0.4f) else Color.Transparent
    val fg = if (primary) scheme.onPrimary else scheme.primary
    Box(
        modifier
            .heightIn(min = 30.dp)
            .clip(shape)
            .background(bg)
            .border(1.dp, if (primary) Color.Transparent else scheme.primary, shape)
            .focusRing(shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (enabled) fg else fg.copy(alpha = 0.6f),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Single-line text field built for D-pad phones. Left/Right move the cursor (and only leave the field at the
 * very start/end of the text), Up/Down always move focus out instead of being swallowed, and Enter/OK runs
 * [onImeAction] (send). [onUp]/[onDown] may handle the move themselves and return true.
 */
@Composable
fun CompactField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "",
    password: Boolean = false,
    imeAction: ImeAction = ImeAction.Default,
    onImeAction: (() -> Unit)? = null,
    onUp: (() -> Boolean)? = null,
    onDown: (() -> Boolean)? = null,
) {
    val shape = RoundedCornerShape(8.dp)
    val scheme = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current

    // The text is owned by the caller; selection/composition live here so we can move the cursor ourselves.
    var selection by remember { mutableStateOf(TextRange(value.length)) }
    var composition by remember { mutableStateOf<TextRange?>(null) }
    val len = value.length
    val sel = TextRange(selection.start.coerceIn(0, len), selection.end.coerceIn(0, len))
    val tfv = TextFieldValue(
        text = value,
        selection = sel,
        composition = composition?.takeIf { it.end <= len },
    )

    BasicTextField(
        value = tfv,
        onValueChange = {
            selection = it.selection
            composition = it.composition
            if (it.text != value) onValueChange(it.text)
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
        cursorBrush = SolidColor(scheme.primary),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        keyboardActions = KeyboardActions(onAny = { onImeAction?.invoke() }),
        modifier = modifier
            .heightIn(min = 32.dp)
            .border(1.dp, scheme.outline, shape)
            .focusRing(shape)
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.key) {
                    Key.DirectionLeft -> {
                        if (!sel.collapsed) { selection = TextRange(sel.min); composition = null }
                        else if (sel.start > 0) { selection = TextRange(sel.start - 1); composition = null }
                        else focusManager.moveFocus(FocusDirection.Left)
                        true
                    }
                    Key.DirectionRight -> {
                        if (!sel.collapsed) { selection = TextRange(sel.max); composition = null }
                        else if (sel.start < len) { selection = TextRange(sel.start + 1); composition = null }
                        else focusManager.moveFocus(FocusDirection.Right)
                        true
                    }
                    Key.DirectionUp -> {
                        if (onUp?.invoke() != true) focusManager.moveFocus(FocusDirection.Up)
                        true
                    }
                    Key.DirectionDown -> {
                        if (onDown?.invoke() != true) focusManager.moveFocus(FocusDirection.Down)
                        true
                    }
                    Key.Enter, Key.NumPadEnter, Key.DirectionCenter ->
                        if (onImeAction != null) { onImeAction(); true } else false
                    else -> false
                }
            },
        decorationBox = { inner ->
            Box(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(hint, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                }
                inner()
            }
        },
    )
}

/** A dialog whose contents are D-pad focusable PadButtons. */
@Composable
fun PadDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}
