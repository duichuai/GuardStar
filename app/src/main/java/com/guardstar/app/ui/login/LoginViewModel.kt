package com.guardstar.app.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guardstar.app.data.auth.AuthApi
import com.guardstar.app.core.role.UserRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val phone: String = "",
    val code: String = "",
    val devCode: String? = null,
    val sendingCode: Boolean = false,
    val loggingIn: Boolean = false,
    val error: String? = null,
    val loggedIn: Boolean = false,
) {
    val phoneValid: Boolean
        get() = PHONE_PATTERN.matches(phone)

    val canSubmit: Boolean
        get() = phoneValid && code.length == 6 && !loggingIn

    private companion object {
        val PHONE_PATTERN = Regex("^1[3-9]\\d{9}$")
    }
}

class LoginViewModel(
    private val authApi: AuthApi,
    private val role: UserRole,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onPhoneChange(value: String) {
        _state.update { it.copy(phone = value.filter(Char::isDigit).take(11), error = null) }
    }

    fun onCodeChange(value: String) {
        _state.update { it.copy(code = value.filter(Char::isDigit).take(6), error = null) }
    }

    fun sendCode() {
        val current = _state.value
        if (!current.phoneValid || current.sendingCode) return
        _state.update { it.copy(sendingCode = true, error = null) }
        viewModelScope.launch {
            runCatching { authApi.sendCode(current.phone) }
                .onSuccess { result ->
                    _state.update { it.copy(sendingCode = false, devCode = result.devCode) }
                }
                .onFailure { error ->
                    _state.update { it.copy(sendingCode = false, error = error.message ?: "验证码发送失败") }
                }
        }
    }

    fun login() {
        val current = _state.value
        if (!current.canSubmit) return
        _state.update { it.copy(loggingIn = true, error = null) }
        viewModelScope.launch {
            runCatching { authApi.login(current.phone, current.code, role) }
                .onSuccess { _state.update { s -> s.copy(loggingIn = false, loggedIn = true) } }
                .onFailure { error ->
                    _state.update { s ->
                        s.copy(loggingIn = false, error = error.message ?: "登录失败")
                    }
                }
        }
    }
}
