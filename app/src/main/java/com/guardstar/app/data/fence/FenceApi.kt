package com.guardstar.app.data.fence

import com.guardstar.app.core.net.ApiClient
import kotlinx.serialization.Serializable

@Serializable
data class Fence(
    val fenceId: String,
    val relationId: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val radiusM: Int,
    /** safe = 安全区（离开时告警），danger = 危险区（进入时告警） */
    val type: String,
    val enabled: Boolean,
    val createdAt: String = "",
)

@Serializable
data class FenceCenter(val lat: Double, val lng: Double)

@Serializable
data class FenceCreateRequest(
    val name: String,
    val center: FenceCenter,
    val radiusM: Int,
    val type: String,
    val enabled: Boolean? = null,
)

@Serializable
data class FenceUpdateRequest(
    val name: String? = null,
    val center: FenceCenter? = null,
    val radiusM: Int? = null,
    val type: String? = null,
    val enabled: Boolean? = null,
)

@Serializable
data class FenceListResponse(val items: List<Fence> = emptyList())

class FenceApi(private val api: ApiClient) {

    suspend fun list(relationId: String): List<Fence> = api.get(
        path = "/v1/relations/$relationId/fences",
        deserializer = FenceListResponse.serializer(),
    ).items

    suspend fun create(relationId: String, req: FenceCreateRequest): Fence = api.post(
        path = "/v1/relations/$relationId/fences",
        body = req,
        serializer = FenceCreateRequest.serializer(),
        deserializer = Fence.serializer(),
    )

    suspend fun update(fenceId: String, req: FenceUpdateRequest): Fence = api.patch(
        path = "/v1/fences/$fenceId",
        body = req,
        serializer = FenceUpdateRequest.serializer(),
        deserializer = Fence.serializer(),
    )

    suspend fun delete(fenceId: String) {
        api.delete(path = "/v1/fences/$fenceId")
    }
}
