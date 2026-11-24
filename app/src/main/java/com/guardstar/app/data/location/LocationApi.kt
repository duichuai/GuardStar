package com.guardstar.app.data.location

import com.guardstar.app.core.net.ApiClient
import kotlinx.serialization.Serializable

@Serializable
data class UploadPoint(
    val clientSeq: Int,
    val recordedAt: String,
    val lat: Double,
    val lng: Double,
    val accuracyM: Double,
    val source: String,
    val battery: Int,
    val charging: Boolean,
    val backfill: Boolean,
)

@Serializable
data class UploadRequest(val points: List<UploadPoint>)

@Serializable
data class RejectedPoint(val clientSeq: Int, val reason: String = "")

@Serializable
data class UploadResponse(
    val accepted: Int = 0,
    val duplicated: Int = 0,
    val rejected: List<RejectedPoint> = emptyList(),
)

@Serializable
data class DeviceStatusRequest(
    val battery: Int,
    val charging: Boolean,
    val locationPermission: String,
    val backgroundRestricted: Boolean,
    val reportingPaused: Boolean,
)

class LocationApi(private val api: ApiClient) {

    suspend fun upload(deviceId: String, points: List<UploadPoint>): UploadResponse = api.post(
        path = "/v1/devices/$deviceId/locations",
        body = UploadRequest(points),
        serializer = UploadRequest.serializer(),
        deserializer = UploadResponse.serializer(),
    )

    /** 上报设备状态（电量、权限、暂停状态等）。 */
    suspend fun reportStatus(deviceId: String, status: DeviceStatusRequest) {
        api.postForUnit(
            path = "/v1/devices/$deviceId/status",
            body = status,
            serializer = DeviceStatusRequest.serializer(),
        )
    }
}
