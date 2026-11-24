package com.guardstar.app.data.event

import com.guardstar.app.core.net.ApiClient
import kotlinx.serialization.Serializable

@Serializable
private object EmptyBody

@Serializable
data class EventItem(
    val eventId: String,
    val type: String,
    val level: String,
    val occurredAt: String,
    val read: Boolean = false,
    val payload: String = "",
)

@Serializable
data class EventListResponse(
    val items: List<EventItem> = emptyList(),
    val nextCursor: String = "",
)

class EventApi(private val api: ApiClient) {

    /** 拉取关系下的事件列表。type 可选（如 sos_created），cursor 分页。 */
    suspend fun list(
        relationId: String,
        type: String = "",
        cursor: String = "",
        limit: Int = 20,
    ): EventListResponse {
        val path = buildString {
            append("/v1/relations/$relationId/events?limit=$limit")
            if (type.isNotBlank()) append("&type=").append(type)
            if (cursor.isNotBlank()) append("&cursor=").append(cursor)
        }
        return api.get(path, EventListResponse.serializer())
    }

    /** 标记事件已读。 */
    suspend fun markRead(eventId: String) {
        api.postForUnit(
            path = "/v1/events/$eventId/read",
            body = EmptyBody,
            serializer = EmptyBody.serializer(),
        )
    }
}
