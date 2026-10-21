package com.jmtditech.groupme.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import java.util.UUID

class Repository(
    private val api: GroupMeApi,
    private val db: AppDatabase,
    private val prefs: Prefs,
    private val ctx: Context,
    private val json: Json,
) {
    companion object {
        const val GROUP = 0
        const val DM = 1
    }

    val myId: String get() = prefs.userId

    // ---------------------------------------------------------------- auth

    suspend fun signIn(token: String) {
        prefs.setTokenTemp(token)
        try {
            val me = api.me().response ?: error("Unexpected response from GroupMe")
            prefs.saveSession(token, me.id, me.name ?: "")
        } catch (e: Exception) {
            prefs.setTokenTemp(null)
            throw e
        }
    }

    // ------------------------------------------------------- conversations

    private suspend fun <T> pages(fetch: suspend (Int) -> List<T>): List<T> {
        val out = mutableListOf<T>()
        for (p in 1..5) {
            val r = fetch(p)
            out += r
            if (r.size < 50) break
        }
        return out
    }

    suspend fun refreshConversations() = withContext(Dispatchers.IO) {
        val existing = db.conversations().getAll().associateBy { it.convKey }
        val groups = pages { api.groups(it, 50).response.orEmpty() }
        val chats = pages { api.chats(it, 50).response.orEmpty() }

        val list = groups.map { g ->
            val key = "g:${g.id}"
            val p = g.messages?.preview
            val preview = when {
                p == null -> ""
                !p.text.isNullOrBlank() -> (p.nickname?.let { "$it: " } ?: "") + p.text
                p.attachments.isNotEmpty() -> (p.nickname?.let { "$it: " } ?: "") + "[attachment]"
                else -> ""
            }
            val old = existing[key]
            ConversationEntity(
                convKey = key, type = GROUP, targetId = g.id,
                name = g.name ?: "Group", imageUrl = g.imageUrl, lastText = preview,
                lastAt = g.messages?.lastMessageCreatedAt?.takeIf { it > 0 } ?: g.updatedAt,
                muted = old?.muted ?: false, unread = old?.unread ?: 0,
            )
        } + chats.map { c ->
            val key = "d:${c.otherUser.id}"
            val old = existing[key]
            val last = c.lastMessage
            ConversationEntity(
                convKey = key, type = DM, targetId = c.otherUser.id,
                name = c.otherUser.name ?: "Direct chat",
                imageUrl = c.otherUser.avatarUrl ?: c.otherUser.imageUrl,
                lastText = last?.let { it.text?.takeIf { t -> t.isNotBlank() } ?: if (it.attachments.isNotEmpty()) "[image]" else "" } ?: "",
                lastAt = last?.createdAt ?: c.updatedAt,
                muted = old?.muted ?: false, unread = old?.unread ?: 0,
            )
        }
        db.conversations().upsertAll(list)
        val me = myId
        val contacts = (
            chats.map {
                ContactEntity("u:${it.otherUser.id}", it.otherUser.id, it.otherUser.name.orEmpty(), it.otherUser.avatarUrl ?: it.otherUser.imageUrl)
            } + groups.flatMap { g ->
                g.members.map { ContactEntity("u:${it.userId}", it.userId, it.nickname.orEmpty(), it.imageUrl) }
            }
            )
            .filter { !it.userId.isNullOrBlank() && it.userId != me }
            .distinctBy { it.contactKey }
        db.contacts().upsertAll(contacts)
        val keys = list.map { it.convKey }
        if (keys.isNotEmpty()) db.conversations().deleteNotIn(keys)
    }

    suspend fun setMuted(convKey: String, muted: Boolean) = db.conversations().setMuted(convKey, muted)

    // ------------------------------------------------------------ messages

    private fun MessageDto.toEntity(convKey: String): MessageEntity {
        val imgs = attachments.filter { it.type == "image" && !it.url.isNullOrBlank() }.mapNotNull { it.url }
        var body = text.orEmpty()
        if (body.isBlank() && imgs.isEmpty() && attachments.isNotEmpty()) body = "[${attachments.first().type.ifBlank { "attachment" }}]"
        return MessageEntity(
            id = id, convKey = convKey, createdAt = createdAt, senderId = sender,
            name = name.orEmpty(), avatarUrl = avatarUrl, text = body,
            images = imgs.joinToString("\n"), likedBy = favoritedBy.joinToString(","), system = system,
        )
    }

    private suspend fun fetch(c: ConversationEntity, beforeId: String?, afterId: String?, limit: Int): List<MessageDto> {
        if (c.type == GROUP) {
            val r = api.groupMessages(c.targetId, limit, beforeId, afterId)
            if (r.code() == 304) return emptyList()
            if (!r.isSuccessful) throw HttpException(r)
            return r.body()?.response?.messages.orEmpty()
        }
        val r = api.directMessages(c.targetId, beforeId, afterId)
        if (r.code() == 304) return emptyList()
        if (!r.isSuccessful) throw HttpException(r)
        return r.body()?.response?.directMessages.orEmpty()
    }

    /** Insert without touching unread counts (history, or messages the user is looking at). */
    private suspend fun store(convKey: String, dtos: List<MessageDto>) {
        if (dtos.isEmpty()) return
        db.messages().insertAll(dtos.map { it.toEntity(convKey) })
    }

    /** Insert live messages, bump the conversation, and return newly seen incoming ones. */
    private suspend fun ingestLive(convKey: String, dtos: List<MessageDto>): List<MessageEntity> {
        if (dtos.isEmpty()) return emptyList()
        val entities = dtos.map { it.toEntity(convKey) }
        val known = db.messages().existing(entities.map { it.id }).toSet()
        db.messages().insertAll(entities)
        val incoming = entities.filter { it.id !in known && it.senderId != myId && !it.system }
        val newest = entities.maxWith(compareBy({ it.createdAt }, { it.id }))
        val open = AppState.foreground && AppState.openConvKey == convKey
        db.conversations().bump(convKey, newest.preview(), newest.createdAt, if (open) 0 else incoming.size)
        return incoming
    }

    private suspend fun forwardFrom(c: ConversationEntity, startId: String): List<MessageDto> {
        val out = mutableListOf<MessageDto>()
        var cursor = startId
        var rounds = 0
        while (rounds++ < 5) {
            val page = fetch(c, null, cursor, 100)
            if (page.isEmpty()) break
            out += page
            val next = page.maxWith(compareBy({ it.createdAt }, { it.id })).id
            if (next == cursor) break
            cursor = next
            if (page.size < 20) break
        }
        return out
    }

    /** Called when a chat is opened: load the latest page or catch up from the newest stored message. */
    suspend fun refreshMessages(c: ConversationEntity) = withContext(Dispatchers.IO) {
        val newest = db.messages().newest(c.convKey)
        val dtos = if (newest == null) fetch(c, null, null, 50) else forwardFrom(c, newest.id)
        store(c.convKey, dtos)
    }

    /** Returns the number of messages fetched, or -1 if nothing is stored yet. */
    suspend fun loadOlder(c: ConversationEntity): Int = withContext(Dispatchers.IO) {
        val oldest = db.messages().oldest(c.convKey) ?: return@withContext -1
        val page = fetch(c, oldest.id, null, 50)
        store(c.convKey, page)
        page.size
    }

    /**
     * Refresh the conversation list and fetch messages for conversations with new activity.
     * Returns convKeys that received new incoming messages (for notifications).
     */
    suspend fun pollOnce(): List<String> = withContext(Dispatchers.IO) {
        val before = db.conversations().getAll().associate { it.convKey to it.lastAt }
        refreshConversations()
        if (before.isEmpty()) return@withContext emptyList()
        val notify = mutableListOf<String>()
        for (c in db.conversations().getAll()) {
            val prev = before[c.convKey] ?: 0L
            if (c.lastAt <= prev) continue
            val newest = db.messages().newest(c.convKey)
            val dtos = if (newest == null) fetch(c, null, null, 50) else forwardFrom(c, newest.id)
            val (newer, older) = dtos.partition { it.createdAt > prev }
            store(c.convKey, older)
            if (ingestLive(c.convKey, newer).isNotEmpty()) notify += c.convKey
        }
        notify
    }

    // -------------------------------------------------------------- sending

    suspend fun uploadImage(uri: Uri): String = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val mime = cr.getType(uri) ?: "image/jpeg"
        val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot read the selected image")
        require(bytes.size <= 15_000_000) { "Image is too large (15 MB max)" }
        val resp = api.upload(bytes.toRequestBody(mime.toMediaType()))
        resp.payload?.pictureUrl ?: resp.payload?.url ?: error("Image upload failed")
    }

    suspend fun send(c: ConversationEntity, text: String, imageUri: Uri?) = withContext(Dispatchers.IO) {
        val url = imageUri?.let { uploadImage(it) }
        val atts = if (url != null) listOf(Attachment("image", url)) else emptyList()
        val guid = UUID.randomUUID().toString()
        val sent: MessageDto? = if (c.type == GROUP) {
            api.sendGroup(c.targetId, SendGroupBody(OutMessage(guid, text, atts))).response?.message
        } else {
            api.sendDm(SendDmBody(OutDm(guid, c.targetId, text, atts))).response?.directMessage
        }
        val dto = sent ?: error("GroupMe did not accept the message")
        val entity = dto.toEntity(c.convKey)
        db.messages().insertAll(listOf(entity))
        // A new direct chat only exists locally until the first message is sent; make sure it has a row.
        if (db.conversations().get(c.convKey) == null) {
            db.conversations().upsertAll(listOf(c.copy(lastText = entity.preview(), lastAt = entity.createdAt)))
        } else {
            db.conversations().bump(c.convKey, entity.preview(), entity.createdAt, 0)
        }
    }

    // --------------------------------------------------- new chats

    /** Opens (or prepares) a direct chat with [contact]; it becomes real on the first message. */
    suspend fun startDirect(contact: ContactEntity): ConversationEntity = withContext(Dispatchers.IO) {
        val uid = contact.userId ?: error("This contact has no GroupMe account yet")
        val key = "d:$uid"
        db.conversations().get(key) ?: ConversationEntity(
            convKey = key, type = DM, targetId = uid,
            name = contact.name.ifBlank { "Direct chat" }, imageUrl = contact.imageUrl,
            lastText = "", lastAt = 0,
        ).also { db.conversations().upsertAll(listOf(it)) }
    }

    // ------------------------------------------------------ contacts

    /** GroupMe wants phone numbers like "+1 2025550123". */
    private fun normalizePhone(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return when {
            digits.length == 10 -> "+1 $digits"
            digits.length == 11 && digits.startsWith("1") -> "+1 ${digits.substring(1)}"
            raw.trim().startsWith("+") -> "+$digits"
            else -> digits
        }
    }

    /** Saves a contact by phone number or email (anything containing '@' is treated as an email). */
    suspend fun saveContact(name: String, phoneOrEmail: String) = withContext(Dispatchers.IO) {
        val v = phoneOrEmail.trim()
        val c = if ('@' in v) {
            ContactEntity("e:${v.lowercase()}", null, name.trim(), null, null, v, true)
        } else {
            val p = normalizePhone(v)
            require(p.count { it.isDigit() } >= 7) { "Enter a valid phone number or email" }
            ContactEntity("p:$p", null, name.trim(), null, p, null, true)
        }
        db.contacts().upsertAll(listOf(c))
    }

    suspend fun deleteContact(key: String) = withContext(Dispatchers.IO) { db.contacts().delete(key) }

    /** Imports names with phone numbers / emails from the phone's address book (needs READ_CONTACTS). */
    suspend fun importPhoneContacts(): Int = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val out = LinkedHashMap<String, ContactEntity>()
        val nameCol = android.provider.ContactsContract.Contacts.DISPLAY_NAME
        cr.query(
            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(nameCol, android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null,
        )?.use { cur ->
            while (cur.moveToNext()) {
                val name = cur.getString(0).orEmpty().trim()
                val num = cur.getString(1).orEmpty()
                if (name.isBlank() || num.count { it.isDigit() } < 7) continue
                val p = normalizePhone(num)
                out.putIfAbsent("p:$p", ContactEntity("p:$p", null, name, null, p, null, true))
            }
        }
        cr.query(
            android.provider.ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(nameCol, android.provider.ContactsContract.CommonDataKinds.Email.ADDRESS),
            null, null, null,
        )?.use { cur ->
            while (cur.moveToNext()) {
                val name = cur.getString(0).orEmpty().trim()
                val mail = cur.getString(1).orEmpty().trim()
                if (name.isBlank() || '@' !in mail) continue
                out.putIfAbsent("e:${mail.lowercase()}", ContactEntity("e:${mail.lowercase()}", null, name, null, null, mail, true))
            }
        }
        db.contacts().upsertAll(out.values.toList())
        out.size
    }

    class CreateResult(val conv: ConversationEntity, val memberError: String?)

    suspend fun createGroup(
        name: String,
        description: String,
        members: List<ContactEntity>,
        extras: List<String>,
    ): CreateResult = withContext(Dispatchers.IO) {
        val g = api.createGroup(CreateGroupBody(name, description.ifBlank { null })).response
            ?: error("GroupMe did not create the group")
        val conv = ConversationEntity(
            convKey = "g:${g.id}", type = GROUP, targetId = g.id, name = g.name ?: name,
            imageUrl = g.imageUrl, lastText = "",
            lastAt = maxOf(g.createdAt, System.currentTimeMillis() / 1000),
        )
        db.conversations().upsertAll(listOf(conv))

        val adds = members.map {
            val byId = it.userId != null
            MemberAdd(
                nickname = it.name.ifBlank { "Member" },
                userId = it.userId,
                phoneNumber = if (byId) null else it.phone,
                email = if (byId) null else it.email,
            )
        } +
            extras.map { e ->
                if ('@' in e) MemberAdd(nickname = e.substringBefore('@'), email = e)
                else MemberAdd(nickname = e, phoneNumber = e)
            }
        var memberError: String? = null
        if (adds.isNotEmpty()) {
            try {
                val r = api.addMembers(g.id, AddMembersBody(adds))
                if (!r.isSuccessful) memberError = "HTTP ${r.code()}"
            } catch (e: Exception) {
                memberError = e.message ?: "unknown error"
            }
        }
        CreateResult(conv, memberError)
    }

    private fun likeConversationId(c: ConversationEntity): String =
        if (c.type == GROUP) c.targetId
        else listOf(myId, c.targetId).sortedBy { it.toLongOrNull() ?: 0L }.joinToString("+")

    suspend fun toggleLike(c: ConversationEntity, m: MessageEntity) = withContext(Dispatchers.IO) {
        val likes = m.likeList().toMutableList()
        val liked = myId in likes
        val r = if (liked) api.unlike(likeConversationId(c), m.id) else api.like(likeConversationId(c), m.id)
        if (!r.isSuccessful) throw HttpException(r)
        if (liked) likes.remove(myId) else likes.add(myId)
        db.messages().setLikes(m.id, likes.joinToString(","))
    }

    // ----------------------------------------------------------- search

    suspend fun search(q: String): List<Pair<ConversationEntity?, MessageEntity>> = withContext(Dispatchers.IO) {
        if (q.isBlank()) return@withContext emptyList()
        val convs = db.conversations().getAll().associateBy { it.convKey }
        db.messages().search(q.trim()).map { convs[it.convKey] to it }
    }

    // --------------------------------------------------------------- push

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull

    /** Handle a Faye data payload; returns (conversation, message) if a new incoming message was stored. */
    suspend fun handlePush(data: JsonObject): Pair<ConversationEntity, MessageEntity>? = withContext(Dispatchers.IO) {
        val type = data.str("type")
        val subject = data["subject"] as? JsonObject ?: return@withContext null
        when (type) {
            "line.create", "direct_message.create" -> {
                val dto = runCatching { json.decodeFromJsonElement(MessageDto.serializer(), subject) }.getOrNull()
                    ?: return@withContext null
                val key = if (type == "line.create") {
                    "g:" + (dto.groupId ?: return@withContext null)
                } else {
                    val chatId = subject.str("chat_id")
                    val other = when {
                        dto.sender.isNotBlank() && dto.sender != myId -> dto.sender
                        !dto.recipientId.isNullOrBlank() -> dto.recipientId
                        else -> chatId?.split("+")?.firstOrNull { it != myId }
                    }
                    "d:" + (other ?: return@withContext null)
                }
                var conv = db.conversations().get(key)
                if (conv == null) {
                    runCatching { refreshConversations() }
                    conv = db.conversations().get(key)
                }
                if (conv == null) {
                    conv = ConversationEntity(
                        convKey = key, type = if (type == "line.create") GROUP else DM,
                        targetId = key.substring(2), name = dto.name ?: "Chat", imageUrl = dto.avatarUrl,
                        lastText = "", lastAt = 0,
                    )
                    db.conversations().upsertAll(listOf(conv))
                }
                val target: ConversationEntity = conv
                val fresh = ingestLive(key, listOf(dto))
                val first = fresh.firstOrNull()
                if (first == null) null else Pair(target, first)
            }
            "favorite" -> {
                val line = subject["line"] as? JsonObject ?: return@withContext null
                val dto = runCatching { json.decodeFromJsonElement(MessageDto.serializer(), line) }.getOrNull()
                    ?: return@withContext null
                db.messages().setLikes(dto.id, dto.favoritedBy.joinToString(","))
                null
            }
            else -> null
        }
    }
}
