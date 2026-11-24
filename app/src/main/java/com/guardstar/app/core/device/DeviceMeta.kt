package com.guardstar.app.core.device

import android.os.Build
import com.guardstar.app.BuildConfig

/** 登录与配对时上报的设备信息。 */
object DeviceMeta {
    val model: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    val osVersion: String = "Android ${Build.VERSION.RELEASE}"
    val appVersion: String = BuildConfig.VERSION_NAME
}
