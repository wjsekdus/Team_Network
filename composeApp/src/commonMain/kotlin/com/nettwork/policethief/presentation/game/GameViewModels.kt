package com.nettwork.policethief.presentation.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nettwork.policethief.core.EpochClock
import com.nettwork.policethief.domain.model.AppResult
import com.nettwork.policethief.domain.model.Game
import com.nettwork.policethief.domain.model.GamePlayer
import com.nettwork.policethief.domain.model.PlayerLocation
import com.nettwork.policethief.domain.repository.AuthRepository
import com.nettwork.policethief.domain.repository.GameRepository
import com.nettwork.policethief.domain.repository.LocationRepository
import com.nettwork.policethief.domain.repository.MockGameController
import com.nettwork.policethief.platform.BackgroundLocationController
import com.nettwork.policethief.shared.geo.GeoMath
import com.nettwork.policethief.shared.geo.GeoPoint
import com.nettwork.policethief.shared.model.GameEndReason
import com.nettwork.policethief.shared.model.GameResult
import com.nettwork.policethief.shared.model.GameStatus
import com.nettwork.policethief.shared.model.PlayerRole
import com.nettwork.policethief.shared.model.PlayerStatus
import com.nettwork.policethief.shared.rules.GameRules
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 경기 지도 상태(보고서 4.3: 기본·로딩·비어 있음·오류·부분). */
data class GameMapUiState(
    val isLoading: Boolean = true,
    val loadError: String? = null,
    val game: Game? = null,
    val me: GamePlayer? = null,
    val myLocation: PlayerLocation? = null,
    val myLocationError: String? = null,
    val others: List<PlayerLocation> = emptyList(),
    val othersReceivedAtEpochMillis: Long? = null,
    /** 부분 상태: 다른 참가자 위치 조회 실패(이전 정보 기준으로 표시). */
    val othersError: String? = null,
    val sendError: String? = null,
    val lastSentAtEpochMillis: Long? = null,
    val nowEpochMillis: Long = 0,
) {
    val remainingMillis: Long get() = ((game?.endsAtEpochMillis ?: nowEpochMillis) - nowEpochMillis).coerceAtLeast(0)

    /** 30초 이상 위치를 받지 못하면 지연 경고(보고서 NFR-A02). */
    val isLocationStale: Boolean
        get() = othersReceivedAtEpochMillis?.let { nowEpochMillis - it > GameRules.LOCATION_STALE_WARNING_MILLIS } ?: false

    val escortedThief: GamePlayer?
        get() = me?.let { m -> game?.players?.firstOrNull { it.capturedBy == m.userId && it.status == PlayerStatus.ESCORTED } }

    val capturedCount: Int
        get() = game?.players?.count { it.role == PlayerRole.THIEF && it.status != PlayerStatus.ACTIVE } ?: 0
}

sealed interface GameMapEvent {
    data object GameEnded : GameMapEvent
}

/**
 * 경찰/도둑 지도 공용 ViewModel.
 * 15초마다: 경기 상태 조회 → 내 위치 수집·전송(실제 서버) → 공개 범위 위치 조회. 1초마다 남은 시간 갱신.
 * 체포·수감·승패는 단말에서 판정하지 않고 서버(현재 Mock) 결과만 표시한다.
 */
class GameMapViewModel(
    private val gameId: String,
    private val games: GameRepository,
    private val locations: LocationRepository,
    private val auth: AuthRepository,
    private val clock: EpochClock,
    val mockController: MockGameController?,
) : ViewModel() {
    private val state = MutableStateFlow(GameMapUiState(nowEpochMillis = clock.nowMillis()))
    val uiState: StateFlow<GameMapUiState> = state.asStateFlow()

    private val eventChannel = Channel<GameMapEvent>(Channel.BUFFERED)
    val events: Flow<GameMapEvent> = eventChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            while (isActive) {
                refresh()
                delay(GameRules.LOCATION_UPDATE_INTERVAL_MILLIS)
            }
        }
        viewModelScope.launch {
            while (isActive) {
                state.update { it.copy(nowEpochMillis = clock.nowMillis()) }
                delay(1_000)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { refreshOnce() }
    }

    private suspend fun refreshOnce() {
        when (val result = games.getGame(gameId)) {
            is AppResult.Failure -> {
                state.update { it.copy(isLoading = false, loadError = result.error.userMessage) }
                return
            }
            is AppResult.Success -> {
                val game = result.value
                val myId = auth.currentUser.value?.userId
                state.update {
                    it.copy(isLoading = false, loadError = null, game = game, me = game.players.firstOrNull { p -> p.userId == myId } ?: game.players.firstOrNull())
                }
                if (game.status == GameStatus.FINISHED || game.status == GameStatus.CANCELLED) {
                    eventChannel.send(GameMapEvent.GameEnded)
                    return
                }
            }
        }

        when (val current = locations.fetchCurrentLocation()) {
            is AppResult.Success -> {
                state.update { it.copy(myLocation = current.value, myLocationError = null) }
                val sent = locations.sendLocation(current.value)
                state.update {
                    it.copy(
                        sendError = (sent as? AppResult.Failure)?.error?.userMessage,
                        lastSentAtEpochMillis = locations.lastSentAtEpochMillis.value,
                    )
                }
            }
            is AppResult.Failure -> state.update { it.copy(myLocationError = current.error.userMessage) }
        }

        when (val visible = games.visibleLocations(gameId)) {
            is AppResult.Success -> state.update {
                it.copy(others = visible.value, othersReceivedAtEpochMillis = clock.nowMillis(), othersError = null)
            }
            is AppResult.Failure -> state.update { it.copy(othersError = visible.error.userMessage) }
        }
    }

    /** D-02 확인 후 호출: 명시적 기권. 서버가 경기를 무효(CANCELLED/INVALID) 처리한다. */
    fun forfeit() {
        viewModelScope.launch {
            games.forfeit(gameId)
            eventChannel.send(GameMapEvent.GameEnded)
        }
    }

    fun mockFinish(result: GameResult, reason: GameEndReason) {
        mockController?.simulateFinish(gameId, result, reason)
        refresh()
    }

    fun mockJailArrival() {
        mockController?.simulateJailArrival(gameId)
        refresh()
    }
}

// ---------------- 호송 중 ----------------

data class EscortUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val escorted: GamePlayer? = null,
    val distanceToJailMeters: Double? = null,
    val jailRadiusMeters: Double = GameRules.JAIL_RADIUS_METERS,
    val locationError: String? = null,
) {
    val isInsideJail: Boolean get() = distanceToJailMeters?.let { it <= jailRadiusMeters } ?: false
}

/** 호송 중 화면: 담당 도둑과 감옥까지의 거리. 수감 확정은 서버 판정(두 사람 모두 10m 이내)을 기다린다. */
class EscortViewModel(
    private val gameId: String,
    private val games: GameRepository,
    private val locations: LocationRepository,
    private val auth: AuthRepository,
    val mockController: MockGameController?,
) : ViewModel() {
    private val state = MutableStateFlow(EscortUiState())
    val uiState: StateFlow<EscortUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            while (isActive) {
                refreshOnce()
                delay(5_000)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { refreshOnce() }
    }

    fun mockJailArrival() {
        mockController?.simulateJailArrival(gameId)
        refresh()
    }

    private suspend fun refreshOnce() {
        val game = when (val result = games.getGame(gameId)) {
            is AppResult.Success -> result.value
            is AppResult.Failure -> {
                state.update { it.copy(isLoading = false, error = result.error.userMessage) }
                return
            }
        }
        val myId = auth.currentUser.value?.userId ?: game.players.first().userId
        val escorted = game.players.firstOrNull { it.capturedBy == myId && it.status == PlayerStatus.ESCORTED }
        val location = locations.fetchCurrentLocation()
        val distance = (location as? AppResult.Success)?.value?.let {
            GeoMath.distanceMeters(GeoPoint(it.latitude, it.longitude), GeoPoint(game.centerLatitude, game.centerLongitude))
        }
        state.update {
            it.copy(
                isLoading = false,
                error = null,
                escorted = escorted,
                distanceToJailMeters = distance,
                jailRadiusMeters = game.jailRadiusMeters,
                locationError = (location as? AppResult.Failure)?.error?.userMessage,
            )
        }
    }
}

// ---------------- 절전 모드 ----------------

data class PowerSaveUiState(
    val endsAtEpochMillis: Long? = null,
    val nowEpochMillis: Long = 0,
    val lastSentAtEpochMillis: Long? = null,
    val backgroundSupported: Boolean = false,
    val backgroundRunning: Boolean = false,
)

/**
 * 절전 모드: 화면 밝기·갱신을 최소화한 검은 화면에 남은 시간과 마지막 위치 전송 시각만 표시한다.
 * 화면이 꺼져도 위치 공유를 유지하려면 백그라운드 위치 서비스(Android Foreground Service)를 켠다.
 */
class PowerSaveViewModel(
    private val gameId: String,
    private val games: GameRepository,
    private val locations: LocationRepository,
    private val background: BackgroundLocationController,
    private val clock: EpochClock,
) : ViewModel() {
    private val state = MutableStateFlow(PowerSaveUiState(nowEpochMillis = clock.nowMillis(), backgroundSupported = background.isSupported))
    val uiState: StateFlow<PowerSaveUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            val endsAt = (games.getGame(gameId) as? AppResult.Success)?.value?.endsAtEpochMillis
            state.update { it.copy(endsAtEpochMillis = endsAt) }
        }
        viewModelScope.launch {
            while (isActive) {
                state.update {
                    it.copy(
                        nowEpochMillis = clock.nowMillis(),
                        lastSentAtEpochMillis = locations.lastSentAtEpochMillis.value,
                        backgroundRunning = background.isRunning.value,
                    )
                }
                // 절전 화면은 초 단위 갱신이 필요 없으므로 5초마다만 다시 그린다.
                delay(5_000)
            }
        }
    }

    fun toggleBackground() {
        if (background.isRunning.value) background.stop() else background.start()
        state.update { it.copy(backgroundRunning = background.isRunning.value) }
    }
}
