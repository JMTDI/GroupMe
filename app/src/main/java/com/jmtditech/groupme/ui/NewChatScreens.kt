package com.jmtditech.groupme.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.PersonAdd
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jmtditech.groupme.data.ContactEntity
import kotlinx.coroutines.delay

private fun List<ContactEntity>.matching(q: String): List<ContactEntity> {
    val needle = q.trim()
    return if (needle.isEmpty()) this else filter {
        it.name.contains(needle, ignoreCase = true) ||
            (it.phone?.contains(needle) == true) || (it.email?.contains(needle, ignoreCase = true) == true)
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().focusRing().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
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

/** A contact row. [checked] shows a checkbox (group picker); [onRemove] shows a remove button (saved contacts). */
@Composable
private fun ContactRow(c: ContactEntity, checked: Boolean?, onRemove: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .focusRing()
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(c.imageUrl, c.name, 32.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    c.name.ifBlank { "Unknown" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val sub = c.phone ?: c.email
                if (sub != null) {
                    Text(
                        sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (checked != null) Checkbox(checked = checked, onCheckedChange = null)
        }
        if (onRemove != null) PadIcon(Icons.Default.Close, "Remove ${c.name}", onRemove)
    }
}

/** Like GroupMe's "New chat": search contacts to start a direct chat, start a group, or add contacts. */
@Composable
fun NewChatScreen(
    onBack: () -> Unit,
    onNewGroup: () -> Unit,
    onAddContact: () -> Unit,
    onInvite: (String) -> Unit,
    onOpenChat: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val vm: NewChatViewModel = viewModel()
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    var q by rememberSaveable { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    val field = remember { FocusRequester() }
    val shown = contacts.matching(q)

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.importPhone()
    }

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
            item { ActionRow(Icons.Default.Group, "New group", onClick = onNewGroup) }
            item { ActionRow(Icons.Default.PersonAdd, "Add contact", onClick = onAddContact) }
            item {
                ActionRow(Icons.Default.Contacts, "Import phone contacts") {
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                        vm.importPhone()
                    } else {
                        permission.launch(Manifest.permission.READ_CONTACTS)
                    }
                }
            }
            items(shown, key = { it.contactKey }) { c ->
                ContactRow(
                    c,
                    checked = null,
                    onRemove = if (c.local) ({ vm.deleteContact(c.contactKey) }) else null,
                ) { vm.open(c, onOpenChat, onInvite) }
            }
            if (shown.isEmpty()) {
                item {
                    Text(
                        if (contacts.isEmpty()) "No contacts yet. Add one, import from your phone, or refresh the chat list to load people from your groups."
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

/** Save a contact by name plus phone number or email. */
@Composable
fun AddContactScreen(onBack: () -> Unit) {
    val vm: NewChatViewModel = viewModel()
    var name by rememberSaveable { mutableStateOf("") }
    var value by rememberSaveable { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    val first = remember { FocusRequester() }
    val ready = name.isNotBlank() && value.isNotBlank()

    LaunchedEffect(Unit) { vm.errors.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { delay(150); runCatching { first.requestFocus() } }

    Scaffold(
        topBar = { CompactBar("Add contact", onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CompactField(
                value = name,
                onValueChange = { name = it },
                hint = "Name",
                modifier = Modifier.fillMaxWidth().focusRequester(first),
            )
            CompactField(
                value = value,
                onValueChange = { value = it },
                hint = "Phone (+1 2025550123) or email",
                imeAction = ImeAction.Done,
                onImeAction = { if (ready) vm.saveContact(name, value, onBack) },
                modifier = Modifier.fillMaxWidth(),
            )
            PadButton("Save contact", onClick = { vm.saveContact(name, value, onBack) }, enabled = ready, modifier = Modifier.fillMaxWidth())
            Text(
                "A saved contact can be invited into a group. Direct messages need their GroupMe account, which appears here once you share a group.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Name the group, pick people from your contacts and/or add them by phone number or email. */
@Composable
fun NewGroupScreen(preselect: String?, onBack: () -> Unit, onOpenChat: (String) -> Unit) {
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
    LaunchedEffect(preselect) { if (preselect != null && preselect !in selected) selected.add(preselect) }

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
                            contacts.filter { it.contactKey in selected }, extras.toList(), onOpenChat,
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
            items(shown, key = { it.contactKey }) { c ->
                val checked = c.contactKey in selected
                ContactRow(c, checked = checked) {
                    if (checked) selected.remove(c.contactKey) else selected.add(c.contactKey)
                }
            }
        }
    }
}
