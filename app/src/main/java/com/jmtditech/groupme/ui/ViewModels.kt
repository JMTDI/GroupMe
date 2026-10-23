package com.jmtditech.groupme.ui

import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jmtditech.groupme.BuildConfig
import com.jmtditech.groupme.data.ContactEntity
import com.jmtditech.groupme.data.ConversationEntity
import com.jmtditech.groupme.data.MessageEntity
import com.jmtditech.groupme.data.Repository
import com.jmtditech.groupme.data.ServiceLocator
import com.jmtditech.groupme.data.UpdateInfo
import com.jmtditech.groupme.service.Notifier
import com.jmtditech.groupme.data.Prefs
import com.jmtditech.groupme.service.PollWorker
import com.jmtditech.groupme.service.PushService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel : ViewModel() {
    private val sl = ServiceLocator

    val signedIn: StateFlow<Boolean?> =
        sl.prefs.signedIn.map<Boolean, Boolean?> { it }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val conversations: StateFlow<List<ConversationEntity>> =
        sl.db.conversations().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val notificationsEnabled: StateFlow<Boolean> =
        sl.prefs.notificationsEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    /** True only in "Instant" mode (foreground service running). */
    val serviceEnabled: StateFlow<Boolean> =
        sl.prefs.serviceEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val bgMode: StateFlow<String> =
        sl.prefs.bgMode.stateIn(viewModelScope, SharingStarted.Eagerly, Prefs.MODE_PERIODIC)
    val clientId: StateFlow<String> =
        sl.prefs.clientId.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val refreshing = MutableStateFlow(false)
    val signingIn = MutableStateFlow(false)
    val loginError = MutableStateFlow<String?>(null)
    val pendingConv = MutableStateFlow<String?>(null)
    val errors = MutableSharedFlow<String>(extraBufferCapacity = 4)

    val userName: String get() = sl.prefs.userName

    /** Handles notification taps (open a chat) and the OAuth redirect (groupmeclient://oauth?access_token=...). */
    fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(Notifier.EXTRA_CONV)?.let { pendingConv.value = it }
        val uri = intent.data
        if (uri != null && uri.scheme == "groupmeclient") {
            uri.getQueryParameter("access_token")?.let { signIn(it) }
        }
    }

    fun signIn(token: String) {
        val t = token.trim()
        if (t.isEmpty()) return
        viewModelScope.launch {
            signingIn.value = true
            loginError.value = null
            try {
                sl.repo.signIn(t)
            } catch (e: Exception) {
                loginError.value = e.message ?: "Sign-in failed"
            } finally {
                signingIn.value = false
            }
        }
    }

    fun saveClientId(id: String) {
        viewModelScope.launch { sl.prefs.setClientId(id.trim()) }
    }

    fun onSignedIn() {
        viewModelScope.launch { applyBackgroundMode(sl.prefs.bgModeNow()) }
        refresh()
    }

    val removing = MutableStateFlow(false)

    fun remove(conv: ConversationEntity, forEveryone: Boolean, onDone: () -> Unit) {
        if (removing.value) return
        viewModelScope.launch {
            removing.value = true
            try {
                sl.repo.removeConversation(conv, forEveryone)
                onDone()
            } catch (e: Exception) {
                errors.tryEmit(e.message ?: "Could not remove this chat")
            } finally {
                removing.value = false
            }
        }
    }

    fun refresh() {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            try {
                sl.repo.pollOnce()
            } catch (e: Exception) {
                errors.tryEmit(e.message ?: "Refresh failed")
            } finally {
                refreshing.value = false
            }
        }
    }

    /** Push connection used only while the UI is visible and the background service is disabled. */
    suspend fun runForegroundPush() {
        val token = sl.prefs.token
        val uid = sl.prefs.userId
        if (token.isNullOrBlank() || uid.isBlank()) return
        var backoff = 2_000L
        while (currentCoroutineContext().isActive) {
            try {
                sl.repo.pollOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
            }
            try {
                sl.faye.listen(uid, token, onConnected = { backoff = 2_000L }) { data -> sl.repo.handlePush(data) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
            }
            delay(backoff)
            backoff = minOf(backoff * 2, 60_000L)
        }
    }

    // ------------------------------------------------------------ updates

    val updatesEnabled: StateFlow<Boolean> =
        sl.prefs.updatesEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateDismissed = MutableStateFlow(false)
    val updateBusy = MutableStateFlow<String?>(null)
    val updateMessage = MutableStateFlow<String?>(null)
    val updateError = MutableStateFlow<String?>(null)
    private var downloaded: Pair<String, java.io.File>? = null

    fun setUpdatesEnabled(v: Boolean) {
        viewModelScope.launch { sl.prefs.setUpdatesEnabled(v) }
    }

    /** Automatic checks run at most every 12 hours; a manual check always runs. */
    fun checkForUpdates(manual: Boolean) {
        if (updateBusy.value != null) return
        viewModelScope.launch {
            if (!manual) {
                if (!sl.prefs.updatesEnabledNow()) return@launch
                if (System.currentTimeMillis() - sl.prefs.lastUpdateCheck() < 12 * 3_600_000L) return@launch
            }
            updateBusy.value = "Checking…"
            try {
                val info = sl.updater.fetchLatest(BuildConfig.UPDATE_REPO)
                sl.prefs.setLastUpdateCheck(System.currentTimeMillis())
                if (info != null && sl.updater.isNewer(info.tag, BuildConfig.VERSION_NAME)) {
                    updateInfo.value = info
                    updateDismissed.value = false
                    updateMessage.value = "Update ${info.tag} available"
                } else {
                    updateInfo.value = null
                    updateMessage.value = "Up to date (${BuildConfig.VERSION_NAME})"
                }
            } catch (e: Exception) {
                if (manual) updateMessage.value = "Could not check: ${e.message}"
            } finally {
                updateBusy.value = null
            }
        }
    }

    fun startUpdate() {
        val info = updateInfo.value ?: return
        if (updateBusy.value != null) return
        viewModelScope.launch {
            updateError.value = null
            try {
                val cached = downloaded?.takeIf { it.first == info.tag && it.second.exists() }?.second
                val file = cached ?: run {
                    updateBusy.value = "Downloading 0%"
                    sl.updater.download(info) { p -> updateBusy.value = "Downloading $p%" }
                        .also { downloaded = info.tag to it }
                }
                if (sl.updater.sameSigner(file) == false) {
                    updateError.value = "This update is signed with a different key than the installed app. " +
                        "Uninstall GroupMe once, then install the new APK."
                    return@launch
                }
                if (!sl.updater.canInstall()) {
                    updateError.value = "Allow GroupMe to install apps on the page that just opened, then press Update again."
                    sl.appContext.startActivity(sl.updater.permissionIntent())
                    return@launch
                }
                sl.appContext.startActivity(sl.updater.installIntent(file))
            } catch (e: Exception) {
                updateError.value = "Update failed: ${e.message}"
            } finally {
                updateBusy.value = null
            }
        }
    }

    fun setNotifications(v: Boolean) {
        viewModelScope.launch { sl.prefs.setNotifications(v) }
    }

    fun setBgMode(mode: String) {
        viewModelScope.launch {
            sl.prefs.setBgMode(mode)
            applyBackgroundMode(mode)
        }
    }

    private fun applyBackgroundMode(mode: String) {
        val ctx = sl.appContext
        when (mode) {
            Prefs.MODE_INSTANT -> { PollWorker.cancel(ctx); PushService.start(ctx) }
            Prefs.MODE_PERIODIC -> { PushService.stop(ctx); PollWorker.schedule(ctx) }
            else -> { PushService.stop(ctx); PollWorker.cancel(ctx) }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            PushService.stop(sl.appContext)
            PollWorker.cancel(sl.appContext)
            withContext(Dispatchers.IO) { sl.db.clearAllTables() }
            NotificationManagerCompat.from(sl.appContext).cancelAll()
            sl.prefs.clearSession()
        }
    }
}

class ChatViewModel(private val convKey: String) : ViewModel() {
    private val sl = ServiceLocator

    val myId: String get() = sl.prefs.userId
    val conv: StateFlow<ConversationEntity?> =
        sl.db.conversations().observe(convKey).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val messages: StateFlow<List<MessageEntity>> =
        sl.db.messages().observeDesc(convKey).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val sending = MutableStateFlow(false)
    val loadingOlder = MutableStateFlow(false)
    val errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    private var exhausted = false
    // A brand-new direct chat has no server-side row yet; remember it so sending still works if the list refreshes.
    private var lastConv: ConversationEntity? = null

    init {
        viewModelScope.launch { conv.collect { if (it != null) lastConv = it } }
        viewModelScope.launch {
            sl.db.conversations().markRead(convKey)
            Notifier.cancel(sl.appContext, convKey)
            val c = sl.db.conversations().get(convKey) ?: return@launch
            // A direct chat that was just started has no history on the server yet.
            if (c.type == Repository.DM && c.lastAt == 0L) return@launch
            try {
                sl.repo.refreshMessages(c)
            } catch (e: Exception) {
                errors.tryEmit(e.message ?: "Network error")
            }
        }
    }

    fun loadOlder() {
        if (exhausted || loadingOlder.value) return
        val c = conv.value ?: return
        viewModelScope.launch {
            loadingOlder.value = true
            try {
                if (sl.repo.loadOlder(c) == 0) exhausted = true
            } catch (e: Exception) {
                errors.tryEmit(e.message ?: "Could not load older messages")
                exhausted = true
            } finally {
                loadingOlder.value = false
            }
        }
    }

    fun send(text: String, image: Uri?, onResult: (Boolean) -> Unit) {
        val c = conv.value ?: lastConv ?: return
        viewModelScope.launch {
            sending.value = true
            try {
                sl.repo.send(c, text, image)
                onResult(true)
            } catch (e: Exception) {
                errors.tryEmit(e.message ?: "Send failed")
                onResult(false)
            } finally {
                sending.value = false
            }
        }
    }

    fun toggleLike(m: MessageEntity) {
        val c = conv.value ?: return
        viewModelScope.launch {
            try {
                sl.repo.toggleLike(c, m)
            } catch (e: Exception) {
                errors.tryEmit(e.message ?: "Could not update like")
            }
        }
    }

    val removing = MutableStateFlow(false)

    fun remove(forEveryone: Boolean, onDone: () -> Unit) {
        val c = conv.value ?: lastConv ?: return
        if (removing.value) return
        viewModelScope.launch {
            removing.value = true
            try {
                sl.repo.removeConversation(c, forEveryone)
                onDone()
            } catch (e: Exception) {
                errors.tryEmit(e.message ?: "Could not remove this chat")
            } finally {
                removing.value = false
            }
        }
    }

    fun toggleMute() {
        val c = conv.value ?: return
        viewModelScope.launch { sl.repo.setMuted(convKey, !c.muted) }
    }
}

class SearchViewModel : ViewModel() {
    val results = MutableStateFlow<List<Pair<ConversationEntity?, MessageEntity>>>(emptyList())

    fun search(q: String) {
        viewModelScope.launch {
            results.value = try {
                ServiceLocator.repo.search(q)
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}

class NewChatViewModel : ViewModel() {
    private val sl = ServiceLocator

    val contacts: StateFlow<List<ContactEntity>> =
        sl.db.contacts().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val creating = MutableStateFlow(false)
    val errors = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** GroupMe users open a direct chat; saved phone/email contacts can only be invited into a group. */
    fun open(contact: ContactEntity, onDirect: (String) -> Unit, onInvite: (String) -> Unit) {
        if (contact.userId == null) {
            toast("Direct messages need a GroupMe account. Starting a new group with ${contact.name.ifBlank { "them" }}.")
            onInvite(contact.contactKey)
            return
        }
        viewModelScope.launch {
            try {
                onDirect(sl.repo.startDirect(contact).convKey)
            } catch (e: Exception) {
                errors.tryEmit(e.message ?: "Could not open chat")
            }
        }
    }

    fun saveContact(name: String, value: String, onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                sl.repo.saveContact(name, value)
                onDone()
            } catch (e: Exception) {
                errors.tryEmit(e.message ?: "Could not save contact")
            }
        }
    }

    fun deleteContact(key: String) {
        viewModelScope.launch { sl.repo.deleteContact(key) }
    }

    fun importPhone() {
        viewModelScope.launch {
            try {
                toast("Imported ${sl.repo.importPhoneContacts()} contacts")
            } catch (e: SecurityException) {
                errors.tryEmit("Contacts permission was not granted")
            } catch (e: Exception) {
                errors.tryEmit(e.message ?: "Could not import contacts")
            }
        }
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(sl.appContext, msg, android.widget.Toast.LENGTH_LONG).show()
    }

    fun createGroup(
        name: String,
        description: String,
        members: List<ContactEntity>,
        extras: List<String>,
        onOpen: (String) -> Unit,
    ) {
        if (creating.value) return
        viewModelScope.launch {
            creating.value = true
            try {
                val r = sl.repo.createGroup(name, description, members, extras)
                if (r.memberError != null) {
                    android.widget.Toast.makeText(
                        sl.appContext, "Group created, but adding people failed: ${r.memberError}",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
                onOpen(r.conv.convKey)
            } catch (e: Exception) {
                errors.tryEmit(e.message ?: "Could not create group")
            } finally {
                creating.value = false
            }
        }
    }
}
