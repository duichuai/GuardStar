package com.guardstar.app.ui.guardian

import android.graphics.Color as AndroidColor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.Circle
import com.amap.api.maps.model.CircleOptions
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.guardstar.app.data.fence.Fence
import com.guardstar.app.data.fence.FenceApi
import com.guardstar.app.data.fence.FenceCenter
import com.guardstar.app.data.fence.FenceCreateRequest
import com.guardstar.app.data.fence.FenceUpdateRequest
import com.guardstar.app.data.location.CoordConverter
import com.guardstar.app.data.location.LocationApi
import kotlinx.coroutines.launch
import java.util.Locale

private const val MIN_RADIUS = 100
private const val MAX_RADIUS = 5000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FenceScreen(
    relationId: String,
    wardNickname: String,
    wardDeviceId: String,
    fenceApi: FenceApi,
    locationApi: LocationApi,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val fences = remember { mutableStateListOf<Fence>() }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var editorOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Fence?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    fun reload() {
        loading = true
        error = null
        scope.launch {
            runCatching { fenceApi.list(relationId) }
                .onSuccess {
                    fences.clear()
                    fences.addAll(it)
                }
                .onFailure { error = it.message ?: "加载失败" }
            loading = false
        }
    }

    LaunchedEffect(refreshKey) { reload() }

    fun saveFence(name: String, type: String, radius: Int, lat: Double, lng: Double, enabled: Boolean) {
        if (saving) return
        saving = true
        saveError = null
        val target = editing
        scope.launch {
            runCatching {
                val center = FenceCenter(lat, lng)
                if (target == null) {
                    fenceApi.create(
                        relationId, FenceCreateRequest(
                            name = name,
                            center = center,
                            radiusM = radius,
                            type = type,
                            enabled = enabled,
                        )
                    )
                } else {
                    fenceApi.update(
                        target.fenceId, FenceUpdateRequest(
                            name = name,
                            center = center,
                            radiusM = radius,
                            type = type,
                            enabled = enabled,
                        )
                    )
                }
            }.onSuccess {
                editorOpen = false
                refreshKey++
            }.onFailure {
                saveError = it.message ?: "保存失败"
            }
            saving = false
        }
    }

    fun deleteFence(fence: Fence) {
        scope.launch {
            runCatching { fenceApi.delete(fence.fenceId) }
                .onSuccess { refreshKey++ }
                .onFailure { error = it.message ?: "删除失败" }
        }
    }

    fun toggleEnabled(fence: Fence) {
        scope.launch {
            runCatching {
                fenceApi.update(fence.fenceId, FenceUpdateRequest(enabled = !fence.enabled))
            }.onSuccess { refreshKey++ }
                .onFailure { error = it.message ?: "操作失败" }
        }
    }

    if (editorOpen) {
        BackHandler { if (!saving) editorOpen = false }
        FenceEditorScreen(
            editing = editing,
            relationId = relationId,
            deviceId = wardDeviceId,
            locationApi = locationApi,
            saving = saving,
            saveError = saveError,
            onDismiss = { if (!saving) editorOpen = false },
            onSave = { name, type, radius, lat, lng, enabled ->
                saveFence(name, type, radius, lat, lng, enabled)
            },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${wardNickname.ifBlank { "被守护人" }} 的围栏") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        editing = null
                        saveError = null
                        editorOpen = true
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = "新建围栏")
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
            if (loading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else if (fences.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("尚未设置围栏", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                    Text("点击右上角 + 在地图上圈选安全区/危险区", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(fences, key = { it.fenceId }) { fence ->
                        FenceItem(
                            fence = fence,
                            onClick = {
                                editing = fence
                                saveError = null
                                editorOpen = true
                            },
                            onToggle = { toggleEnabled(fence) },
                            onDelete = { deleteFence(fence) },
                        )
                    }
                }
            }

            error?.let { msg ->
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(16.dp)
                        .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text(msg, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
    }
}

@Composable
private fun FenceItem(
    fence: Fence,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val (typeLabel, typeColor) = if (fence.type == "safe")
        "安全区" to Color(0xFF2E7D32) else "危险区" to Color(0xFFC62828)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(typeColor.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.LocationOn,
                        contentDescription = null,
                        tint = typeColor,
                    )
                }
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(fence.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        "半径 ${fence.radiusM}m · $typeLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = fence.enabled, onCheckedChange = { onToggle() })
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FenceEditorScreen(
    editing: Fence?,
    relationId: String,
    deviceId: String,
    locationApi: LocationApi,
    saving: Boolean,
    saveError: String?,
    onDismiss: () -> Unit,
    onSave: (name: String, type: String, radius: Int, lat: Double, lng: Double, enabled: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(editing?.name.orEmpty()) }
    var type by remember { mutableStateOf(editing?.type ?: "safe") }
    var radius by remember { mutableStateOf((editing?.radiusM ?: 200).toFloat()) }
    var enabled by remember { mutableStateOf(editing?.enabled ?: true) }
    var center by remember {
        mutableStateOf(
            editing?.let { fence ->
                if (fence.lat == 0.0 && fence.lng == 0.0) null else fence.lat to fence.lng
            }
        )
    }
    var locating by remember { mutableStateOf(false) }
    var mapError by remember { mutableStateOf<String?>(null) }

    // 地图实例与圆圈/标注，跨重组保留。
    val mapView = remember { MapView(context) }
    var aMap by remember { mutableStateOf<AMap?>(null) }
    var circle by remember { mutableStateOf<Circle?>(null) }
    var marker by remember { mutableStateOf<Marker?>(null) }

    // 地图生命周期跟随宿主；离开编辑器时主动销毁，避免重复进入泄漏。
    DisposableEffect(lifecycleOwner, mapView) {
        var destroyed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_DESTROY -> {
                    mapView.onDestroy()
                    destroyed = true
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (!destroyed) {
                mapView.onPause()
                mapView.onDestroy()
            }
        }
    }

    // 初始化地图并监听点击选点。
    LaunchedEffect(mapView) {
        runCatching { mapView.map }.onSuccess { map ->
            aMap = map
            map.setOnMapClickListener { latLng ->
                val (wgsLat, wgsLng) = CoordConverter.gcj02ToWgs84(latLng.latitude, latLng.longitude)
                center = wgsLat to wgsLng
                mapError = null
            }
        }.onFailure {
            mapError = "地图初始化失败，请返回重试"
        }
    }

    // 首次进入：编辑时定位到已有围栏；新建时优先定位到被守护人最新位置。
    LaunchedEffect(aMap) {
        val map = aMap ?: return@LaunchedEffect
        val existing = center
        if (existing != null) {
            val (gcjLat, gcjLng) = CoordConverter.wgs84ToGcj02(existing.first, existing.second)
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(gcjLat, gcjLng), 16f))
            return@LaunchedEffect
        }
        if (deviceId.isBlank()) {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(35.0, 105.0), 4f))
            return@LaunchedEffect
        }
        locating = true
        runCatching { locationApi.latest(relationId, deviceId) }
            .onSuccess { result ->
                val loc = result.location
                if (loc == null) {
                    if (center == null) {
                        mapError = "尚未收到被守护人位置，请点击地图选择中心"
                        map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(35.0, 105.0), 4f))
                    }
                } else {
                    // 用户可能已在等待期间手动选点，避免覆盖。
                    if (center == null) {
                        center = loc.lat to loc.lng
                        val (gcjLat, gcjLng) = CoordConverter.wgs84ToGcj02(loc.lat, loc.lng)
                        map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(gcjLat, gcjLng), 16f))
                    }
                }
            }
            .onFailure {
                if (center == null) {
                    mapError = "获取被守护人位置失败，请点击地图选择中心"
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(35.0, 105.0), 4f))
                }
            }
        locating = false
    }

    // 中心、半径或类型变化时重绘围栏预览。
    LaunchedEffect(aMap, center, radius, type) {
        val map = aMap ?: return@LaunchedEffect
        circle?.remove()
        marker?.remove()
        circle = null
        marker = null
        val current = center ?: return@LaunchedEffect
        val (gcjLat, gcjLng) = CoordConverter.wgs84ToGcj02(current.first, current.second)
        val point = LatLng(gcjLat, gcjLng)
        val fillColor = if (type == "safe")
            AndroidColor.argb(48, 46, 125, 50) else AndroidColor.argb(48, 198, 40, 40)
        val strokeColor = if (type == "safe")
            AndroidColor.argb(180, 46, 125, 50) else AndroidColor.argb(180, 198, 40, 40)
        circle = map.addCircle(
            CircleOptions()
                .center(point)
                .radius(radius.toDouble())
                .strokeWidth(2f)
                .strokeColor(strokeColor)
                .fillColor(fillColor)
        )
        marker = map.addMarker(MarkerOptions().position(point).title("围栏中心"))
    }

    fun useWardLocation() {
        if (locating || deviceId.isBlank()) return
        locating = true
        mapError = null
        scope.launch {
            runCatching { locationApi.latest(relationId, deviceId) }
                .onSuccess { result ->
                    val loc = result.location
                    if (loc == null) {
                        mapError = "尚未收到被守护人位置"
                    } else {
                        center = loc.lat to loc.lng
                        val (gcjLat, gcjLng) = CoordConverter.wgs84ToGcj02(loc.lat, loc.lng)
                        aMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(gcjLat, gcjLng), 17f))
                    }
                }
                .onFailure { mapError = it.message ?: "获取被守护人位置失败" }
            locating = false
        }
    }

    val isEdit = editing != null
    val valid = name.trim().length in 1..20 && center != null
    val centerText = center?.let {
        "中心：${String.format(Locale.US, "%.6f", it.first)}, ${String.format(Locale.US, "%.6f", it.second)}"
    } ?: "中心：未选择，请点击地图"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEdit) "编辑围栏" else "新建围栏") },
                navigationIcon = {
                    IconButton(onClick = onDismiss, enabled = !saving) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                AndroidView(
                    factory = { mapView.also { it.onCreate(null) } },
                    modifier = Modifier.fillMaxSize(),
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(12.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (center == null) "点击地图选择围栏中心"
                        else "点击地图调整中心，圆圈为围栏范围",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                mapError?.let { msg ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 56.dp, start = 12.dp, end = 12.dp)
                            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            msg,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                Button(
                    onClick = { useWardLocation() },
                    enabled = !locating && deviceId.isNotBlank(),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp),
                ) {
                    if (locating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(
                            Icons.Outlined.MyLocation,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text("被守护人位置")
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 3.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(20) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("名称（1-20字）") },
                        singleLine = true,
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = type == "safe",
                            onClick = { type = "safe" },
                            label = { Text("安全区（离开告警）") },
                        )
                        FilterChip(
                            selected = type == "danger",
                            onClick = { type = "danger" },
                            label = { Text("危险区（进入告警）") },
                        )
                    }

                    Column {
                        Text("半径：${radius.toInt()} 米", style = MaterialTheme.typography.bodyMedium)
                        Slider(
                            value = radius,
                            onValueChange = { radius = it },
                            valueRange = MIN_RADIUS.toFloat()..MAX_RADIUS.toFloat(),
                            steps = ((MAX_RADIUS - MIN_RADIUS) / 100 - 1),
                        )
                    }

                    Text(
                        centerText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (center == null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("启用", modifier = Modifier.weight(1f))
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                    }

                    saveError?.let { msg ->
                        Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }

                    Button(
                        onClick = {
                            val point = center
                            if (point != null) {
                                onSave(name.trim(), type, radius.toInt(), point.first, point.second, enabled)
                            }
                        },
                        enabled = valid && !saving,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text(if (isEdit) "保存" else "创建")
                        }
                    }
                }
            }
        }
    }
}
