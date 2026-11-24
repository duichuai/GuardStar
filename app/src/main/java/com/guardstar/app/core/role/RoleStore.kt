package com.guardstar.app.core.role

import android.content.Context

/**
 * 角色选择是本地状态，使用 SharedPreferences 持久化；不涉及敏感信息。
 */
class RoleStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadRole(): UserRole? = UserRole.fromName(prefs.getString(KEY_ROLE, null))

    fun saveRole(role: UserRole) {
        prefs.edit().putString(KEY_ROLE, role.name).apply()
    }

    fun clearRole() {
        prefs.edit().remove(KEY_ROLE).apply()
    }

    private companion object {
        const val PREFS_NAME = "guardstar_role"
        const val KEY_ROLE = "role"
    }
}
