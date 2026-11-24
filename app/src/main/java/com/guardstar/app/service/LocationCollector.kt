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
 *
 * 坐标按原始来源上报（GPS 为 WGS-84），不在客户端做坐标转换，
 * 由服务端统一存储 WGS-84，展示侧再转换。
 *
 * 自适应采样策略（设计 3.3）：
 * - 移动中（位移 > 100m）→ 1 分钟
 * - 常规 → 5 分钟
 * - 连续 3 次静止（位移 < 50m）→ 15 分钟
 *
 * 过滤：丢弃过期缓存定位、精度过低的定位，以及短时间内重复的定位。
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

    @Volatile private var currentIntervalMs: Long = DEFAULT_INTERVAL_MS
    @Volatile private var lastLat: Double? = null
    @Volatile private var lastLng: Double? = null
    @Volatile private var lastEmitAt: Long = 0L
    @Volatile private var stationaryCount: Int = 0
    @Volatile private var sosMode: Boolean = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = handle(location)
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    fun start(intervalMs: Long = DEFAULT_INTERVAL_MS, minDistanceM: Float = 0f) {
        currentIntervalMs = intervalMs
        register(currentIntervalMs, minDistanceM)
        emitLastKnown()
    }

    fun stop() {
        runCatching { locationManager.removeUpdates(listener) }
        handlerThread.quitSafely()
        scope.coroutineContext[Job]?.cancel()
    }

    /**
     * SOS 模式：强制 30 秒采集间隔，禁用静止节流，保证紧急时定位密度。
     */
    fun setSosMode(enabled: Boolean) {
        if (sosMode == enabled) return
        sosMode = enabled
        stationaryCount = 0
        reregister(if (enabled) SOS_INTERVAL_MS else DEFAULT_INTERVAL_MS)
    }

    private fun register(intervalMs: Long, minDistanceM: Float) {
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
    }

    private fun reregister(intervalMs: Long) {
        runCatching { locationManager.removeUpdates(listener) }
        currentIntervalMs = intervalMs
        register(intervalMs, 0f)
    }

    private fun emitLastKnown() {
        val best = sequenceOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { locationManager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (best != null) handle(best, fromLastKnown = true)
    }

    private fun handle(location: Location, fromLastKnown: Boolean = false) {
        // 1. 过期缓存定位丢弃（系统可能返回很旧的 last known）
        val ageMs = System.currentTimeMillis() - location.time
        val staleLimit = if (fromLastKnown) LAST_KNOWN_STALE_MS else STALE_MS
        if (ageMs > staleLimit) return

        // 2. 精度过滤：GPS > 200m、网络 > 500m 视为不可用
        val maxAccuracy = when (location.provider) {
            LocationManager.GPS_PROVIDER -> MAX_ACCURACY_GPS
            else -> MAX_ACCURACY_NETWORK
        }
        if (location.hasAccuracy() && location.accuracy > maxAccuracy) return

        // 3. 重复定位去重：10s 内且位移小于精度，视为同一点
        val lat = lastLat
        val lng = lastLng
        if (lat != null && lng != null) {
            val results = FloatArray(1)
            Location.distanceBetween(lat, lng, location.latitude, location.longitude, results)
            val distance = results[0]
            val since = System.currentTimeMillis() - lastEmitAt
            if (since < DEDUPE_WINDOW_MS && distance <= (if (location.hasAccuracy()) location.accuracy else 10f)) {
                return
            }
            updatePolicy(distance)
        } else {
            updatePolicy(Float.MAX_VALUE)
        }

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
            backfill = fromLastKnown && ageMs > STALE_MS,
        )
        lastLat = location.latitude
        lastLng = location.longitude
        lastEmitAt = System.currentTimeMillis()
        scope.launch { runCatching { onPoint(point) } }
    }

    /** 根据最近一次位移调整采集间隔，仅在变化时重新注册。SOS 模式下不调整。 */
    private fun updatePolicy(distanceM: Float) {
        if (sosMode) return
        val desired = when {
            distanceM > MOVING_THRESHOLD_M -> {
                stationaryCount = 0
                MOVING_INTERVAL_MS
            }
            distanceM < STATIONARY_THRESHOLD_M -> {
                stationaryCount += 1
                if (stationaryCount >= STATIONARY_COUNT) STATIONARY_INTERVAL_MS else DEFAULT_INTERVAL_MS
            }
            else -> {
                stationaryCount = 0
                DEFAULT_INTERVAL_MS
            }
        }
        if (desired != currentIntervalMs) reregister(desired)
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

    private companion object {
        const val DEFAULT_INTERVAL_MS = 5 * 60 * 1000L
        const val MOVING_INTERVAL_MS = 60 * 1000L
        const val STATIONARY_INTERVAL_MS = 15 * 60 * 1000L
        const val SOS_INTERVAL_MS = 30 * 1000L
        const val MOVING_THRESHOLD_M = 100f
        const val STATIONARY_THRESHOLD_M = 50f
        const val STATIONARY_COUNT = 3
        const val MAX_ACCURACY_GPS = 200f
        const val MAX_ACCURACY_NETWORK = 500f
        const val STALE_MS = 15_000L
        const val LAST_KNOWN_STALE_MS = 5 * 60 * 1000L
        const val DEDUPE_WINDOW_MS = 10_000L
    }
}
