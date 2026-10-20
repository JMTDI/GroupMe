package com.jmtditech.groupme.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Envelope<T>(val response: T? = null, val meta: Meta? = null)

@Serializable
data class Meta(val code: Int? = null, val errors: List<String>? = null)

@Serializable
data class UserDto(
    val id: String,
    val name: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

@Serializable
data class Attachment(val type: String = "", val url: String? = null)

@Serializable
data class PreviewDto(
    val nickname: String? = null,
    val text: String? = null,
    val attachments: List<Attachment> = emptyList(),
)

@Serializable
data class GroupMsgInfo(
    val count: Int = 0,
    @SerialName("last_message_created_at") val lastMessageCreatedAt: Long = 0,
    val preview: PreviewDto? = null,
)

@Serializable
data class MemberDto(
    @SerialName("user_id") val userId: String,
    val nickname: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
)

@Serializable
data class GroupDto(
    val id: String,
    val name: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("updated_at") val updatedAt: Long = 0,
    val messages: GroupMsgInfo? = null,
    val members: List<MemberDto> = emptyList(),
)

@Serializable
data class ChatDto(
    @SerialName("updated_at") val updatedAt: Long = 0,
    @SerialName("last_message") val lastMessage: MessageDto? = null,
    @SerialName("other_user") val otherUser: UserDto,
)

/** Used for both group messages and direct messages (and push payloads). */
@Serializable
data class MessageDto(
    val id: String,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("sender_id") val senderId: String? = null,
    @SerialName("group_id") val groupId: String? = null,
    @SerialName("recipient_id") val recipientId: String? = null,
    val name: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val text: String? = null,
    val system: Boolean = false,
    @SerialName("favorited_by") val favoritedBy: List<String> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
) {
    val sender: String get() = senderId ?: userId ?: ""
}

@Serializable
data class GroupMessagesResp(val count: Int = 0, val messages: List<MessageDto> = emptyList())

@Serializable
data class DmResp(
    val count: Int = 0,
    @SerialName("direct_messages") val directMessages: List<MessageDto> = emptyList(),
)

@Serializable
data class OutMessage(
    @SerialName("source_guid") val sourceGuid: String,
    val text: String,
    val attachments: List<Attachment> = emptyList(),
)

@Serializable
data class SendGroupBody(val message: OutMessage)

@Serializable
data class OutDm(
    @SerialName("source_guid") val sourceGuid: String,
    @SerialName("recipient_id") val recipientId: String,
    val text: String,
    val attachments: List<Attachment> = emptyList(),
)

@Serializable
data class SendDmBody(@SerialName("direct_message") val directMessage: OutDm)

@Serializable
data class SendGroupResp(val message: MessageDto? = null)

@Serializable
data class SendDmResp(@SerialName("direct_message") val directMessage: MessageDto? = null)

@Serializable
data class UploadResp(val payload: UploadPayload? = null)

@Serializable
data class UploadPayload(
    val url: String? = null,
    @SerialName("picture_url") val pictureUrl: String? = null,
)

@Serializable
data class CreateGroupBody(
    val name: String,
    val description: String? = null,
    val share: Boolean = false,
)

@Serializable
data class MemberAdd(
    val nickname: String,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("phone_number") val phoneNumber: String? = null,
    val email: String? = null,
)

@Serializable
data class AddMembersBody(val members: List<MemberAdd>)
