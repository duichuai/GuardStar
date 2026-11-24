package com.guardstar.app.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 电池优化白名单辅助。被守护端需要后台持续定位，
 * 加入电池优化白名单可显著提升服务存活率（尤其国产 ROM）。
 */
object BatteryOptimize {

    fun isExempt(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** 打开系统「忽略电池优化」设置页，需 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 权限。 */
    fun requestExemptionIntent(context: Context): Intent {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        intent.data = Uri.parse("package:${context.packageName}")
        return intent
    }
}
