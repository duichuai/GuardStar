package com.guardstar.app.ui.guardian

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.guardstar.app.data.event.EventApi
import com.guardstar.app.data.event.EventItem
import com.guardstar.app.data.fence.FenceApi
import com.guardstar.app.data.model.RelationSummary
import com.guardstar.app.data.relation.InviteResponse
import com.guardstar.app.data.relation.RelationApi
import com.guardstar.app.data.sos.SosApi
import com.guardstar.app.service.Notifications
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuardianHomeScreen(
    relationApi: RelationApi,
    eventApi: EventApi,
    sosApi: SosApi,
    fenceApi: FenceApi,
    locationApi: com.guardstar.app.data.location.LocationApi,
    onSelectRelation: (RelationSummary) -> Unit,
    onManageFences: (RelationSummary) -> Unit,
    onViewTrack: (RelationSummary) -> Unit,
    pendingEvent: Notifications.EventTarget?,
    onPendingEventHandled: () -> Unit,
    onLogout: () -> Unit,
) {
    val context = LocalContext.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    var relations by remember { mutableStateOf<List<RelationSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }
    // 最近事件：relationId -> 事件列表；告警区只展示其中未读项。
    var eventsByRelation by remember { mutableStateOf<Map<String, List<EventItem>>>(emptyMap()) }
    var eventsLoaded by remember { mutableStateOf(false) }
    var selectedEvent by remember { mutableStateOf<Pair<RelationSummary, EventItem>?>(null) }
    var ackBusy by remember { mutableStateOf(false) }
    var readBusy by remember { mutableStateOf(false) }
    var ackTarget by remember { mutableStateOf<Pair<RelationSummary, EventItem>?>(null) }
    val scope = rememberCoroutineScope()
    val sosAlerts = eventsByRelation.mapValues { (_, events) ->
        events.filter { it.type == "sos_created" && !it.read }
    }.filterValues { it.isNotEmpty() }
    val otherAlerts = eventsByRelation.mapValues { (_, events) ->
        events.filter { it.type != "sos_created" && !it.read }
    }.filterValues { it.isNotEmpty() }

    LaunchedEffect(refreshKey) {
        loading = true
        runCatching { relationApi.list() }
            .onSuccess { relations = it }
            .onFailure { error = it.message ?: "加载失败" }
        loading = false
    }

    // 轮询各关系下的全部事件，每 15 秒一次；通知按事件 ID 本地去重。
    LaunchedEffect(relations) {
        eventsLoaded = false
        while (true) {
            if (relations.isNotEmpty()) {
                // 单个关系拉取失败时保留上次数据，避免弱网下告警短暂消失。
                val loaded = eventsByRelation.toMutableMap()
                for (rel in relations) {
                    runCatching { eventApi.list(rel.relationId, limit = 50) }
                        .onSuccess { resp ->
                            loaded[rel.relationId] = resp.items
                            Notifications.notifyNewEvents(
                                context = context,
                                relation = rel,
                                events = resp.items.filter { !it.read },
                            )
                        }
                }
                eventsByRelation = loaded.filterKeys { relationId ->
                    relations.any { it.relationId == relationId }
                }
            } else {
                eventsByRelation = emptyMap()
            }
            eventsLoaded = true
            delay(15_000)
        }
    }

    LaunchedEffect(pendingEvent, eventsByRelation, eventsLoaded, loading) {
        val target = pendingEvent ?: return@LaunchedEffect
        if (!eventsLoaded || loading) return@LaunchedEffect
        val relation = relations.firstOrNull { it.relationId == target.relationId }
        val event = eventsByRelation[target.relationId]?.firstOrNull { it.eventId == target.eventId }
        if (relation != null && event != null) {
            selectedEvent = relation to event
        } else {
            error = "该提醒不存在或已超出最近事件范围"
        }
        onPendingEventHandled()
    }

    fun markEventRead(relation: RelationSummary, event: EventItem, onRead: () -> Unit = {}) {
        if (event.read) {
            selectedEvent = null
            onRead()
            return
        }
        if (readBusy) return
        readBusy = true
        scope.launch {
            runCatching { eventApi.markRead(event.eventId) }
                .onSuccess {
                    eventsByRelation = eventsByRelation.mapValues { (relationId, events) ->
                        if (relationId != relation.relationId) return@mapValues events
                        events.map { item ->
                            if (item.eventId == event.eventId) item.copy(read = true) else item
                        }
                    }
                    Notifications.cancelEvent(context, event.eventId)
                    selectedEvent = null
                    readBusy = false
                    onRead()
                }
                .onFailure { failure ->
                    error = failure.message ?: "标记已读失败"
                    readBusy = false
                }
        }
    }

    fun ackSos(relation: RelationSummary, event: EventItem, action: String, reason: String = "") {
        if (ackBusy) return
        val sosId = runCatching {
            Json.parseToJsonElement(event.payload).jsonObject["sosId"]?.jsonPrimitive?.content
        }.getOrNull().orEmpty()
        if (sosId.isBlank()) return
        ackBusy = true
        scope.launch {
            runCatching { sosApi.ack(sosId, action, reason) }
                .onSuccess {
                    runCatching { eventApi.markRead(event.eventId) }
                    eventsByRelation = eventsByRelation.mapValues { (relationId, events) ->
                        if (relationId != relation.relationId) return@mapValues events
                        events.map { item ->
                            if (item.eventId == event.eventId) item.copy(read = true) else item
                        }
                    }
                    Notifications.cancelEvent(context, event.eventId)
                    selectedEvent = null
                    ackTarget = null
                }
                .onFailure { error = it.message ?: "处理失败" }
            ackBusy = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("守护端") },
                actions = {
                    TextButton(onClick = onLogout) { Text("退出登录") }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            when {
                loading -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }

                relations.isEmpty() -> Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(40.dp))
                    Icon(
                        Icons.Outlined.PersonAdd,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "还没有被守护人",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "生成 6 位邀请码，让对方在自己的手机上确认后即完成配对。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = { showAddDialog = true }) {
                        Text("添加被守护人")
                    }
                }

                else -> Column {
                    // SOS 告警区：展示各被守护人未处理的求助。
                    if (sosAlerts.isNotEmpty()) {
                        Text(
                            text = "紧急求助",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(8.dp))
                        sosAlerts.forEach { (relId, events) ->
                            val relation = relations.firstOrNull { it.relationId == relId } ?: return@forEach
                            events.forEach { event ->
                                SosAlertCard(
                                    relation = relation,
                                    event = event,
                                    onViewLocation = { onSelectRelation(relation) },
                                    onHandled = { ackSos(relation, event, "handled") },
                                    onFalseAlarm = { ackTarget = relation to event },
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }

                    if (otherAlerts.isNotEmpty()) {
                        Text(
                            text = "守护提醒",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(8.dp))
                        otherAlerts.forEach { (relId, events) ->
                            val relation = relations.firstOrNull { it.relationId == relId } ?: return@forEach
                            events.forEach { event ->
                                EventAlertCard(
                                    relation = relation,
                                    event = event,
                                    onClick = { selectedEvent = relation to event },
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }

                    relations.forEach { relation ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectRelation(relation) },
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.Shield, contentDescription = null)
                                Column {
                                    Text(
                                        text = relation.peerNickname.ifBlank { "被守护人" },
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        text = "关系状态：${relation.status}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedButton(
                                    onClick = { onSelectRelation(relation) },
                                    modifier = Modifier.weight(1f),
                                ) { Text("查看位置") }
                                OutlinedButton(
                                    onClick = { onManageFences(relation) },
                                    modifier = Modifier.weight(1f),
                                ) { Text("围栏管理") }
                                OutlinedButton(
                                    onClick = { onViewTrack(relation) },
                                    modifier = Modifier.weight(1f),
                                ) { Text("历史轨迹") }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { showAddDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("添加被守护人")
                    }
                }
            }

            error?.let { message ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    if (showAddDialog) {
        AddWardDialog(
            relationApi = relationApi,
            onDismiss = {
                showAddDialog = false
                refreshKey++
            },
        )
    }

    selectedEvent?.let { (relation, event) ->
        EventDetailDialog(
            relation = relation,
            event = event,
            busy = readBusy || ackBusy,
            onDismiss = { selectedEvent = null },
            onViewLocation = {
                if (event.type == "sos_created") {
                    selectedEvent = null
                    onSelectRelation(relation)
                } else {
                    markEventRead(relation, event) { onSelectRelation(relation) }
                }
            },
            onRead = { markEventRead(relation, event) },
            onHandled = { ackSos(relation, event, "handled") },
        )
    }

    ackTarget?.let { (relation, event) ->
        FalseAlarmDialog(
            busy = ackBusy,
            onConfirm = { reason -> ackSos(relation, event, "false_alarm", reason) },
            onDismiss = { ackTarget = null },
        )
    }
}

@Composable
private fun AddWardDialog(
    relationApi: RelationApi,
    onDismiss: () -> Unit,
) {
    var nickname by remember { mutableStateOf("") }
    var invite by remember { mutableStateOf<InviteResponse?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun createInvite() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            runCatching { relationApi.createInvite(nickname.trim()) }
                .onSuccess { invite = it }
                .onFailure { error = it.message ?: "生成邀请码失败" }
            busy = false
        }
    }

    val currentInvite = invite
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (currentInvite == null) "添加被守护人" else "邀请码") },
        text = {
            Column {
                if (currentInvite == null) {
                    Text(
                        text = "输入对方称呼（可选），生成后请让对方在 10 分钟内输入。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = nickname,
                        onValueChange = { nickname = it.take(20) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("称呼") },
                        singleLine = true,
                    )
                } else {
                    Text(
                        text = currentInvite.inviteCode,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 8.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "10 分钟内有效，仅可使用一次。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    formatExpiry(currentInvite.expiresAt)?.let { text ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "有效期至 $text",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                error?.let { message ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            if (currentInvite == null) {
                Button(onClick = { createInvite() }, enabled = !busy) {
                    Text(if (busy) "生成中…" else "生成邀请码")
                }
            } else {
                TextButton(onClick = onDismiss) { Text("完成") }
            }
        },
        dismissButton = {
            if (currentInvite == null) {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

private fun formatExpiry(expiresAt: String): String? = runCatching {
    Instant.parse(expiresAt)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm"))
}.getOrNull()

@Composable
private fun EventAlertCard(
    relation: RelationSummary,
    event: EventItem,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (event.level == "p1") {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${relation.peerNickname.ifBlank { "被守护人" }} · ${eventTitle(event)}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = formatEventTime(event.occurredAt),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(text = eventDescription(event), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun EventDetailDialog(
    relation: RelationSummary,
    event: EventItem,
    busy: Boolean,
    onDismiss: () -> Unit,
    onViewLocation: () -> Unit,
    onRead: () -> Unit,
    onHandled: () -> Unit,
) {
    val isSos = event.type == "sos_created"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${relation.peerNickname.ifBlank { "被守护人" }} · ${eventTitle(event)}") },
        text = {
            Column {
                Text(text = eventDescription(event), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "发生时间：${formatEventTime(event.occurredAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            if (isSos) {
                Button(onClick = onHandled, enabled = !busy) { Text("已处理") }
            } else {
                TextButton(onClick = onViewLocation, enabled = !busy) { Text("查看位置") }
            }
        },
        dismissButton = {
            if (isSos) {
                TextButton(onClick = onViewLocation, enabled = !busy) { Text("查看位置") }
            } else {
                TextButton(onClick = onRead, enabled = !busy) { Text("标记已读") }
            }
        },
    )
}

private fun eventTitle(event: EventItem): String = when (event.type) {
    "sos_created" -> "SOS 紧急求助"
    "low_battery" -> "设备电量低"
    "device_offline" -> "设备已离线"
    "device_online" -> "设备恢复在线"
    "location_disabled" -> "定位权限已关闭"
    "fence_exit" -> "离开围栏"
    "fence_enter" -> "进入围栏"
    "reporting_paused" -> "位置共享已暂停"
    "sos_enabled" -> "SOS 高频定位已开启"
    else -> "守护提醒"
}

private fun eventDescription(event: EventItem): String {
    val payload = runCatching {
        Json.parseToJsonElement(event.payload).jsonObject
    }.getOrNull()
    fun field(name: String): String? = payload?.get(name)?.jsonPrimitive?.content
    return when (event.type) {
        "sos_created" -> "收到紧急求助，请尽快查看位置并处理。"
        "low_battery" -> "当前电量 ${field("battery") ?: "未知"}%，请提醒对方及时充电。"
        "device_offline" -> "已连续 ${field("offlineAfterMin") ?: "30"} 分钟未收到设备上报。"
        "device_online" -> "已重新收到设备上报。"
        "location_disabled" -> "定位权限被关闭，位置共享可能已中断。"
        "fence_exit", "fence_enter" -> "围栏：${field("fenceName") ?: "未命名"}，点击查看触发位置。"
        "reporting_paused" -> "被守护端已暂停位置上报。"
        "sos_enabled" -> "设备已进入 30 秒高频定位模式。"
        else -> "点击查看事件与最新位置。"
    }
}

@Composable
private fun SosAlertCard(
    relation: RelationSummary,
    event: EventItem,
    onViewLocation: () -> Unit,
    onHandled: () -> Unit,
    onFalseAlarm: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${relation.peerNickname.ifBlank { "被守护人" }} 发出求助",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = formatEventTime(event.occurredAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onViewLocation,
                    modifier = Modifier.weight(1f),
                ) { Text("查看位置") }
                Button(
                    onClick = onHandled,
                    modifier = Modifier.weight(1f),
                ) { Text("已处理") }
                TextButton(
                    onClick = onFalseAlarm,
                    modifier = Modifier.weight(1f),
                ) { Text("误报") }
            }
        }
    }
}

@Composable
private fun FalseAlarmDialog(
    busy: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("标记为误报") },
        text = {
            Column {
                Text(
                    text = "请填写误报原因（将通知被守护人）。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it.take(100) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("原因") },
                    singleLine = false,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (reason.isNotBlank()) onConfirm(reason.trim()) },
                enabled = !busy && reason.isNotBlank(),
            ) { Text(if (busy) "提交中…" else "确认") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        },
    )
}

private fun formatEventTime(occurredAt: String): String = runCatching {
    val instant = Instant.parse(occurredAt)
    val agoSec = (System.currentTimeMillis() - instant.toEpochMilli()) / 1000
    val rel = when {
        agoSec < 60 -> "${agoSec}秒前"
        agoSec < 3600 -> "${agoSec / 60}分钟前"
        agoSec < 86400 -> "${agoSec / 3600}小时前"
        else -> "${agoSec / 86400}天前"
    }
    val abs = instant.atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    "$rel（$abs）"
}.getOrDefault(occurredAt)
