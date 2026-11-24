package com.guardstar.app.data.relation

import com.guardstar.app.core.device.DeviceMeta
import com.guardstar.app.core.net.ApiClient
import com.guardstar.app.core.session.SessionStore
import com.guardstar.app.data.model.RelationSummary
import kotlinx.serialization.Serializable

@Serializable
data class RelationListResponse(val items: List<RelationSummary> = emptyList())

@Serializable
data class CreateInviteRequest(val wardNickname: String = "")

@Serializable
data class InviteResponse(
    val inviteCode: String,
    val qrPayload: String = "",
    val expiresAt: String = "",
)

@Serializable
data class AcceptDevice(
    val deviceId: String,
    val model: String,
    val osVersion: String,
    val appVersion: String,
)

@Serializable
data class AcceptInviteRequest(
    val code: String,
    val confirm: Boolean = true,
    val device: AcceptDevice,
)

@Serializable
data class AcceptInviteResponse(
    val relationId: String,
    val ownerNickname: String = "",
    val status: String = "active",
)

class RelationApi(
    private val api: ApiClient,
    private val session: SessionStore,
) {

    suspend fun list(): List<RelationSummary> = api.get(
        path = "/v1/relations",
        deserializer = RelationListResponse.serializer(),
    ).items

    suspend fun createInvite(wardNickname: String): InviteResponse = api.post(
        path = "/v1/relations/invites",
        body = CreateInviteRequest(wardNickname),
        serializer = CreateInviteRequest.serializer(),
        deserializer = InviteResponse.serializer(),
    )

    suspend fun acceptInvite(code: String): AcceptInviteResponse = api.post(
        path = "/v1/relations/invites/accept",
        body = AcceptInviteRequest(
            code = code,
            confirm = true,
            device = AcceptDevice(
                deviceId = session.deviceId,
                model = DeviceMeta.model,
                osVersion = DeviceMeta.osVersion,
                appVersion = DeviceMeta.appVersion,
            ),
        ),
        serializer = AcceptInviteRequest.serializer(),
        deserializer = AcceptInviteResponse.serializer(),
    )

    suspend fun delete(relationId: String) {
        api.delete("/v1/relations/$relationId")
    }
}
