package com.jmtditech.groupme.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** type 0 = group (convKey "g:<groupId>"), type 1 = direct chat (convKey "d:<otherUserId>"). */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val convKey: String,
    val type: Int,
    val targetId: String,
    val name: String,
    val imageUrl: String?,
    val lastText: String,
    val lastAt: Long,
    val muted: Boolean = false,
    val unread: Int = 0,
)

/**
 * People you can chat with. Two kinds:
 *  - GroupMe users (userId set): members of your groups / existing chats. Can be messaged directly.
 *  - Saved or imported contacts (local = true, userId null): a phone number or email. GroupMe can only invite
 *    these into a group; a direct chat needs a GroupMe user id.
 * contactKey is "u:<userId>", "p:<phone>" or "e:<email>".
 */
@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val contactKey: String,
    val userId: String?,
    val name: String,
    val imageUrl: String?,
    val phone: String? = null,
    val email: String? = null,
    val local: Boolean = false,
)

@Entity(tableName = "messages", indices = [Index("convKey"), Index("createdAt")])
data class MessageEntity(
    @PrimaryKey val id: String,
    val convKey: String,
    val createdAt: Long,
    val senderId: String,
    val name: String,
    val avatarUrl: String?,
    val text: String,
    val images: String,
    val likedBy: String,
    val system: Boolean,
) {
    fun imageList(): List<String> = if (images.isBlank()) emptyList() else images.split("\n")
    fun likeList(): List<String> = if (likedBy.isBlank()) emptyList() else likedBy.split(",")
    fun preview(): String = text.ifBlank { if (images.isNotBlank()) "[image]" else "" }
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY lastAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE convKey = :key")
    fun observe(key: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE convKey = :key")
    suspend fun get(key: String): ConversationEntity?

    @Query("SELECT * FROM conversations")
    suspend fun getAll(): List<ConversationEntity>

    @Upsert
    suspend fun upsertAll(list: List<ConversationEntity>)

    @Query("DELETE FROM conversations WHERE convKey NOT IN (:keys)")
    suspend fun deleteNotIn(keys: List<String>)

    @Query("UPDATE conversations SET unread = 0 WHERE convKey = :key")
    suspend fun markRead(key: String)

    @Query("UPDATE conversations SET muted = :muted WHERE convKey = :key")
    suspend fun setMuted(key: String, muted: Boolean)

    @Query("UPDATE conversations SET lastText = :text, lastAt = :at, unread = unread + :inc WHERE convKey = :key AND :at >= lastAt")
    suspend fun bump(key: String, text: String, at: Long, inc: Int)
}

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<ContactEntity>>

    @Upsert
    suspend fun upsertAll(list: List<ContactEntity>)

    @Query("DELETE FROM contacts WHERE contactKey = :key")
    suspend fun delete(key: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE convKey = :key ORDER BY createdAt DESC, id DESC")
    fun observeDesc(key: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE convKey = :key ORDER BY createdAt DESC, id DESC LIMIT :n")
    suspend fun recent(key: String, n: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE convKey = :key ORDER BY createdAt DESC, id DESC LIMIT 1")
    suspend fun newest(key: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE convKey = :key ORDER BY createdAt ASC, id ASC LIMIT 1")
    suspend fun oldest(key: String): MessageEntity?

    @Query("SELECT id FROM messages WHERE id IN (:ids)")
    suspend fun existing(ids: List<String>): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<MessageEntity>)

    @Query("UPDATE messages SET likedBy = :likedBy WHERE id = :id")
    suspend fun setLikes(id: String, likedBy: String)

    @Query("SELECT * FROM messages WHERE text LIKE '%' || :q || '%' ORDER BY createdAt DESC LIMIT 200")
    suspend fun search(q: String): List<MessageEntity>
}

@Database(entities = [ConversationEntity::class, MessageEntity::class, ContactEntity::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao
    abstract fun contacts(): ContactDao
}
