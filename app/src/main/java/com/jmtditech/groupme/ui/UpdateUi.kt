package com.jmtditech.groupme.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jmtditech.groupme.BuildConfig
import kotlinx.coroutines.delay

/** Checks for an update on launch (at most every 12 hours) and shows a D-pad friendly prompt. */
@Composable
fun UpdatePrompt(vm: MainViewModel) {
    val info by vm.updateInfo.collectAsStateWithLifecycle()
    val dismissed by vm.updateDismissed.collectAsStateWithLifecycle()
    val busy by vm.updateBusy.collectAsStateWithLifecycle()
    val error by vm.updateError.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.checkForUpdates(manual = false) }

    val i = info
    if (i != null && !dismissed) {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(100); runCatching { first.requestFocus() } }
        PadDialog(onDismiss = { if (busy == null) vm.updateDismissed.value = true }) {
            Text("Update available", style = MaterialTheme.typography.titleMedium)
            Text(
                "Version ${i.tag.removePrefix("v")} (installed ${BuildConfig.VERSION_NAME})",
                style = MaterialTheme.typography.bodySmall,
            )
            if (i.notes.isNotBlank()) {
                Text(
                    i.notes.trim().take(400),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            PadButton(
                busy ?: "Update",
                onClick = { vm.startUpdate() },
                enabled = busy == null,
                modifier = Modifier.fillMaxWidth().focusRequester(first),
            )
            PadButton(
                "Later",
                onClick = { vm.updateDismissed.value = true },
                enabled = busy == null,
                modifier = Modifier.fillMaxWidth(),
                primary = false,
            )
        }
    }
}
