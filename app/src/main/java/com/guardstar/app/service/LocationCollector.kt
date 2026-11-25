package com.guardstar.app.service

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.HandlerThread
import com.guardstar.app.data.location.QueuePoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    private var heartbeatJob: Job? = null

    private val listener: LocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = handle(location)
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) {
            // 服务运行期间用户打开 GPS/网络定位，立即注册该提供者。
            runCatching {
                if (locationManager.isProviderEnabled(provider)) {
                    locationManager.requestLocationUpdates(
                        provider,
                        currentIntervalMs,
                        minDistanceFor(provider),
                        listener,
                        handlerThread.looper,
                    )
                }
            }
        }
        override fun onProviderDisabled(provider: String) = Unit
    }

    fun start(intervalMs: Long = DEFAULT_INTERVAL_MS, minDistanceM: Float = MIN_DISTANCE_M) {
        // 先移除旧注册，保证重复调用 start 不会产生重复回调。
        runCatching { locationManager.removeUpdates(listener) }
        currentIntervalMs = intervalMs
        register(currentIntervalMs, minDistanceM)
        emitLastKnown()
        startHeartbeat()
    }

    fun stop() {
        heartbeatJob?.cancel()
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
                        minDistanceFor(provider, minDistanceM),
                        listener,
                        handlerThread.looper,
                    )
                }
            }
        }
    }

    /**
     * 网络定位不受位移限制：设备静置时也能按采集间隔持续上报，避免开机后长时间没有新位置。
     * GPS 仍按 10 米位移节流，降低户外静置时的功耗。
     */
    private fun minDistanceFor(provider: String, gpsMinDistanceM: Float = MIN_DISTANCE_M): Float =
        if (provider == LocationManager.NETWORK_PROVIDER) 0f else gpsMinDistanceM

    private fun reregister(intervalMs: Long) {
        runCatching { locationManager.removeUpdates(listener) }
        currentIntervalMs = intervalMs
        register(intervalMs, MIN_DISTANCE_M)
    }

    private fun emitLastKnown() {
        val best = sequenceOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { locationManager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (best != null) handle(best, fromLastKnown = true)
    }

    /**
     * 静置时系统可能长期不回调（尤其网络定位），定期主动请求一次当前位置，
     * 保证服务启动后持续有新的上报，而不是只在开机后上报一个点。
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_TICK_MS)
                if (System.currentTimeMillis() - lastEmitAt < currentIntervalMs) continue
                requestFreshLocation()
            }
        }
    }

    private fun requestFreshLocation() {
        val provider = when {
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                LocationManager.NETWORK_PROVIDER
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                LocationManager.GPS_PROVIDER
            else -> return
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                locationManager.getCurrentLocation(provider, null, appContext.mainExecutor) { location ->
                    location?.let { handle(it, fromLastKnown = true) }
                }
            } else {
                @Suppress("DEPRECATION")
                locationManager.requestSingleUpdate(provider, singleListener, handlerThread.looper)
            }
        }
    }

    private val singleListener: LocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = handle(location, fromLastKnown = true)
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    private fun handle(location: Location, fromLastKnown: Boolean = false) {
        // 0. 安全过滤：丢弃模拟定位（守护场景下伪造位置会误导监护人）。
        if (isMock(location)) return

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
        // 提前读取电量，供自适应策略做低电量节流。
        val battery = readBattery()
        if (lat != null && lng != null) {
            val results = FloatArray(1)
            Location.distanceBetween(lat, lng, location.latitude, location.longitude, results)
            val distance = results[0]
            val since = System.currentTimeMillis() - lastEmitAt
            if (since < DEDUPE_WINDOW_MS && distance <= (if (location.hasAccuracy()) location.accuracy else 10f)) {
                return
            }
            updatePolicy(distance, battery)
        } else {
            updatePolicy(Float.MAX_VALUE, battery)
        }

        val recordedAt = Instant.ofEpochMilli(location.time)
            .atOffset(ZoneOffset.UTC)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
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
        // 去重状态在入库成功后再更新：若 onPoint 失败，保留旧状态以便下一个点重试，
        // 避免入库失败 + 误判重复导致连续丢点。
        scope.launch {
            runCatching { onPoint(point) }.onSuccess {
                lastLat = location.latitude
                lastLng = location.longitude
                lastEmitAt = System.currentTimeMillis()
            }
        }
    }

    /**
     * 根据最近一次位移调整采集间隔，仅在变化时重新注册。SOS 模式下不调整。
     * 低电量（<15% 且未充电）时将间隔翻倍（封顶 30 分钟）以延长续航；SOS 紧急模式不受影响。
     */
    private fun updatePolicy(distanceM: Float, battery: Pair<Int, Boolean>) {
        if (sosMode) return
        val base = when {
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
        val lowBattery = battery.first in 0 until LOW_BATTERY_THRESHOLD && !battery.second
        val desired = if (lowBattery) (base * 2).coerceAtMost(LOW_BATTERY_MAX_INTERVAL_MS) else base
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

    @Suppress("DEPRECATION")
    private fun isMock(location: Location): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            location.isMock
        } else {
            location.isFromMockProvider
        }
    }.getOrDefault(false)

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
        const val LOW_BATTERY_THRESHOLD = 15
        const val LOW_BATTERY_MAX_INTERVAL_MS = 30 * 60 * 1000L
        // 心跳检查频率：无新回调时，超过采集间隔就主动请求一次当前位置。
        const val HEARTBEAT_TICK_MS = 60 * 1000L
        // GPS 系统层 minDistance：位移小于此值时不回调；网络定位不受此限制（见 minDistanceFor）。
        const val MIN_DISTANCE_M = 10f
    }
}
