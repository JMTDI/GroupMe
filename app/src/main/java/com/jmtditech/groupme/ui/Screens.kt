package com.jmtditech.groupme.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jmtditech.groupme.BuildConfig
import com.jmtditech.groupme.data.ConversationEntity
import kotlinx.coroutines.delay

// ---------------------------------------------------------------- login

@Composable
fun LoginScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val compact = LocalCompact.current
    var token by rememberSaveable { mutableStateOf("") }
    val busy by vm.signingIn.collectAsStateWithLifecycle()
    val error by vm.loginError.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }
    var pageUrl by rememberSaveable { mutableStateOf<String?>(null) }
    val clientId = BuildConfig.GROUPME_CLIENT_ID
    LaunchedEffect(Unit) { delay(150); runCatching { first.requestFocus() } }

    pageUrl?.let { u ->
        val isOAuth = u.startsWith("https://oauth.groupme.com/")
        InAppWebView(
            title = if (isOAuth) "Sign in with GroupMe" else "dev.groupme.com",
            url = u,
            onClose = { pageUrl = null },
            onToken = if (isOAuth) ({ token: String -> pageUrl = null; vm.signIn(token) }) else null,
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(if (compact) 8.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 12.dp),
    ) {
        Text("GroupMe", style = MaterialTheme.typography.titleLarge)
        Text(
            "Sign in with an access token from dev.groupme.com (shown under \"Access Token\").",
            style = MaterialTheme.typography.bodySmall,
        )
        CompactField(
            value = token,
            onValueChange = { token = it },
            hint = "Access token",
            password = true,
            modifier = Modifier.fillMaxWidth().focusRequester(first),
        )
        PadButton(
            if (busy) "Signing in…" else "Sign in",
            onClick = { vm.signIn(token) },
            enabled = token.isNotBlank() && !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        PadButton(
            "Get token (dev.groupme.com)",
            onClick = { pageUrl = "https://dev.groupme.com/" },
            modifier = Modifier.fillMaxWidth(),
            primary = false,
        )

        // The OAuth Client ID is built into the app (GROUPME_CLIENT_ID); there is nothing to type here.
        if (clientId.isNotBlank()) {
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            PadButton(
                "Sign in with GroupMe",
                onClick = {
                    pageUrl = "https://oauth.groupme.com/oauth/authorize?client_id=" + android.net.Uri.encode(clientId)
                },
                modifier = Modifier.fillMaxWidth(),
                primary = false,
            )
        }
    }
}

// ----------------------------------------------------- conversation list

@Composable
fun ConversationListScreen(
    vm: MainViewModel,
    onOpen: (String) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onNew: () -> Unit,
) {
    val ctx = LocalContext.current
    val convs by vm.conversations.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val firstRow = remember { FocusRequester() }

    LaunchedEffect(Unit) { vm.errors.collect { snackbar.showSnackbar(it) } }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val list = convs.filter { it.type == tab }
    // Start with D-pad focus on the first conversation.
    LaunchedEffect(tab, list.isNotEmpty()) {
        if (list.isNotEmpty()) {
            delay(150)
            runCatching { firstRow.requestFocus() }
        }
    }

    Scaffold(
        topBar = {
            CompactBar(title = "Chats", actions = {
                PadIcon(Icons.Default.Add, "New chat", onNew)
                PadIcon(Icons.Default.Refresh, "Refresh", { vm.refresh() })
                PadIcon(Icons.Default.Search, "Search", onSearch)
                PadIcon(Icons.Default.Settings, "Settings", onSettings)
            })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PadButton("Groups", { tab = 0 }, Modifier.weight(1f), primary = tab == 0)
                PadButton("Direct", { tab = 1 }, Modifier.weight(1f), primary = tab == 1)
            }
            if (refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(list, key = { _, c -> c.convKey }) { i, c ->
                    ConversationRow(
                        c,
                        if (i == 0) Modifier.focusRequester(firstRow) else Modifier,
                    ) { onOpen(c.convKey) }
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(c: ConversationEntity, modifier: Modifier, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val compact = LocalCompact.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .focusRing()
            .clickable(onClick = onClick)
            .padding(horizontal = if (compact) 6.dp else 16.dp, vertical = if (compact) 4.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(c.imageUrl, c.name, if (compact) 32.dp else 48.dp)
        Spacer(Modifier.width(if (compact) 6.dp else 12.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                c.lastText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(4.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (!compact) Text(formatTime(ctx, c.lastAt), style = MaterialTheme.typography.labelSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (c.muted) Icon(Icons.Default.NotificationsOff, "Muted", Modifier.padding(end = 2.dp).width(14.dp))
                if (c.unread > 0) Badge { Text(if (c.unread > 99) "99+" else c.unread.toString()) }
            }
        }
    }
}

// ----------------------------------------------------------------- search

@Composable
fun SearchScreen(onOpen: (String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val vm: SearchViewModel = viewModel()
    val results by vm.results.collectAsStateWithLifecycle()
    var q by rememberSaveable { mutableStateOf("") }
    val field = remember { FocusRequester() }

    LaunchedEffect(Unit) { delay(150); runCatching { field.requestFocus() } }
    LaunchedEffect(q) {
        delay(250)
        vm.search(q)
    }

    Scaffold(topBar = { CompactBar("Search", onBack = onBack) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            CompactField(
                value = q,
                onValueChange = { q = it },
                hint = "Search loaded messages",
                imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                modifier = Modifier.fillMaxWidth().padding(4.dp).focusRequester(field),
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(results, key = { it.second.id }) { (conv, msg) ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .focusRing()
                            .clickable { onOpen(msg.convKey) }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            (conv?.name ?: "Chat") + " · " + formatTime(ctx, msg.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            (if (msg.name.isNotBlank()) msg.name + ": " else "") + msg.text,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

// --------------------------------------------------------------- settings

@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val notif by vm.notificationsEnabled.collectAsStateWithLifecycle()
    val service by vm.serviceEnabled.collectAsStateWithLifecycle()
    val updatesOn by vm.updatesEnabled.collectAsStateWithLifecycle()
    val updateBusy by vm.updateBusy.collectAsStateWithLifecycle()
    val updateMessage by vm.updateMessage.collectAsStateWithLifecycle()
    val updateInfo by vm.updateInfo.collectAsStateWithLifecycle()
    var confirmSignOut by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { first.requestFocus() } }

    Scaffold(topBar = { CompactBar("Settings", onBack = onBack) }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Signed in as ${vm.userName.ifBlank { "you" }}", style = MaterialTheme.typography.titleSmall)

            SettingRow(
                "Notifications", "Show a notification for new messages", notif,
                Modifier.focusRequester(first),
            ) { vm.setNotifications(it) }
            SettingRow(
                "Background connection",
                "Live connection for instant messages (silent ongoing notification)",
                service,
                Modifier,
            ) { vm.setService(it) }

            SettingRow(
                "Automatic updates",
                "Check GitHub for new versions (installed ${BuildConfig.VERSION_NAME})",
                updatesOn,
                Modifier,
            ) { vm.setUpdatesEnabled(it) }
            PadButton(
                updateBusy ?: "Check for updates now",
                onClick = { vm.checkForUpdates(manual = true) },
                enabled = updateBusy == null,
                modifier = Modifier.fillMaxWidth(),
                primary = false,
            )
            updateMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            updateInfo?.let { info ->
                PadButton(
                    "Install ${info.tag}",
                    onClick = { vm.updateDismissed.value = false },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            PadButton(
                "Battery optimisation",
                onClick = {
                    runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                },
                modifier = Modifier.fillMaxWidth(),
                primary = false,
            )
            Text(
                "If messages arrive late, exclude this app from battery optimisation.",
                style = MaterialTheme.typography.bodySmall,
            )
            HorizontalDivider()
            PadButton("Sign out", { confirmSignOut = true }, Modifier.fillMaxWidth())
        }
    }

    if (confirmSignOut) {
        val safe = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(100); runCatching { safe.requestFocus() } }
        PadDialog(onDismiss = { confirmSignOut = false }) {
            Text("Sign out and clear downloaded messages?", style = MaterialTheme.typography.bodyMedium)
            PadButton("Cancel", { confirmSignOut = false }, Modifier.fillMaxWidth().focusRequester(safe), primary = false)
            PadButton("Sign out", { confirmSignOut = false; vm.signOut() }, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    modifier: Modifier,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .focusRing()
            .clickable { onChange(!checked) }
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}
