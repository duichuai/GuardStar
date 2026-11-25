package com.guardstar.app.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.guardstar.app.GuardStarApp
import com.guardstar.app.core.role.UserRole

/**
 * 开机自启：被守护端已登录、未主动停止共享且权限齐全时，恢复位置共享前台服务。
 * 由服务端根据守护关系决定是否真正消费定位，无关系时上报会被忽略。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as GuardStarApp
        val role = app.roleStore.loadRole()
        val loggedIn = app.session.isLoggedIn()
        val sharingEnabled = app.sharingStore.isEnabled()
        val fineLocation = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val backgroundLocation = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        Log.i(
            TAG,
            "开机自启检查: role=$role loggedIn=$loggedIn sharingEnabled=$sharingEnabled " +
                "fineLocation=$fineLocation backgroundLocation=$backgroundLocation",
        )
        if (role != UserRole.WARD || !loggedIn || !sharingEnabled || !fineLocation || !backgroundLocation) {
            return
        }
        runCatching { WardLocationService.start(context) }
            .onFailure { Log.w(TAG, "开机恢复位置共享失败", it) }
    }

    private companion object {
        const val TAG = "GuardStarBoot"
    }
}
