package com.guardstar.app.ui.ward

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Group
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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.guardstar.app.data.location.LocationRepository
import com.guardstar.app.data.model.RelationSummary
import com.guardstar.app.data.relation.RelationApi
import com.guardstar.app.service.WardLocationService
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WardHomeScreen(
    relationApi: RelationApi,
    locationRepository: LocationRepository,
    onLogout: () -> Unit,
) {
    val context = LocalContext.current
    val locStatus by locationRepository.status.collectAsState()
    var hasFineLocation by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var hasNotifications by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            } else true
        )
    }

    val basePermissions = remember {
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }
    val baseLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasFineLocation = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        hasNotifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            result[Manifest.permission.POST_NOTIFICATIONS] == true
        } else true
    }
    val bgLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) WardLocationService.start(context)
    }

    fun startSharing() {
        if (!hasFineLocation || !hasNotifications) {
            baseLauncher.launch(basePermissions)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            WardLocationService.start(context)
        }
    }

    var relations by remember { mutableStateOf<List<RelationSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var inviteCode by remember { mutableStateOf("") }
    var joining by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(refreshKey) {
        loading = true
        runCatching { relationApi.list() }
            .onSuccess { relations = it }
            .onFailure { error = it.message ?: "加载失败" }
        loading = false
    }

    fun joinByInvite() {
        val code = inviteCode.trim()
        if (code.length != 6 || joining) return
        joining = true
        error = null
        scope.launch {
            runCatching { relationApi.acceptInvite(code) }
                .onSuccess {
                    inviteCode = ""
                    refreshKey++
                }
                .onFailure { error = it.message ?: "配对失败" }
            joining = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("被守护端") },
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

                relations.isEmpty() -> Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            text = "尚未开始共享位置",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "向监护人索取 6 位邀请码并输入；配对前不会采集或上传任何位置。",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(14.dp))
                        OutlinedTextField(
                            value = inviteCode,
                            onValueChange = {
                                inviteCode = it.filter(Char::isDigit).take(6)
                                error = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("邀请码") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { joinByInvite() },
                            enabled = inviteCode.length == 6 && !joining,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (joining) "配对中…" else "确认配对")
                        }
                    }
                }

                else -> Column {
                    Text("我的监护人", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    relations.forEach { relation ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.Group, contentDescription = null)
                                Column {
                                    Text(
                                        text = relation.peerNickname.ifBlank { "监护人" },
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        text = "可以查看你的位置",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    Spacer(Modifier.height(16.dp))

                    // 位置共享控制
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                text = "位置共享",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = if (locStatus.running) {
                                    "正在共享位置，待上报 ${locStatus.pendingCount} 条"
                                } else {
                                    "开启后按 5 分钟间隔采集并上报，息屏也会继续运行。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            locStatus.lastUploadAt?.let { at ->
                                val ago = (System.currentTimeMillis() - at) / 1000
                                Text(
                                    text = "最近上报：${ago}秒前",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            locStatus.lastError?.let { msg ->
                                Text(
                                    text = "上报异常：$msg",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            if (locStatus.running) {
                                OutlinedButton(
                                    onClick = { WardLocationService.stop(context) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("停止共享")
                                }
                            } else {
                                Button(
                                    onClick = { startSharing() },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("启动位置共享")
                                }
                            }
                        }
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

            Spacer(Modifier.height(32.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Card(
                    shape = CircleShape,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.size(132.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Filled.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.height(4.dp))
                            Text("SOS", fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = if (relations.isEmpty()) "配对后可用" else "长按 3 秒发出求助（即将接入）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
