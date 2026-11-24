package com.guardstar.app.core.role

/**
 * 同一台设备上用户的角色。角色只影响本机界面与后台任务，不改变服务端已有关系。
 */
enum class UserRole {
    /** 被守护端：采集并上报位置。 */
    WARD,

    /** 守护端：查看位置、配置围栏、处理求助。 */
    GUARDIAN;

    /** 服务端约定的角色取值。 */
    val apiValue: String
        get() = when (this) {
            WARD -> "ward"
            GUARDIAN -> "guardian"
        }

    companion object {
        fun fromName(value: String?): UserRole? =
            entries.firstOrNull { it.name == value }
    }
}
