package com.guardstar.app.service

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.HandlerThread
import com.guardstar.app.data.location.QueuePoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 基于系统 LocationManager 的采集器。
 * 坐标按原始来源上报（GPS 为 WGS-84），不在客户端做坐标转换，
 * 由服务端统一存储 WGS-84，展示侧再转换。
 */
class LocationCollector(
    context: Context,
    private val onPoint: suspend (QueuePoint) -> Unit,
) {
    private val appContext = context.applicationContext
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val handlerThread = HandlerThread("guardstar-loc").apply { start() }

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = handle(location)
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    /**
     * @param intervalMs 最小采集间隔，常规 5 分钟。
     * @param minDistanceM 最小位移阈值，0 表示按时间。
     */
    fun start(intervalMs: Long, minDistanceM: Float = 0f) {
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        for (provider in providers) {
            runCatching {
                if (locationManager.isProviderEnabled(provider)) {
                    locationManager.requestLocationUpdates(
                        provider,
                        intervalMs,
                        minDistanceM,
                        listener,
                        handlerThread.looper,
                    )
                }
            }
        }
        // 立即上报一次最近已知位置，避免首次等待。
        emitLastKnown()
    }

    fun stop() {
        runCatching { locationManager.removeUpdates(listener) }
        handlerThread.quitSafely()
        scope.coroutineContext[Job]?.cancel()
    }

    private fun emitLastKnown() {
        val best = sequenceOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { locationManager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (best != null) handle(best)
    }

    private fun handle(location: Location) {
        val recordedAt = Instant.ofEpochMilli(location.time)
            .atOffset(ZoneOffset.UTC)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val battery = readBattery()
        val point = QueuePoint(
            recordedAt = recordedAt,
            lat = location.latitude,
            lng = location.longitude,
            accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else 0.0,
            source = location.provider ?: "unknown",
            battery = battery.first,
            charging = battery.second,
            backfill = false,
        )
        scope.launch { onPoint(point) }
    }

    private fun readBattery(): Pair<Int, Boolean> {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val status = appContext.registerReceiver(null, filter) ?: return 50 to false
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val plugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else 50
        return pct to (plugged != 0)
    }
}
