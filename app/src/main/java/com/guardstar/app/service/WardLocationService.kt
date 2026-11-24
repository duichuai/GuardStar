package com.guardstar.app.service

import android.Manifest
import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.guardstar.app.GuardStarApp
import com.guardstar.app.core.session.SessionStore
import com.guardstar.app.data.location.DeviceStatusRequest
import com.guardstar.app.data.location.LocationApi
import com.guardstar.app.data.location.LocationFlushWorker
import com.guardstar.app.data.location.LocationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 被守护端位置共享前台服务。
 * - 启动 LocationCollector 按 5 分钟间隔采集并写入本地队列
 * - 每 60 秒触发一次批量上报（队列达到 20 条或时间到）
 * - 周期性上报设备状态（电量/权限）
 */
class WardLocationService : Service() {

    private lateinit var app: GuardStarApp
    private lateinit var repo: LocationRepository
    private lateinit var locationApi: LocationApi
    private lateinit var session: SessionStore
    private lateinit var collector: LocationCollector

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var flushJob: Job? = null
    private var statusJob: Job? = null

    @Volatile private var sosMode: Boolean = false
    @Volatile private var started: Boolean = false

    override fun onCreate() {
        super.onCreate()
        app = application as GuardStarApp
        repo = app.locationRepository
        locationApi = app.locationApi
        session = app.session
        collector = LocationCollector(this) { point -> repo.enqueue(point) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifications.ensureChannels(this)
        val notification = Notifications.locationNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                Notifications.ID_WARD_LOCATION,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(Notifications.ID_WARD_LOCATION, notification)
        }

        if (!started) {
            started = true
            repo.setRunning(true)
            collector.start(COLLECT_INTERVAL_MS)
            startFlushLoop()
            startStatusLoop()
        }

        // 支持通过 Intent 切换 SOS 采集模式（30 秒间隔）。
        if (intent?.hasExtra(EXTRA_SOS) == true) {
            sosMode = intent.getBooleanExtra(EXTRA_SOS, false)
            collector.setSosMode(sosMode)
            // SOS 模式切换立即上报状态，避免监护人等待下一次 15 分钟周期上报。
            scope.launch { reportStatus() }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户从最近任务划掉：保持 START_STICKY 重启语义，不主动停止定位。
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        collector.stop()
        flushJob?.cancel()
        statusJob?.cancel()
        scope.coroutineContext[Job]?.cancel()
        repo.setRunning(false)
        super.onDestroy()
    }

    private fun startFlushLoop() {
        flushJob = scope.launch {
            var lastFlush = 0L
            while (isActive) {
                // 登出或凭证失效（token 刷新失败已清 session）时停止服务，避免无效重传。
                if (!session.isLoggedIn()) {
                    stopSelf()
                    return@launch
                }
                val pending = repo.status.value.pendingCount
                val now = System.currentTimeMillis()
                val locationOk = isLocationAvailable()
                repo.setLocationAvailable(locationOk)
                repo.setNotificationsEnabled(areNotificationsEnabled())
                val interval = if (sosMode) SOS_FLUSH_INTERVAL_MS else FLUSH_INTERVAL_MS
                val threshold = if (sosMode) SOS_FLUSH_THRESHOLD else FLUSH_THRESHOLD
                val due = pending >= threshold || now - lastFlush >= interval
                var flushed = false
                if (due && isNetworkAvailable()) {
                    repo.flush()
                    lastFlush = now
                    flushed = true
                }
                // 每轮更新通知文案，展示待上报数。
                updateNotificationText(pending, locationOk)
                // flush 后仍有待上报点（积压未排空），缩短下一轮延迟以快速排空；否则按正常轮询间隔。
                val remaining = repo.status.value.pendingCount
                val tick = if (sosMode) SOS_TICK_MS else TICK_MS
                delay(if (flushed && remaining > 0) BACKLOG_DRAIN_TICK_MS else tick)
            }
        }
    }

    private fun updateNotificationText(pending: Int, locationOk: Boolean) {
        val prefix = when {
            !locationOk -> "定位不可用，请开启系统定位"
            sosMode -> "SOS 紧急定位中"
            else -> "正在共享位置"
        }
        val text = if (pending > 0) "$prefix · 待上报 $pending 条" else prefix
        Notifications.updateLocationNotification(this, text)
    }

    /** 定位可用 = 已授予精确定位权限且至少一个定位提供者开启。 */
    private fun isLocationAvailable(): Boolean {
        val permOk = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!permOk) return false
        val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
        return runCatching {
            lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(false)
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        return runCatching {
            val network = cm.activeNetwork ?: return@runCatching false
            val caps = cm.getNetworkCapabilities(network) ?: return@runCatching false
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }.getOrDefault(false)
    }

    /** Android 13+ 通知权限；低版本默认开启。 */
    private fun areNotificationsEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun startStatusLoop() {
        statusJob = scope.launch {
            reportStatus()
            while (isActive) {
                delay(STATUS_INTERVAL_MS)
                reportStatus()
            }
        }
    }

    private suspend fun reportStatus() {
        runCatching {
            locationApi.reportStatus(
                session.deviceId,
                DeviceStatusRequest(
                    battery = readBatteryPct(),
                    charging = isCharging(),
                    locationPermission = locationPermissionState(),
                    backgroundRestricted = isBackgroundRestricted(),
                    reportingPaused = false,
                    sosMode = sosMode,
                ),
            )
        }
    }

    private fun locationPermissionState(): String = when {
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED -> "denied"
        checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED -> "granted_background"
        else -> "granted_foreground"
    }

    private fun isBackgroundRestricted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return runCatching {
            (getSystemService(ACTIVITY_SERVICE) as ActivityManager).isBackgroundRestricted
        }.getOrDefault(false)
    }

    private fun readBatteryPct(): Int {
        val filter = android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val status = registerReceiver(null, filter) ?: return 50
        val level = status.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) level * 100 / scale else 50
    }

    private fun isCharging(): Boolean {
        val filter = android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val status = registerReceiver(null, filter) ?: return false
        return status.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    companion object {
        private const val COLLECT_INTERVAL_MS = 5 * 60 * 1000L
        private const val FLUSH_INTERVAL_MS = 60 * 1000L
        private const val FLUSH_THRESHOLD = 20
        private const val SOS_FLUSH_INTERVAL_MS = 15 * 1000L
        private const val SOS_FLUSH_THRESHOLD = 5
        private const val TICK_MS = 10_000L
        // flush 后仍有积压待上报点时的下一轮延迟，快速排空积压。
        private const val BACKLOG_DRAIN_TICK_MS = 1_000L
        private const val SOS_TICK_MS = 5_000L
        private const val STATUS_INTERVAL_MS = 15 * 60 * 1000L

        fun start(context: Context) {
            val intent = Intent(context, WardLocationService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WardLocationService::class.java))
            // 停止后立即触发一次补传，避免残余队列丢失。
            LocationFlushWorker.flushOnce(context)
        }

        /** 切换 SOS 采集模式；若服务未运行则先启动。 */
        fun setSosMode(context: Context, enabled: Boolean) {
            val intent = Intent(context, WardLocationService::class.java)
                .putExtra(EXTRA_SOS, enabled)
            ContextCompat.startForegroundService(context, intent)
        }

        private const val EXTRA_SOS = "sos_mode"
    }
}
