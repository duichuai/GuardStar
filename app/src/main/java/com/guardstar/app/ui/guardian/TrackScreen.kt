package com.guardstar.app.ui.guardian

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.amap.api.maps.AMap
import com.amap.api.maps.MapView
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.PolylineOptions
import com.guardstar.app.data.location.CoordConverter
import com.guardstar.app.data.location.LocationApi
import com.guardstar.app.data.location.Stay
import com.guardstar.app.data.location.TrackPoint
import com.guardstar.app.data.location.TrackResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import android.graphics.Color

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackScreen(
    relationId: String,
    wardNickname: String,
    locationApi: LocationApi,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val mapView = remember { MapView(context) }
    var aMap by remember { mutableStateOf<AMap?>(null) }

    var preset by remember { mutableStateOf("today") }
    var result by remember { mutableStateOf<TrackResult?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    var playIndex by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }

    fun loadTrack() {
        scope.launch {
            loading = true
            error = null
            val (from, to) = computeRange(preset)
            runCatching { locationApi.track(relationId, from, to) }
                .onSuccess {
                    result = it
                    playIndex = if (it.points.isNotEmpty()) it.points.lastIndex else 0
                }
                .onFailure { error = it.message ?: "查询轨迹失败" }
            loading = false
        }
    }

    // 切换时间范围或手动刷新时加载
    LaunchedEffect(preset, refreshKey) { loadTrack() }

    // 播放：每 150ms 前进一个点
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        val pts = result?.points ?: return@LaunchedEffect
        while (playing && playIndex < pts.lastIndex) {
            delay(150)
            playIndex++
        }
        playing = false
    }

    // 地图生命周期
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

    LaunchedEffect(mapView) { runCatching { aMap = mapView.map } }

    // 轨迹或回放位置变化时重绘
    LaunchedEffect(result, playIndex) {
        val map = aMap ?: return@LaunchedEffect
        val r = result ?: return@LaunchedEffect
        runCatching {
            map.clear()
            // 1. 轨迹折线
            if (r.points.size >= 2) {
                val pts = r.points.map { p ->
                    val (lat, lng) = CoordConverter.wgs84ToGcj02(p.lat, p.lng)
                    LatLng(lat, lng)
                }
                map.addPolyline(
                    PolylineOptions()
                        .addAll(pts)
                        .color(Color.argb(220, 33, 150, 243))
                        .width(10f)
                )
                // 起点（绿）和终点（红）
                map.addMarker(MarkerOptions().position(pts.first())
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                    .title("起点"))
                map.addMarker(MarkerOptions().position(pts.last())
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                    .title("终点"))
            }
            // 2. 停留点
            r.stays.forEach { s ->
                val (lat, lng) = CoordConverter.wgs84ToGcj02(s.lat, s.lng)
                map.addMarker(
                    MarkerOptions()
                        .position(LatLng(lat, lng))
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_YELLOW))
                        .title("停留 ${formatStay(s)}")
                )
            }
            // 3. 回放当前位置
            if (r.points.isNotEmpty() && playIndex in r.points.indices) {
                val p = r.points[playIndex]
                val (lat, lng) = CoordConverter.wgs84ToGcj02(p.lat, p.lng)
                map.addMarker(
                    MarkerOptions()
                        .position(LatLng(lat, lng))
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE))
                        .title(formatTime(p.recordedAt))
                )
            }
        }
    }

    // 首次加载完成后移动镜头到轨迹中心
    LaunchedEffect(result) {
        val map = aMap ?: return@LaunchedEffect
        val pts = result?.points ?: return@LaunchedEffect
        if (pts.isEmpty()) return@LaunchedEffect
        runCatching {
            val first = pts.first()
            val (lat, lng) = CoordConverter.wgs84ToGcj02(first.lat, first.lng)
            map.moveCamera(com.amap.api.maps.CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), 15f))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("$wardNickname 的轨迹") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                factory = { mapView.also { it.onCreate(null) } },
                modifier = Modifier.fillMaxSize(),
            )

            // 顶部时间范围选择
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(12.dp)
            ) {
                Row(
                    Modifier
                        .background(
                            MaterialTheme.colorScheme.surface,
                            RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    RangeChip("今天", preset == "today") { preset = "today" }
                    RangeChip("昨天", preset == "yesterday") { preset = "yesterday" }
                    RangeChip("近3天", preset == "3d") { preset = "3d" }
                    RangeChip("近7天", preset == "7d") { preset = "7d" }
                }
                if (error != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        error ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.surface,
                                RoundedCornerShape(8.dp)
                            )
                            .padding(8.dp),
                    )
                }
            }

            // 加载中
            if (loading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }

            // 底部回放控制
            val pts = result?.points ?: emptyList()
            if (pts.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(12.dp),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        // 信息行
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                "共 ${pts.size} 点" +
                                    (if (result?.simplified == true) "（原始 ${result?.rawCount}）" else ""),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            val stays = result?.stays?.size ?: 0
                            if (stays > 0) {
                                Text("停留 $stays 处", style = MaterialTheme.typography.bodySmall)
                            }
                            Text(
                                if (playIndex in pts.indices) formatTime(pts[playIndex].recordedAt) else "",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        // 进度条
                        Slider(
                            value = playIndex.toFloat(),
                            onValueChange = { playIndex = it.toInt() },
                            valueRange = 0f..pts.lastIndex.toFloat(),
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            AssistChip(
                                onClick = {
                                    if (playIndex >= pts.lastIndex) playIndex = 0
                                    playing = !playing
                                },
                                label = { Text(if (playing) "暂停" else "播放") },
                            )
                        }
                    }
                }
            }

            // 无数据提示
            if (!loading && pts.isEmpty()) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .background(
                            MaterialTheme.colorScheme.surface,
                            RoundedCornerShape(12.dp)
                        )
                        .padding(16.dp),
                ) {
                    Text("该时间段暂无轨迹数据", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun RangeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
    )
}

/** 计算时间范围，返回 (from RFC3339, to RFC3339)。 */
private fun computeRange(preset: String): Pair<String, String> {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val fmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    return when (preset) {
        "yesterday" -> {
            val y = today.minusDays(1)
            y.atStartOfDay(zone).format(fmt) to
                y.atTime(LocalTime.MAX).atZone(zone).format(fmt)
        }
        "3d" -> today.minusDays(2).atStartOfDay(zone).format(fmt) to
            java.time.ZonedDateTime.now(zone).format(fmt)
        "7d" -> today.minusDays(6).atStartOfDay(zone).format(fmt) to
            java.time.ZonedDateTime.now(zone).format(fmt)
        else -> today.atStartOfDay(zone).format(fmt) to
            java.time.ZonedDateTime.now(zone).format(fmt)
    }
}

private fun formatTime(iso: String): String {
    return runCatching {
        java.time.ZonedDateTime.parse(iso)
            .withZoneSameInstant(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    }.getOrDefault(iso)
}

private fun formatStay(s: Stay): String {
    val start = formatTime(s.startAt)
    val end = formatTime(s.endAt)
    return "$start ~ $end"
}
