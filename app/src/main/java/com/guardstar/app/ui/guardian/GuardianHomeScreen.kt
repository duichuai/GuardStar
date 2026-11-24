package com.guardstar.app.ui.guardian

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.guardstar.app.data.model.RelationSummary
import com.guardstar.app.data.relation.InviteResponse
import com.guardstar.app.data.relation.RelationApi
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuardianHomeScreen(
    relationApi: RelationApi,
    onLogout: () -> Unit,
) {
    var relations by remember { mutableStateOf<List<RelationSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        loading = true
        runCatching { relationApi.list() }
            .onSuccess { relations = it }
            .onFailure { error = it.message ?: "加载失败" }
        loading = false
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
                    relations.forEach { relation ->
                        Card(modifier = Modifier.fillMaxWidth()) {
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
