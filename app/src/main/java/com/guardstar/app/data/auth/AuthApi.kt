package com.guardstar.app.data.auth

import com.guardstar.app.core.device.DeviceMeta
import com.guardstar.app.core.net.ApiClient
import com.guardstar.app.core.role.UserRole
import com.guardstar.app.core.session.SessionStore
import com.guardstar.app.data.model.RelationSummary
import kotlinx.serialization.Serializable

@Serializable
data class SmsCodeRequest(val phone: String)

@Serializable
data class SmsCodeResponse(
    val expiresIn: Int = 0,
    val devCode: String? = null,
)

@Serializable
data class DeviceInfo(
    val deviceId: String,
    val role: String,
    val model: String,
    val osVersion: String,
    val appVersion: String,
)

@Serializable
data class LoginRequest(
    val phone: String,
    val code: String,
    val device: DeviceInfo,
)

@Serializable
data class LoginResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Int = 0,
    val userId: String,
    val relations: List<RelationSummary> = emptyList(),
)

class AuthApi(
    private val api: ApiClient,
    private val session: SessionStore,
) {

    suspend fun sendCode(phone: String): SmsCodeResponse = api.post(
        path = "/v1/auth/sms-code",
        body = SmsCodeRequest(phone),
        serializer = SmsCodeRequest.serializer(),
        deserializer = SmsCodeResponse.serializer(),
    )

    suspend fun login(phone: String, code: String, role: UserRole): LoginResponse {
        val request = LoginRequest(
            phone = phone,
            code = code,
            device = DeviceInfo(
                deviceId = session.deviceId,
                role = role.apiValue,
                model = DeviceMeta.model,
                osVersion = DeviceMeta.osVersion,
                appVersion = DeviceMeta.appVersion,
            ),
        )
        val result = api.post(
            path = "/v1/auth/login",
            body = request,
            serializer = LoginRequest.serializer(),
            deserializer = LoginResponse.serializer(),
        )
        session.saveLogin(result, phone)
        return result
    }
}
