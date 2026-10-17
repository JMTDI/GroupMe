package com.jmtditech.groupme.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jmtditech.groupme.data.AppState
import com.jmtditech.groupme.data.MessageEntity
import com.jmtditech.groupme.data.Repository
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

@Composable
fun ChatScreen(convKey: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val vm: ChatViewModel = viewModel(
        key = convKey,
        factory = viewModelFactory { initializer { ChatViewModel(convKey) } },
    )
    val conv by vm.conv.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val sending by vm.sending.collectAsStateWithLifecycle()
    val loadingOlder by vm.loadingOlder.collectAsStateWithLifecycle()

    var text by rememberSaveable { mutableStateOf("") }
    var image by remember { mutableStateOf<Uri?>(null) }
    var selected by remember { mutableStateOf<MessageEntity?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val newestFocus = remember { FocusRequester() }
    var focusedOnce by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { if (it != null) image = it }

    DisposableEffect(convKey) {
        AppState.openConvKey = convKey
        onDispose { if (AppState.openConvKey == convKey) AppState.openConvKey = null }
    }
    LaunchedEffect(Unit) { vm.errors.collect { snackbar.showSnackbar(it) } }

    // D-pad: start with focus on the newest message.
    LaunchedEffect(messages.isNotEmpty()) {
        if (messages.isNotEmpty() && !focusedOnce) {
            delay(200)
            runCatching { newestFocus.requestFocus() }
            focusedOnce = true
        }
    }

    // Reverse layout: the last visible index is the oldest message currently on screen.
    val nearTop by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 5
        }
    }
    LaunchedEffect(nearTop, messages.size) { if (nearTop) vm.loadOlder() }
    LaunchedEffect(messages.firstOrNull()?.id) {
        if (listState.firstVisibleItemIndex <= 2 && !listState.isScrollInProgress) listState.scrollToItem(0)
    }

    fun doSend() {
        if ((text.isBlank() && image == null) || sending) return
        val t = text.trim()
        val img = image
        text = ""
        image = null
        vm.send(t, img) { ok ->
            if (!ok) {
                if (text.isEmpty()) text = t
                if (image == null) image = img
            }
        }
    }

    Scaffold(
        topBar = {
            val muted = conv?.muted == true
            CompactBar(title = conv?.name ?: "", onBack = onBack, actions = {
                PadIcon(
                    if (muted) Icons.Default.NotificationsOff else Icons.Default.Notifications,
                    if (muted) "Unmute" else "Mute",
                    { vm.toggleMute() },
                )
            })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .consumeWindowInsets(pad)
                .imePadding()
                .fillMaxSize()
        ) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(messages, key = { it.id }) { m ->
                    MessageBubble(
                        m = m,
                        mine = m.senderId == vm.myId,
                        showName = conv?.type == Repository.GROUP,
                        myId = vm.myId,
                        modifier = if (m.id == messages.firstOrNull()?.id) Modifier.focusRequester(newestFocus) else Modifier,
                        onClick = { selected = m },
                    )
                }
                if (loadingOlder) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(6.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }

            image?.let { uri ->
                Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = uri,
                        contentDescription = "Attached image",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Image attached", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    PadIcon(Icons.Default.Close, "Remove image", { image = null })
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PadIcon(Icons.Default.AttachFile, "Attach image", { picker.launch("image/*") })
                CompactField(
                    value = text,
                    onValueChange = { text = it },
                    hint = "Message",
                    imeAction = ImeAction.Send,
                    onImeAction = { doSend() },
                    modifier = Modifier.weight(1f),
                )
                if (sending) {
                    Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                } else {
                    PadIcon(
                        Icons.AutoMirrored.Filled.Send, "Send", { doSend() },
                        enabled = text.isNotBlank() || image != null,
                    )
                }
            }
        }
    }

    selected?.let { m ->
        val liked = vm.myId in m.likeList()
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(100); runCatching { first.requestFocus() } }
        PadDialog(onDismiss = { selected = null }) {
            if (m.text.isNotBlank()) {
                Text(m.text, style = MaterialTheme.typography.bodySmall, maxLines = 4)
            }
            PadButton(
                if (liked) "Unlike" else "Like",
                { vm.toggleLike(m); selected = null },
                Modifier.fillMaxWidth().focusRequester(first),
            )
            m.imageList().firstOrNull()?.let { url ->
                PadButton("Open image", { openUrl(ctx, url); selected = null }, Modifier.fillMaxWidth(), primary = false)
            }
            if (m.text.isNotBlank()) {
                PadButton(
                    "Copy text",
                    { clipboard.setText(AnnotatedString(m.text)); selected = null },
                    Modifier.fillMaxWidth(),
                    primary = false,
                )
            }
            PadButton("Close", { selected = null }, Modifier.fillMaxWidth(), primary = false)
        }
    }
}

@Composable
private fun MessageBubble(
    m: MessageEntity,
    mine: Boolean,
    showName: Boolean,
    myId: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val ctx = LocalContext.current
    val compact = LocalCompact.current

    if (m.system) {
        Text(
            m.text,
            modifier = modifier.fillMaxWidth().focusRing().focusable().padding(vertical = 2.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val likes = m.likeList()
    val liked = myId in likes
    val screenW = LocalConfiguration.current.screenWidthDp
    val maxW = if (compact) (screenW - 16).dp else 300.dp
    val shape = RoundedCornerShape(12.dp)

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!mine && !compact) {
            Avatar(m.avatarUrl, m.name, 32.dp)
            Spacer(Modifier.width(6.dp))
        }
        // Each bubble is a D-pad focus stop; OK/Enter opens the message actions.
        Column(
            modifier = modifier
                .widthIn(max = maxW)
                .clip(shape)
                .background(
                    if (mine) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .focusRing(shape)
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (!mine && showName && m.name.isNotBlank()) {
                Text(m.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            m.imageList().forEach { url ->
                AsyncImage(
                    model = url,
                    contentDescription = "Image",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.sizeIn(maxHeight = if (compact) 120.dp else 240.dp).clip(RoundedCornerShape(6.dp)),
                )
            }
            if (m.text.isNotBlank()) Text(m.text, style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatTime(ctx, m.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (likes.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Default.Favorite, "Likes",
                        Modifier.size(11.dp),
                        tint = if (liked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(likes.size.toString(), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
