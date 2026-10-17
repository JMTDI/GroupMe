package com.jmtditech.groupme.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.jmtditech.groupme.data.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Handles the "Reply" and "Mark as read" notification actions. */
class NotifActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(Notifier.EXTRA_CONV) ?: return
        val appCtx = context.applicationContext
        val pending = goAsync()
        val action = intent.action
        val replyText = if (action == Notifier.ACTION_REPLY) {
            RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.KEY_REPLY)?.toString()?.trim().orEmpty()
        } else ""

        CoroutineScope(Dispatchers.IO).launch {
            val sl = ServiceLocator
            try {
                if (action == Notifier.ACTION_REPLY && replyText.isNotEmpty()) {
                    val conv = sl.db.conversations().get(key)
                    if (conv != null) sl.repo.send(conv, replyText, null)
                }
                sl.db.conversations().markRead(key)
                Notifier.cancel(appCtx, key)
            } catch (e: Exception) {
                // Send failed: re-post the notification so the reply spinner clears and the user can retry.
                runCatching { Notifier.maybeNotify(appCtx, key) }
            } finally {
                pending.finish()
            }
        }
    }
}
