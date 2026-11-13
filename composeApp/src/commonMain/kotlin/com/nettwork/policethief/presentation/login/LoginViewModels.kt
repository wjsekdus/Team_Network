package com.nettwork.policethief.presentation.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nettwork.policethief.domain.model.AppError
import com.nettwork.policethief.domain.model.AppResult
import com.nettwork.policethief.domain.repository.SessionRestoreResult
import com.nettwork.policethief.domain.usecase.GuestLoginUseCase
import com.nettwork.policethief.domain.usecase.RestoreSessionUseCase
import com.nettwork.policethief.platform.AppPermission
import com.nettwork.policethief.platform.PermissionController
import com.nettwork.policethief.platform.PermissionState
import com.nettwork.policethief.shared.rules.NicknamePolicy
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ---------------- 앱 시작(세션 복구) ----------------

sealed interface LaunchUiState {
    data object Restoring : LaunchUiState
    data class Failed(val message: String) : LaunchUiState
}

sealed interface LaunchEvent {
    data object GoHome : LaunchEvent
    data object GoLogin : LaunchEvent
}

/** 저장된 토큰으로 로그인 상태를 복구한다. 네트워크 오류 시 저장된 프로필로 오프라인 진입한다. */
class LaunchViewModel(private val restoreSession: RestoreSessionUseCase) : ViewModel() {
    private val state = MutableStateFlow<LaunchUiState>(LaunchUiState.Restoring)
    val uiState: StateFlow<LaunchUiState> = state.asStateFlow()

    private val eventChannel = Channel<LaunchEvent>(Channel.BUFFERED)
    val events: Flow<LaunchEvent> = eventChannel.receiveAsFlow()

    init {
        restore()
    }

    fun restore() {
        state.value = LaunchUiState.Restoring
        viewModelScope.launch {
            when (val result = restoreSession()) {
                is AppResult.Success -> eventChannel.send(
                    if (result.value is SessionRestoreResult.Restored) LaunchEvent.GoHome else LaunchEvent.GoLogin,
                )
                is AppResult.Failure -> state.value = LaunchUiState.Failed(result.error.userMessage)
            }
        }
    }

    fun continueToLogin() {
        viewModelScope.launch { eventChannel.send(LaunchEvent.GoLogin) }
    }
}

// ---------------- 게스트 로그인 (S-01) ----------------

data class LoginUiState(
    val nickname: String = "",
    val isSubmitting: Boolean = false,
    /** 닉네임 입력칸 아래 표시할 문구. */
    val fieldError: String? = null,
    /** 서버·네트워크 오류. 입력값은 유지된다. */
    val submitError: String? = null,
) {
    val canSubmit: Boolean get() = !isSubmitting && nickname.isNotBlank()
}

sealed interface LoginEvent {
    data class LoggedIn(val needsPermission: Boolean) : LoginEvent
}

/**
 * 상태 전이: Idle → (제출) Submitting → 성공: LoggedIn 이벤트 / 실패: Idle + 오류 문구.
 * 제출 중에는 버튼이 비활성화되어 중복 요청을 막는다.
 */
class LoginViewModel(
    private val guestLogin: GuestLoginUseCase,
    private val permissions: PermissionController,
) : ViewModel() {
    private val state = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = state.asStateFlow()

    private val eventChannel = Channel<LoginEvent>(Channel.BUFFERED)
    val events: Flow<LoginEvent> = eventChannel.receiveAsFlow()

    fun onNicknameChange(value: String) {
        // 최대 길이보다 약간 더 받아 두고 검증 문구로 안내한다(입력이 갑자기 막히면 혼란스럽기 때문).
        state.update { it.copy(nickname = value.take(NicknamePolicy.MAX_LENGTH + 4), fieldError = null, submitError = null) }
    }

    fun submit() {
        val current = state.value
        if (!current.canSubmit) return
        state.update { it.copy(isSubmitting = true, fieldError = null, submitError = null) }
        viewModelScope.launch {
            when (val result = guestLogin(current.nickname)) {
                is AppResult.Success -> {
                    state.update { it.copy(isSubmitting = false) }
                    val needsPermission = permissions.status(AppPermission.LOCATION) != PermissionState.GRANTED
                    eventChannel.send(LoginEvent.LoggedIn(needsPermission))
                }
                is AppResult.Failure -> state.update {
                    when (val error = result.error) {
                        is AppError.Validation -> it.copy(isSubmitting = false, fieldError = error.userMessage)
                        else -> it.copy(isSubmitting = false, submitError = error.userMessage)
                    }
                }
            }
        }
    }
}
