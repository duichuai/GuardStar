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
    val sosMode: Boolean,
)

/** 监护人查询的最新位置（服务端存储 WGS-84，展示前需转 GCJ-02）。 */
@Serializable
data class LatestLocation(
    val lat: Double,
    val lng: Double,
    val accuracyM: Double,
    val source: String = "",
    val recordedAt: String = "",
)

@Serializable
data class DeviceStatusView(
    val battery: Int? = null,
    val charging: Boolean = false,
    val onlineState: String = "offline",
    val lastSeenAt: String? = null,
    val locationPermission: String = "",
    val reportingPaused: Boolean = false,
)

@Serializable
data class FenceState(
    val fenceId: String,
    val name: String = "",
    val inside: Boolean = false,
)

@Serializable
data class LatestResult(
    val location: LatestLocation? = null,
    val device: DeviceStatusView = DeviceStatusView(),
    val stale: Boolean = false,
    val activeFences: List<FenceState> = emptyList(),
)

@Serializable
data class TrackPoint(
    val lat: Double,
    val lng: Double,
    val accuracyM: Double = 0.0,
    val recordedAt: String = "",
    val source: String = "",
)

@Serializable
data class TrackResult(
    val points: List<TrackPoint> = emptyList(),
    val simplified: Boolean = false,
    val rawCount: Int = 0,
)

@Serializable
data class CommandRequest(
    val type: String,
    val durationSec: Int? = null,
)

@Serializable
data class CommandResult(
    val commandId: String,
    val expiresAt: String = "",
)

/** 被守护端拉取到的待执行命令。 */
@Serializable
data class PendingCommand(
    val id: String,
    val type: String,
    val payload: String = "",
    val expiresAt: String = "",
)

@Serializable
data class CommandListResponse(val items: List<PendingCommand> = emptyList())

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

    /** 查询被守护人的最新位置与设备状态。deviceId 可选，留空由服务端按关系解析。 */
    suspend fun latest(relationId: String, deviceId: String = ""): LatestResult {
        val path = buildString {
            append("/v1/relations/$relationId/location/latest")
            if (deviceId.isNotBlank()) append("?deviceId=").append(deviceId)
        }
        return api.get(path, LatestResult.serializer())
    }

    /** 查询历史轨迹，from/to 为 RFC3339 时间字符串。 */
    suspend fun track(
        relationId: String,
        from: String,
        to: String,
        deviceId: String = "",
        simplify: Boolean = true,
    ): TrackResult {
        val path = buildString {
            append("/v1/relations/$relationId/locations?from=").append(from)
            append("&to=").append(to)
            if (!simplify) append("&simplify=false")
            if (deviceId.isNotBlank()) append("&deviceId=").append(deviceId)
        }
        return api.get(path, TrackResult.serializer())
    }

    /** 向被守护端下发命令（refresh / finding_mode / pause_reporting / resume_reporting）。 */
    suspend fun command(deviceId: String, type: String, durationSec: Int? = null): CommandResult =
        api.post(
            path = "/v1/devices/$deviceId/commands",
            body = CommandRequest(type, durationSec),
            serializer = CommandRequest.serializer(),
            deserializer = CommandResult.serializer(),
        )

    /** 被守护端拉取待执行命令（已送达的命令会被服务端标记）。 */
    suspend fun fetchCommands(deviceId: String): List<PendingCommand> = api.get(
        path = "/v1/devices/$deviceId/commands",
        deserializer = CommandListResponse.serializer(),
    ).items
}
