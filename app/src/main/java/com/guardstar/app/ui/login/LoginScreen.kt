package com.guardstar.app.ui.login

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.guardstar.app.core.role.UserRole
import com.guardstar.app.data.auth.AuthApi

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    role: UserRole,
    authApi: AuthApi,
    onLoggedIn: () -> Unit,
    onBack: () -> Unit,
) {
    val viewModel: LoginViewModel = viewModel(
        key = "login-${role.name}",
        factory = viewModelFactory {
            initializer { LoginViewModel(authApi, role) }
        },
    )
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.loggedIn) {
        if (state.loggedIn) onLoggedIn()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (role == UserRole.WARD) "登录 · 被守护端" else "登录 · 守护端") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 24.dp)
        ) {
            Text(
                text = "使用手机号登录",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "验证码用于确认本人操作，不会用于其他用途。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(28.dp))

            OutlinedTextField(
                value = state.phone,
                onValueChange = viewModel::onPhoneChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("手机号") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            )
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = state.code,
                onValueChange = viewModel::onCodeChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("验证码") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                trailingIcon = {
                    TextButton(
                        onClick = viewModel::sendCode,
                        enabled = state.phoneValid && !state.sendingCode,
                    ) {
                        Text(if (state.sendingCode) "发送中" else "获取验证码")
                    }
                },
            )

            state.devCode?.let { code ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "开发环境验证码：$code",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            state.error?.let { message ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(28.dp))
            Button(
                onClick = viewModel::login,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.canSubmit,
            ) {
                if (state.loggingIn) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.size(8.dp))
                }
                Text("登录")
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = "登录即表示同意仅在授权范围内共享位置。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}
