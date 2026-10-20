package com.jmtditech.groupme.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jmtditech.groupme.data.ContactEntity
import kotlinx.coroutines.delay

private fun List<ContactEntity>.matching(q: String): List<ContactEntity> {
    val needle = q.trim()
    return if (needle.isEmpty()) this else filter { it.name.contains(needle, ignoreCase = true) }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().focusRing().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(18.dp)) }
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun ContactRow(c: ContactEntity, checked: Boolean?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().focusRing().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(c.imageUrl, c.name, 32.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            c.name.ifBlank { "Unknown" },
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (checked != null) Checkbox(checked = checked, onCheckedChange = null)
    }
}

/** Like GroupMe's "New chat": search your contacts to start a direct chat, or start a new group. */
@Composable
fun NewChatScreen(onBack: () -> Unit, onNewGroup: () -> Unit, onOpenChat: (String) -> Unit) {
    val vm: NewChatViewModel = viewModel()
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    var q by rememberSaveable { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    val field = remember { FocusRequester() }
    val shown = contacts.matching(q)

    LaunchedEffect(Unit) { vm.errors.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { delay(150); runCatching { field.requestFocus() } }

    Scaffold(
        topBar = { CompactBar("New chat", onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                CompactField(
                    value = q,
                    onValueChange = { q = it },
                    hint = "Find contacts",
                    modifier = Modifier.fillMaxWidth().padding(4.dp).focusRequester(field),
                )
            }
            item { ActionRow(Icons.Default.Group, "New group", onNewGroup) }
            items(shown, key = { it.userId }) { c ->
                ContactRow(c, checked = null) { vm.startDirect(c, onOpenChat) }
            }
            if (shown.isEmpty()) {
                item {
                    Text(
                        if (contacts.isEmpty()) "No contacts yet. They come from your groups and chats; go back and refresh the chat list."
                        else "No matching contacts.",
                        Modifier.padding(8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Name the group, pick people from your contacts and/or add them by phone number or email. */
@Composable
fun NewGroupScreen(onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    val vm: NewChatViewModel = viewModel()
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val creating by vm.creating.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf("") }
    var desc by rememberSaveable { mutableStateOf("") }
    var q by rememberSaveable { mutableStateOf("") }
    var extra by rememberSaveable { mutableStateOf("") }
    val extras = remember { mutableStateListOf<String>() }
    val selected = remember { mutableStateListOf<String>() }
    val snackbar = remember { SnackbarHostState() }
    val first = remember { FocusRequester() }
    val shown = contacts.matching(q)

    LaunchedEffect(Unit) { vm.errors.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { delay(150); runCatching { first.requestFocus() } }

    fun addExtra() {
        val v = extra.trim()
        if (v.isNotEmpty() && v !in extras) extras.add(v)
        extra = ""
    }

    Scaffold(
        topBar = { CompactBar("New group", onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                CompactField(
                    value = name,
                    onValueChange = { name = it },
                    hint = "Group name",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).focusRequester(first),
                )
            }
            item {
                CompactField(
                    value = desc,
                    onValueChange = { desc = it },
                    hint = "Description (optional)",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                )
            }
            item {
                PadButton(
                    if (creating) "Creating…" else "Create group",
                    onClick = {
                        vm.createGroup(
                            name.trim(), desc.trim(),
                            contacts.filter { it.userId in selected }, extras.toList(), onOpenChat,
                        )
                    },
                    enabled = name.isNotBlank() && !creating,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                )
            }
            item {
                Text(
                    "Add people (${selected.size + extras.size} selected)",
                    Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            item {
                CompactField(
                    value = extra,
                    onValueChange = { extra = it },
                    hint = "Phone (+1 2025550123) or email",
                    imeAction = ImeAction.Done,
                    onImeAction = { addExtra() },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                )
            }
            items(extras.toList(), key = { "x:$it" }) { e ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(e, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    PadIcon(Icons.Default.Close, "Remove $e", { extras.remove(e) })
                }
            }
            item {
                CompactField(
                    value = q,
                    onValueChange = { q = it },
                    hint = "Find contacts",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                )
            }
            items(shown, key = { it.userId }) { c ->
                val checked = c.userId in selected
                ContactRow(c, checked = checked) {
                    if (checked) selected.remove(c.userId) else selected.add(c.userId)
                }
            }
        }
    }
}
