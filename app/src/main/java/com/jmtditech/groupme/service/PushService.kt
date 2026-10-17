package com.jmtditech.groupme.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.jmtditech.groupme.data.ServiceLocator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Foreground service that keeps the Faye push connection open. While the socket is down it
 * falls back to polling the REST API once a minute, and it catches up on every (re)connect.
 */
class PushService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, Notifier.SERVICE_ID, Notifier.serviceNotification(this), type)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (job?.isActive != true) job = scope.launch { runLoop() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun poll() {
        val keys = ServiceLocator.repo.pollOnce().distinct()
        for (k in keys) Notifier.maybeNotify(this, k)
    }

    private suspend fun runLoop() = coroutineScope {
        val sl = ServiceLocator
        // Fallback poller: only does work while the push socket is down.
        launch {
            while (isActive) {
                delay(60_000)
                if (!sl.faye.connected && !sl.prefs.token.isNullOrBlank()) runCatching { poll() }
            }
        }
        var backoff = 2_000L
        while (isActive) {
            val token = sl.prefs.token
            val uid = sl.prefs.userId
            if (token.isNullOrBlank() || uid.isBlank()) {
                stopSelf()
                return@coroutineScope
            }
            runCatching { poll() }
            try {
                sl.faye.listen(
                    userId = uid,
                    token = token,
                    onConnected = { backoff = 2_000L },
                ) { data ->
                    val hit = sl.repo.handlePush(data)
                    if (hit != null) Notifier.maybeNotify(this@PushService, hit.first.convKey)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // fall through to backoff + reconnect
            }
            delay(backoff)
            backoff = minOf(backoff * 2, 60_000L)
        }
    }

    companion object {
        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, PushService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, PushService::class.java))
        }

        fun startIfEnabled(ctx: Context) {
            val sl = ServiceLocator
            if (sl.prefs.token.isNullOrBlank()) return
            val enabled = runBlocking { sl.prefs.serviceEnabled.first() }
            if (enabled) runCatching { start(ctx) }
        }
    }
}
