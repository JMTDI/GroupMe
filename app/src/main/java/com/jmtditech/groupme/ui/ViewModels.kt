package com.jmtditech.groupme.ui

import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jmtditech.groupme.data.ConversationEntity
import com.jmtditech.groupme.data.MessageEntity
import com.jmtditech.groupme.data.ServiceLocator
import com.jmtditech.groupme.service.Notifier
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
    val serviceEnabled: StateFlow<Boolean> =
        sl.prefs.serviceEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)
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
        viewModelScope.launch {
            if (sl.prefs.serviceEnabledNow()) PushService.start(sl.appContext)
        }
        refresh()
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

    fun setNotifications(v: Boolean) {
        viewModelScope.launch { sl.prefs.setNotifications(v) }
    }

    fun setService(v: Boolean) {
        viewModelScope.launch {
            sl.prefs.setServiceEnabled(v)
            if (v) PushService.start(sl.appContext) else PushService.stop(sl.appContext)
        }
    }

    fun signOut() {
        viewModelScope.launch {
            PushService.stop(sl.appContext)
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

    init {
        viewModelScope.launch {
            sl.db.conversations().markRead(convKey)
            Notifier.cancel(sl.appContext, convKey)
            val c = sl.db.conversations().get(convKey) ?: return@launch
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
        val c = conv.value ?: return
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
