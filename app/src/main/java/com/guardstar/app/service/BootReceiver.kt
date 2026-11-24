package com.guardstar.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.guardstar.app.GuardStarApp
import com.guardstar.app.core.role.UserRole

/**
 * 开机自启：若当前角色为被守护端，则恢复位置共享前台服务。
 * 由服务端根据守护关系决定是否真正消费定位，无关系时上报会被忽略。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as GuardStarApp
        if (app.roleStore.loadRole() == UserRole.WARD) {
            WardLocationService.start(context)
        }
    }
}
