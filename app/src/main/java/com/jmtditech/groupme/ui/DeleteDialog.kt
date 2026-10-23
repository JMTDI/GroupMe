package com.jmtditech.groupme.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.jmtditech.groupme.data.ConversationEntity
import com.jmtditech.groupme.data.Repository
import kotlinx.coroutines.delay

/**
 * Confirmation for removing a chat. Groups can be left (or deleted for everyone by their creator); GroupMe's API
 * can't delete direct chats, so those are only removed from this app. Focus starts on Cancel.
 */
@Composable
fun DeleteDialog(
    conv: ConversationEntity,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (forEveryone: Boolean) -> Unit,
) {
    val cancel = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(100); runCatching { cancel.requestFocus() } }
    val isGroup = conv.type == Repository.GROUP

    PadDialog(onDismiss = { if (!busy) onDismiss() }) {
        Text(
            if (isGroup) "Leave \"${conv.name}\"?" else "Delete chat with ${conv.name}?",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            if (isGroup) "You will stop receiving its messages. The group carries on for everyone else."
            else "This removes the chat from this app. GroupMe doesn't let apps delete direct messages, so the other " +
                "person keeps their copy, and the chat comes back if either of you sends a new message.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PadButton(
            "Cancel",
            onClick = onDismiss,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().focusRequester(cancel),
            primary = false,
        )
        PadButton(
            if (busy) "Working…" else if (isGroup) "Leave group" else "Delete chat",
            onClick = { onConfirm(false) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        if (isGroup) {
            PadButton(
                "Delete for everyone (creator only)",
                onClick = { onConfirm(true) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                primary = false,
            )
        }
    }
}
