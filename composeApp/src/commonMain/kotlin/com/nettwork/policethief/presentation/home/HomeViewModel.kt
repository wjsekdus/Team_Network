package com.nettwork.policethief.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nettwork.policethief.config.AppConfig
import com.nettwork.policethief.data.local.DevSettingsStore
import com.nettwork.policethief.domain.model.AppError
import com.nettwork.policethief.domain.model.AppResult
import com.nettwork.policethief.domain.model.LocationFailureReason
import com.nettwork.policethief.domain.model.PlayerLocation
import com.nettwork.policethief.domain.model.RealtimeConnectionState
import com.nettwork.policethief.domain.model.RealtimeEvent
import com.nettwork.policethief.domain.model.ServerStatus
import com.nettwork.policethief.domain.model.User
import com.nettwork.policethief.domain.repository.AuthRepository
import com.nettwork.policethief.domain.repository.LocationRepository
import com.nettwork.policethief.domain.repository.RealtimeRepository
import com.nettwork.policethief.domain.usecase.CheckServerStatusUseCase
import com.nettwork.policethief.domain.usecase.locationReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 서버 연결 상태(REST). */
sealed interface ServerUiState {
    data object Checking : ServerUiState
    data class Online(val status: ServerStatus) : ServerUiState
    data class Offline(val message: String) : ServerUiState
}

/** 현재 위치 카드 상태. 실패 시 마지막 위치를 함께 보여 준다(부분 상태). */
sealed interface LocationUiState {
    data object Idle : LocationUiState
    data object Loading : LocationUiState
    data class Available(val location: PlayerLocation) : LocationUiState
    data class Failed(val message: String, val reason: LocationFailureReason?, val lastKnown: PlayerLocation?) : LocationUiState
}

data class HomeUiState(
    val user: User? = null,
    val server: ServerUiState = ServerUiState.Checking,
    val realtime: RealtimeConnectionState = RealtimeConnectionState.Disconnected,
    val lastRealtimeEvent: RealtimeEvent? = null,
    val location: LocationUiState = LocationUiState.Idle,
    val lastSentAtEpochMillis: Long? = null,
    val sendMessage: String? = null,
    val isSending: Boolean = false,
    val isMockLocation: Boolean = false,
    val isMockGame: Boolean = false,
    val isDevBuild: Boolean = false,
)

/**
 * 홈(S-04). 실제 서버와 연결되는 화면:
 * 사용자 정보(GET /api/users/me 로 복구된 값), 서버 상태(GET /api/health), WebSocket 상태, 현재 위치(GPS/테스트 좌표) + 서버 전송.
 */
class HomeViewModel(
    private val auth: AuthRepository,
    private val checkServer: CheckServerStatusUseCase,
    private val locations: LocationRepository,
    private val realtime: RealtimeRepository,
    devSettings: DevSettingsStore,
    config: AppConfig,
) : ViewModel() {
    private val local = MutableStateFlow(
        HomeUiState(
            isDevBuild = config.isDevBuild,
            isMockGame = devSettings.current.value.useMockGameData,
        ),
    )
    private val lastEvent = MutableStateFlow<RealtimeEvent?>(null)

    val uiState: StateFlow<HomeUiState> = combine(
        local, auth.currentUser, realtime.connectionState, lastEvent, combine(locations.lastSentAtEpochMillis, devSettings.current) { a, b -> a to b },
    ) { state, user, connection, event, (lastSent, dev) ->
        state.copy(
            user = user,
            realtime = connection,
            lastRealtimeEvent = event,
            lastSentAtEpochMillis = lastSent,
            isMockLocation = state.isDevBuild && dev.useMockLocation,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    init {
        viewModelScope.launch { realtime.events.collect { lastEvent.value = it } }
        refreshServer()
        refreshLocation()
        realtime.connect()
    }

    fun refreshServer() {
        local.update { it.copy(server = ServerUiState.Checking) }
        viewModelScope.launch {
            val server = when (val result = checkServer()) {
                is AppResult.Success -> ServerUiState.Online(result.value)
                is AppResult.Failure -> ServerUiState.Offline(result.error.userMessage)
            }
            local.update { it.copy(server = server) }
            // 서버가 살아났는데 WebSocket 이 실패 상태라면 다시 연결을 시도한다.
            if (server is ServerUiState.Online && realtime.connectionState.value is RealtimeConnectionState.Failed) {
                realtime.disconnect()
                realtime.connect()
            }
            if (server is ServerUiState.Online) auth.refreshMe()
        }
    }

    fun refreshLocation() {
        local.update { it.copy(location = LocationUiState.Loading, sendMessage = null) }
        viewModelScope.launch {
            val next = when (val result = locations.fetchCurrentLocation()) {
                is AppResult.Success -> LocationUiState.Available(result.value)
                is AppResult.Failure -> LocationUiState.Failed(result.error.userMessage, result.error.locationReason(), locations.lastLocation.value)
            }
            local.update { it.copy(location = next) }
        }
    }

    /** 현재 표시 중인 위치를 서버로 보낸다(POST /api/locations). */
    fun sendLocation() {
        val location = (local.value.location as? LocationUiState.Available)?.location ?: return
        if (local.value.isSending) return
        local.update { it.copy(isSending = true, sendMessage = null) }
        viewModelScope.launch {
            val message = when (val result = locations.sendLocation(location)) {
                is AppResult.Success -> "서버 저장 완료"
                is AppResult.Failure -> when (result.error) {
                    AppError.Unauthorized -> "로그인이 만료되어 전송하지 못했습니다."
                    else -> "전송 실패: ${result.error.userMessage}"
                }
            }
            local.update { it.copy(isSending = false, sendMessage = message) }
        }
    }

    fun reconnectRealtime() {
        realtime.disconnect()
        realtime.connect()
    }
}
