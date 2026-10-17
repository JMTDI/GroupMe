package com.jmtditech.groupme.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.jmtditech.groupme.MainActivity
import com.jmtditech.groupme.R
import com.jmtditech.groupme.data.AppState
import com.jmtditech.groupme.data.ConversationEntity
import com.jmtditech.groupme.data.MessageEntity
import com.jmtditech.groupme.data.Repository
import com.jmtditech.groupme.data.ServiceLocator

object Notifier {
    const val CH_MESSAGES = "messages"
    const val CH_SERVICE = "service"
    const val SERVICE_ID = 1
    const val EXTRA_CONV = "convKey"
    const val ACTION_REPLY = "com.jmtditech.groupme.REPLY"
    const val ACTION_READ = "com.jmtditech.groupme.MARK_READ"
    const val KEY_REPLY = "reply_text"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Background connection", NotificationManager.IMPORTANCE_MIN)
        )
    }

    fun serviceNotification(ctx: Context): Notification {
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("GroupMe Client")
            .setContentText("Listening for new messages")
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    fun cancel(ctx: Context, convKey: String) {
        NotificationManagerCompat.from(ctx).cancel(convKey.hashCode())
    }

    /** Shows a notification for [convKey] unless muted, disabled, or the app is in the foreground. */
    suspend fun maybeNotify(ctx: Context, convKey: String) {
        val sl = ServiceLocator
        if (!sl.prefs.notificationsEnabledNow()) return
        if (AppState.foreground) return
        val conv = sl.db.conversations().get(convKey) ?: return
        if (conv.muted) return
        val n = maxOf(1, minOf(conv.unread, 6))
        val recent = sl.db.messages().recent(convKey, n).reversed()
        if (recent.isEmpty()) return
        show(ctx, conv, recent)
    }

    @SuppressLint("MissingPermission")
    private fun show(ctx: Context, conv: ConversationEntity, recent: List<MessageEntity>) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val myId = ServiceLocator.prefs.userId
        val me = Person.Builder().setName("Me").setKey(myId).build()
        val style = NotificationCompat.MessagingStyle(me).setGroupConversation(conv.type == Repository.GROUP)
        if (conv.type == Repository.GROUP) style.setConversationTitle(conv.name)
        for (m in recent) {
            val who = if (m.senderId == myId) me
            else Person.Builder().setName(m.name.ifBlank { conv.name }).setKey(m.senderId).build()
            style.addMessage(m.preview(), m.createdAt * 1000, who)
        }

        val id = conv.convKey.hashCode()
        val open = PendingIntent.getActivity(
            ctx, id,
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_CONV, conv.convKey)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val replyPi = PendingIntent.getBroadcast(
            ctx, id,
            Intent(ctx, NotifActionReceiver::class.java).setAction(ACTION_REPLY).putExtra(EXTRA_CONV, conv.convKey),
            PendingIntent.FLAG_UPDATE_CURRENT or mutable,
        )
        val readPi = PendingIntent.getBroadcast(
            ctx, id + 1,
            Intent(ctx, NotifActionReceiver::class.java).setAction(ACTION_READ).putExtra(EXTRA_CONV, conv.convKey),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val reply = NotificationCompat.Action.Builder(R.drawable.ic_notification, "Reply", replyPi)
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("Reply").build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setAllowGeneratedReplies(true)
            .build()
        val read = NotificationCompat.Action.Builder(R.drawable.ic_notification, "Mark as read", readPi)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .build()

        val notification = NotificationCompat.Builder(ctx, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(reply)
            .addAction(read)
            .build()
        NotificationManagerCompat.from(ctx).notify(id, notification)
    }
}
