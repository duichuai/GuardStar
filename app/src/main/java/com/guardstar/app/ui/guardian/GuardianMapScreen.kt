package com.guardstar.app.ui.guardian

import android.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.CircleOptions
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.MarkerOptions
import com.guardstar.app.data.fence.Fence
import com.guardstar.app.data.fence.FenceApi
import com.guardstar.app.data.location.CoordConverter
import com.guardstar.app.data.location.LatestResult
import com.guardstar.app.data.location.LocationApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuardianMapScreen(
    relationId: String,
    wardNickname: String,
    deviceId: String,
    locationApi: LocationApi,
    fenceApi: FenceApi,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var latest by remember { mutableStateOf<LatestResult?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var fences by remember { mutableStateOf<List<Fence>>(emptyList()) }

    // 地图实例与标注，跨重组保留。
    val mapView = remember { MapView(context) }
    var aMap by remember { mutableStateOf<AMap?>(null) }

    // 拉取围栏列表（进入页面时一次）。
    LaunchedEffect(Unit) {
        runCatching { fenceApi.list(relationId) }
            .onSuccess { fences = it }
    }

    // 拉取最新位置；手动刷新与自动轮询都通过 refreshKey 触发。
    LaunchedEffect(refreshKey) {
        loading = latest == null
        error = null
        runCatching { locationApi.latest(relationId, deviceId) }
            .onSuccess { latest = it }
            .onFailure { error = it.message ?: "获取位置失败" }
        loading = false
    }

    // 自动轮询：每 30 秒刷新一次，保证监护人能看到移动。
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            refreshKey++
        }
    }

    // 地图生命周期跟随宿主（onCreate 在 factory 中调用，此处只处理 resume/pause/destroy）。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 获取地图实例（V11 提供同步 map 属性）。
    LaunchedEffect(mapView) {
        runCatching { aMap = mapView.map }
    }

    // 位置或围栏更新时统一重绘地图（clear 后先画围栏，再画位置标记）。
    LaunchedEffect(latest?.location?.lat, latest?.location?.lng, fences) {
        val map = aMap ?: return@LaunchedEffect
        val loc = latest?.location ?: return@LaunchedEffect
        runCatching {
            map.clear()
            // 围栏圆圈：safe=绿色，danger=红色
            for (fence in fences) {
                if (!fence.enabled) continue
                val (fLat, fLng) = CoordConverter.wgs84ToGcj02(fence.lat, fence.lng)
                val fillColor = if (fence.type == "safe")
                    Color.argb(40, 46, 125, 50) else Color.argb(40, 198, 40, 40)
                val strokeColor = if (fence.type == "safe")
                    Color.argb(180, 46, 125, 50) else Color.argb(180, 198, 40, 40)
                map.addCircle(
                    CircleOptions()
                        .center(LatLng(fLat, fLng))
                        .radius(fence.radiusM.toDouble())
                        .strokeWidth(2f)
                        .strokeColor(strokeColor)
                        .fillColor(fillColor)
                )
            }
            val (gcjLat, gcjLng) = CoordConverter.wgs84ToGcj02(loc.lat, loc.lng)
            val point = LatLng(gcjLat, gcjLng)
            map.addCircle(
                CircleOptions()
                    .center(point)
                    .radius(loc.accuracyM)
                    .strokeWidth(0f)
                    .fillColor(Color.argb(40, 66, 133, 244))
            )
            map.addMarker(MarkerOptions().position(point).title("位置"))
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(point, 16f))
        }
    }

    fun requestRefresh() {
        if (refreshing || deviceId.isBlank()) return
        refreshing = true
        error = null
        scope.launch {
            runCatching { locationApi.command(deviceId, "refresh") }
                .onFailure { error = it.message ?: "刷新请求失败" }
            // 等待被守护端上报后再拉取。
            delay(3_000)
            refreshKey++
            refreshing = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(wardNickname.ifBlank { "被守护人" }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AndroidView(
                factory = { mapView.also { it.onCreate(null) } },
                modifier = Modifier.fillMaxSize(),
            )

            // 底部状态卡片。
            latest?.let { result ->
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    result.location?.let { loc ->
                        StatusCard(result = result, recordedAt = loc.recordedAt, accuracyM = loc.accuracyM, source = loc.source)
                    } ?: Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "尚未收到该设备的位置",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { requestRefresh() },
                        enabled = !refreshing && deviceId.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (refreshing) "刷新中…" else "立即刷新位置")
                    }
                }
            }

            if (loading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            error?.let { msg ->
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(16.dp)
                        .background(
                            MaterialTheme.colorScheme.errorContainer,
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text(msg, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    result: LatestResult,
    recordedAt: String,
    accuracyM: Double,
    source: String,
) {
    val device = result.device
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("当前位置", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                val (stateText, stateColor) = onlineStateStyle(device.onlineState)
                Text(stateText, color = stateColor, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            val timeText = formatRecordedAt(recordedAt)
            Text(
                text = "最后上报：$timeText" + if (result.stale) "（可能已过期）" else "",
                style = MaterialTheme.typography.bodySmall,
                color = if (result.stale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("精度 ${"%.0f".format(accuracyM)}m", style = MaterialTheme.typography.bodySmall)
                }
                device.battery?.let { battery ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.BatteryFull, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("$battery%${if (device.charging) " ⚡" else ""}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (device.locationPermission == "denied") {
                Spacer(Modifier.height(4.dp))
                Text(
                    "被守护端定位权限已关闭",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (device.reportingPaused) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "被守护端已暂停上报",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (result.activeFences.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                val inside = result.activeFences.filter { it.inside }
                val outside = result.activeFences.filter { !it.inside }
                if (inside.isNotEmpty()) {
                    Text(
                        "在围栏内：${inside.joinToString("、") { it.name }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = androidx.compose.ui.graphics.Color(0xFF2E7D32),
                    )
                }
                outside.forEach { f ->
                    Text(
                        "已离开围栏：${f.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

private fun onlineStateStyle(state: String): Pair<String, androidx.compose.ui.graphics.Color> = when (state) {
    "online" -> "在线" to androidx.compose.ui.graphics.Color(0xFF2E7D32)
    "delayed" -> "弱网/延迟" to androidx.compose.ui.graphics.Color(0xFFEF6C00)
    else -> "离线" to androidx.compose.ui.graphics.Color(0xFF616161)
}

private fun formatRecordedAt(recordedAt: String): String = runCatching {
    val instant = Instant.parse(recordedAt)
    val now = System.currentTimeMillis()
    val agoSec = (now - instant.toEpochMilli()) / 1000
    val rel = when {
        agoSec < 60 -> "${agoSec}秒前"
        agoSec < 3600 -> "${agoSec / 60}分钟前"
        agoSec < 86400 -> "${agoSec / 3600}小时前"
        else -> "${agoSec / 86400}天前"
    }
    val abs = instant.atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    "$rel（$abs）"
}.getOrDefault(recordedAt)
