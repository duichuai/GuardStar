package com.guardstar.app.core.net

import com.guardstar.app.BuildConfig
import com.guardstar.app.core.session.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** 服务端统一错误体映射出的异常。 */
class ApiException(
    val status: Int,
    val code: String,
    message: String,
) : Exception(message)

/**
 * 极简 HTTP 客户端：JSON 编解码 + Bearer 令牌 + 401 自动刷新。
 */
class ApiClient(
    private val session: SessionStore,
    baseUrl: String = BuildConfig.API_BASE_URL,
) {

    private val root = baseUrl.trimEnd('/')

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val plainClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val refreshLock = Any()

    private val client = plainClient.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val token = session.accessToken()
            if (token != null && request.header("Authorization") == null) {
                chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").build())
            } else {
                chain.proceed(request)
            }
        }
        .authenticator { _, response ->
            val isAuthCall = response.request.url.encodedPath.startsWith("/v1/auth/")
            if (response.priorResponse != null || isAuthCall) {
                null
            } else {
                val failedToken = response.request.header("Authorization")?.removePrefix("Bearer ")
                val fresh = refreshAccessToken(failedToken)
                if (fresh == null) {
                    null
                } else {
                    response.request.newBuilder().header("Authorization", "Bearer $fresh").build()
                }
            }
        }
        .build()

    suspend fun <T> get(path: String, deserializer: DeserializationStrategy<T>): T =
        withContext(Dispatchers.IO) {
            json.decodeFromString(deserializer, execute("GET", path, null))
        }

    suspend fun <B, T> post(
        path: String,
        body: B,
        serializer: SerializationStrategy<B>,
        deserializer: DeserializationStrategy<T>,
    ): T = withContext(Dispatchers.IO) {
        val payload = json.encodeToString(serializer, body)
        json.decodeFromString(deserializer, execute("POST", path, payload))
    }

    suspend fun <B> postForUnit(path: String, body: B, serializer: SerializationStrategy<B>) {
        withContext(Dispatchers.IO) {
            execute("POST", path, json.encodeToString(serializer, body))
        }
    }

    suspend fun delete(path: String) {
        withContext(Dispatchers.IO) { execute("DELETE", path, null) }
    }

    private fun execute(method: String, path: String, body: String?): String {
        val builder = Request.Builder().url(root + path)
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post((body ?: "{}").toRequestBody(jsonMedia))
            "PATCH" -> builder.patch((body ?: "{}").toRequestBody(jsonMedia))
            "DELETE" -> builder.delete()
            else -> error("unsupported method $method")
        }
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw toApiException(response.code, text)
            }
            return text
        }
    }

    /** 单飞刷新：并发 401 只轮换一次刷新令牌。 */
    private fun refreshAccessToken(failedToken: String?): String? = synchronized(refreshLock) {
        val current = session.accessToken()
        if (current != null && failedToken != null && current != failedToken) {
            return@synchronized current
        }
        val refreshToken = session.refreshToken() ?: return@synchronized null
        val request = Request.Builder()
            .url("$root/v1/auth/refresh")
            .post(
                json.encodeToString(RefreshRequest.serializer(), RefreshRequest(refreshToken))
                    .toRequestBody(jsonMedia)
            )
            .build()
        try {
            plainClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    session.clear()
                    return@synchronized null
                }
                val result = json.decodeFromString(RefreshResponse.serializer(), text)
                session.saveTokens(result.accessToken, result.refreshToken)
                result.accessToken
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun toApiException(status: Int, text: String): ApiException {
        val parsed = runCatching { json.decodeFromString(ErrorBody.serializer(), text) }.getOrNull()
        val code = parsed?.code?.takeIf { it.isNotBlank() } ?: "HTTP_$status"
        val message = parsed?.message?.takeIf { it.isNotBlank() } ?: "请求失败（$status）"
        return ApiException(status, code, message)
    }

    @Serializable
    private data class ErrorBody(val code: String = "", val message: String = "")

    @Serializable
    private data class RefreshRequest(val refreshToken: String)

    @Serializable
    private data class RefreshResponse(
        val accessToken: String,
        val refreshToken: String,
        val expiresIn: Int = 0,
    )
}
