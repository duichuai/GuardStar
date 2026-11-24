package com.guardstar.app.ui.guardian

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.guardstar.app.data.fence.Fence
import com.guardstar.app.data.fence.FenceApi
import com.guardstar.app.data.fence.FenceCenter
import com.guardstar.app.data.fence.FenceCreateRequest
import com.guardstar.app.data.fence.FenceUpdateRequest
import kotlinx.coroutines.launch

private const val MIN_RADIUS = 100
private const val MAX_RADIUS = 5000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FenceScreen(
    relationId: String,
    wardNickname: String,
    fenceApi: FenceApi,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val fences = remember { mutableStateListOf<Fence>() }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var showEditor by remember { mutableStateOf<Fence?>(null) } // null = 新建，非空 = 编辑

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
        scope.launch {
            runCatching {
                val editing = showEditor
                if (editing == null) {
                    fenceApi.create(
                        relationId, FenceCreateRequest(
                            name = name,
                            center = FenceCenter(lat, lng),
                            radiusM = radius,
                            type = type,
                            enabled = enabled,
                        )
                    )
                } else {
                    fenceApi.update(
                        editing.fenceId, FenceUpdateRequest(
                            name = name,
                            center = FenceCenter(lat, lng),
                            radiusM = radius,
                            type = type,
                            enabled = enabled,
                        )
                    )
                }
            }.onSuccess { refreshKey++ }
                .onFailure { error = it.message ?: "保存失败" }
            showEditor = null
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
                    IconButton(onClick = { showEditor = Fence(
                        fenceId = "", relationId = relationId, name = "",
                        lat = 0.0, lng = 0.0, radiusM = 200, type = "safe", enabled = true,
                    ) }) {
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
                    Text("点击右上角 + 添加安全区/危险区", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(fences, key = { it.fenceId }) { fence ->
                        FenceItem(
                            fence = fence,
                            onClick = { showEditor = fence },
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

    showEditor?.let { editing ->
        FenceEditorDialog(
            editing = editing,
            onDismiss = { showEditor = null },
            onConfirm = { name, type, radius, lat, lng, enabled ->
                saveFence(name, type, radius, lat, lng, enabled)
            },
        )
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

@Composable
private fun FenceEditorDialog(
    editing: Fence,
    onDismiss: () -> Unit,
    onConfirm: (name: String, type: String, radius: Int, lat: Double, lng: Double, enabled: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf(editing.name) }
    var type by remember { mutableStateOf(editing.type) }
    var radius by remember { mutableStateOf(editing.radiusM.toFloat()) }
    var latText by remember { mutableStateOf(if (editing.lat == 0.0 && editing.lng == 0.0) "" else "%.6f".format(editing.lat)) }
    var lngText by remember { mutableStateOf(if (editing.lat == 0.0 && editing.lng == 0.0) "" else "%.6f".format(editing.lng)) }
    var enabled by remember { mutableStateOf(editing.enabled) }

    val isEdit = editing.fenceId.isNotBlank()
    val lat = latText.toDoubleOrNull()
    val lng = lngText.toDoubleOrNull()
    val valid = name.length in 1..20 &&
        lat != null && lat in -90.0..90.0 &&
        lng != null && lng in -180.0..180.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) "编辑围栏" else "新建围栏") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(20) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("名称（1-20字）") },
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
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
                Spacer(Modifier.height(12.dp))
                Text("半径：${radius.toInt()} 米", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = radius,
                    onValueChange = { radius = it },
                    valueRange = MIN_RADIUS.toFloat()..MAX_RADIUS.toFloat(),
                    steps = ((MAX_RADIUS - MIN_RADIUS) / 100 - 1),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = latText,
                    onValueChange = { latText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("中心纬度（-90 ~ 90）") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = lngText,
                    onValueChange = { lngText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("中心经度（-180 ~ 180）") },
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("启用", modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (valid && lat != null && lng != null) {
                        onConfirm(name.trim(), type, radius.toInt(), lat, lng, enabled)
                    }
                },
                enabled = valid,
            ) { Text(if (isEdit) "保存" else "创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
