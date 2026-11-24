package com.guardstar.app.core.session

import android.content.Context
import com.guardstar.app.data.auth.LoginResponse
import java.util.UUID

/**
 * 保存登录令牌与设备标识。设备标识在重装前保持不变，用于服务端幂等与设备归属校验。
 */
class SessionStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 首次读取时生成并持久化，保证同一安装内稳定。 */
    val deviceId: String
        get() {
            prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
            val generated = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
            return generated
        }

    fun accessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)

    fun refreshToken(): String? = prefs.getString(KEY_REFRESH_TOKEN, null)

    fun userId(): String? = prefs.getString(KEY_USER_ID, null)

    fun phone(): String? = prefs.getString(KEY_PHONE, null)

    fun isLoggedIn(): Boolean = accessToken() != null && refreshToken() != null

    fun saveLogin(result: LoginResponse, phone: String) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, result.accessToken)
            .putString(KEY_REFRESH_TOKEN, result.refreshToken)
            .putString(KEY_USER_ID, result.userId)
            .putString(KEY_PHONE, phone)
            .apply()
    }

    fun saveTokens(accessToken: String, refreshToken: String) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .apply()
    }

    /** 退出登录只清理账号信息，保留设备标识。 */
    fun clear() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_USER_ID)
            .remove(KEY_PHONE)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "guardstar_session"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_USER_ID = "user_id"
        const val KEY_PHONE = "phone"
    }
}
