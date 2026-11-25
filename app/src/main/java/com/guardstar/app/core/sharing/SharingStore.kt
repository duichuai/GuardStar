package com.guardstar.app.core.sharing

import android.content.Context

/**
 * 记录用户是否希望持续共享位置。默认开启，用户主动停止后置为关闭。
 * 开机或进入被守护端首页时据此决定是否自动拉起服务，避免停止后被反复重启。
 */
class SharingStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private companion object {
        const val PREFS_NAME = "guardstar_sharing"
        const val KEY_ENABLED = "enabled"
    }
}
