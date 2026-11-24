package com.guardstar.app.data.sos

import com.guardstar.app.core.net.ApiClient
import kotlinx.serialization.Serializable

@Serializable
data class SosLocation(
    val lat: Double,
    val lng: Double,
    val accuracyM: Double,
    val recordedAt: String,
)

@Serializable
data class SosCreateRequest(
    val clientEventId: String,
    val location: SosLocation,
    val battery: Int? = null,
)

@Serializable
data class SosCreateResponse(
    val sosId: String,
    val status: String = "",
    val deliveredTo: Int = 0,
)

@Serializable
data class SosAckRequest(
    val action: String,
    val reason: String = "",
)

@Serializable
data class SosAckResponse(
    val sosId: String,
    val status: String = "",
    val handledBy: String = "",
    val handledAt: String = "",
)

class SosApi(private val api: ApiClient) {

    /** 被守护端发起一键求助。clientEventId 用于幂等（建议 UUID）。 */
    suspend fun create(
        relationId: String,
        clientEventId: String,
        location: SosLocation,
        battery: Int? = null,
    ): SosCreateResponse = api.post(
        path = "/v1/relations/$relationId/sos",
        body = SosCreateRequest(clientEventId, location, battery),
        serializer = SosCreateRequest.serializer(),
        deserializer = SosCreateResponse.serializer(),
    )

    /** 监护人确认求助：action = handled / false_alarm（误报需填 reason）。 */
    suspend fun ack(sosId: String, action: String, reason: String = ""): SosAckResponse = api.post(
        path = "/v1/sos/$sosId/ack",
        body = SosAckRequest(action, reason),
        serializer = SosAckRequest.serializer(),
        deserializer = SosAckResponse.serializer(),
    )
}
