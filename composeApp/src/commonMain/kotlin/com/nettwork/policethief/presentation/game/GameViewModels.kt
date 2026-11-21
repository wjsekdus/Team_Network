package com.nettwork.policethief.presentation.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nettwork.policethief.core.EpochClock
import com.nettwork.policethief.core.SystemEpochClock
import com.nettwork.policethief.domain.repository.UserPreferencesRepository
import com.nettwork.policethief.domain.model.AppResult
import com.nettwork.policethief.domain.model.Game
import com.nettwork.policethief.domain.model.GamePlayer
import com.nettwork.policethief.domain.model.PlayerLocation
import com.nettwork.policethief.domain.repository.AuthRepository
import com.nettwork.policethief.domain.repository.GameRepository
import com.nettwork.policethief.domain.repository.GameSessionRepository
import com.nettwork.policethief.domain.repository.GameSessionState
import com.nettwork.policethief.domain.repository.MockGameController
import com.nettwork.policethief.platform.BackgroundLocationController
import com.nettwork.policethief.shared.geo.GeoMath
import com.nettwork.policethief.shared.geo.GeoPoint
import com.nettwork.policethief.shared.model.GameEndReason
import com.nettwork.policethief.shared.model.GameEventType
import com.nettwork.policethief.shared.model.GameResult
import com.nettwork.policethief.shared.model.GameStatus
import com.nettwork.policethief.shared.model.PlayerStatus
import com.nettwork.policethief.shared.rules.GameRules
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 경기 지도 상태(보고서 4.3: 기본·로딩·비어 있음·오류·부분·연결 끊김). */
data class GameMapUiState(
    val isLoading: Boolean = true,
    val loadError: String? = null,
    val game: Game? = null,
    val me: GamePlayer? = null,
    val myLocation: PlayerLocation? = null,
    val myLocationError: String? = null,
    /** 서버가 내 역할에 맞게 걸러 준 다른 참가자 위치(내 위치 제외). */
    val others: List<PlayerLocation> = emptyList(),
    /** 다른 참가자 위치를 마지막으로 받은 시각(단말 시계). */
    val othersReceivedAtEpochMillis: Long? = null,
    /** 부분 상태: 서버 동기화 실패(이전 정보 기준으로 표시). */
    val othersError: String? = null,
    val sendError: String? = null,
    val lastSentAtEpochMillis: Long? = null,
    /** 서버 시계 기준 현재 시각(남은 시간 계산용). */
    val nowEpochMillis: Long = 0,
    /** 단말 시계 기준 현재 시각(수신 지연 계산용). */
    val deviceNowEpochMillis: Long = 0,
) {
    val remainingMillis: Long get() = ((game?.endsAtEpochMillis ?: nowEpochMillis) - nowEpochMillis).coerceAtLeast(0)

    /** 30초 이상 위치를 받지 못하면 지연 경고(보고서 NFR-A02). */
    val isLocationStale: Boolean
        get() = othersReceivedAtEpochMillis?.let { deviceNowEpochMillis - it > GameRules.LOCATION_STALE_WARNING_MILLIS } ?: false

    val escortedThief: GamePlayer?
        get() = me?.let { m -> game?.players?.firstOrNull { it.capturedBy == m.userId && it.status == PlayerStatus.ESCORTED } }

    /** 경찰 승리 판정과 같은 기준(호송 중 + 수감). */
    val capturedCount: Int get() = game?.capturedThiefCount ?: 0

    val isOutOfZone: Boolean get() = me?.outOfZoneSinceEpochMillis != null

    val distanceToJailMeters: Double?
        get() {
            val g = game ?: return null
            val l = myLocation ?: return null
            return GeoMath.distanceMeters(GeoPoint(l.latitude, l.longitude), GeoPoint(g.centerLatitude, g.centerLongitude))
        }

    /** 오래된 좌표를 현재 위치처럼 보이지 않게, 30초 넘은 위치는 지도에서 흐리게 표시할 대상으로 본다. */
    fun isStale(location: PlayerLocation): Boolean = nowEpochMillis - location.timestampEpochMillis > GameRules.LOCATION_STALE_WARNING_MILLIS
}

sealed interface GameMapEvent {
    data object GameEnded : GameMapEvent

    /** 설정에서 "경기 시작 시 절전 모드"를 켠 경우 한 번 보낸다. */
    data object OpenPowerSave : GameMapEvent
}

/**
 * 경찰/도둑 지도 공용 ViewModel.
 * 경기 상태·위치는 [GameSessionRepository](WebSocket + 주기적 재조회)가 서버와 맞춘 값을 그대로 보여 준다.
 * 내 위치 전송도 같은 저장소가 앱 범위에서 하므로, 절전 화면이나 QR 화면으로 옮겨도 끊기지 않는다.
 * 체포·수감·승패는 단말에서 판정하지 않는다.
 */
class GameMapViewModel(
    private val gameId: String,
    private val session: GameSessionRepository,
    private val games: GameRepository,
    private val auth: AuthRepository,
    private val clock: EpochClock,
    private val preferences: UserPreferencesRepository,
    val mockController: MockGameController?,
) : ViewModel() {
    private val state = MutableStateFlow(GameMapUiState(nowEpochMillis = clock.nowMillis(), deviceNowEpochMillis = SystemEpochClock.nowMillis()))
    val uiState: StateFlow<GameMapUiState> = state.asStateFlow()

    private val eventChannel = Channel<GameMapEvent>(Channel.BUFFERED)
    val events: Flow<GameMapEvent> = eventChannel.receiveAsFlow()

    private var endedSent = false
    private var powerSaveOffered = false

    init {
        session.start(gameId)
        viewModelScope.launch {
            session.state.filterNotNull().collect { s -> if (s.gameId == gameId) apply(s) }
        }
        viewModelScope.launch {
            while (isActive) {
                state.update { it.copy(nowEpochMillis = clock.nowMillis(), deviceNowEpochMillis = SystemEpochClock.nowMillis()) }
                delay(1_000)
            }
        }
    }

    private suspend fun apply(s: GameSessionState) {
        val snapshot = s.snapshot
        val myId = auth.currentUser.value?.userId
        state.update {
            it.copy(
                isLoading = snapshot == null && s.syncError == null,
                loadError = if (snapshot == null) s.syncError else null,
                game = snapshot?.game ?: it.game,
                me = snapshot?.game?.players?.firstOrNull { p -> p.userId == myId } ?: it.me,
                others = snapshot?.locations?.filter { l -> l.userId != myId } ?: it.others,
                othersReceivedAtEpochMillis = s.locationsReceivedAtEpochMillis,
                othersError = s.syncError,
                myLocation = s.myLocation ?: it.myLocation,
                myLocationError = s.myLocationError,
                sendError = s.sendError,
                lastSentAtEpochMillis = s.lastSentAtEpochMillis,
            )
        }
        val game = snapshot?.game ?: return
        if (game.isEnded && !endedSent) {
            endedSent = true
            eventChannel.send(GameMapEvent.GameEnded)
        }
        if (game.status == GameStatus.PLAYING && !powerSaveOffered) {
            powerSaveOffered = true
            if (preferences.current.value.autoPowerSave) eventChannel.send(GameMapEvent.OpenPowerSave)
        }
    }

    fun refresh() {
        viewModelScope.launch { session.refresh() }
    }

    /** D-02 확인 후 호출: 명시적 기권. 서버가 경기를 무효(CANCELLED/INVALID) 처리한 뒤 결과 화면으로 간다. */
    fun forfeit() {
        viewModelScope.launch {
            when (val result = games.forfeit(gameId)) {
                is AppResult.Success -> {
                    session.refresh()
                    if (!endedSent) {
                        endedSent = true
                        eventChannel.send(GameMapEvent.GameEnded)
                    }
                }
                // 서버가 기권을 확정하지 못했으면 경기 화면에 남는다(서버 기준 상태 유지).
                is AppResult.Failure -> state.update { it.copy(othersError = "기권 요청 실패: ${result.error.userMessage}") }
            }
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
    /** 경찰에게는 도둑 위치도 공개되므로 호송 대상의 감옥까지 거리도 보여 준다. */
    val escortedDistanceToJailMeters: Double? = null,
    val jailRadiusMeters: Double = GameRules.JAIL_RADIUS_METERS,
    val locationError: String? = null,
    /** 서버가 "감옥 안이지만 GPS 정확도 부족"으로 판정을 보류한 시각(단말 시계). */
    val jailPendingAtEpochMillis: Long? = null,
) {
    val isInsideJail: Boolean get() = distanceToJailMeters?.let { it <= jailRadiusMeters } ?: false
}

/** 호송 중 화면: 담당 도둑과 감옥까지의 거리. 수감 확정은 서버 판정(두 사람 모두 10m 이내, 정확한 최신 위치)을 기다린다. */
class EscortViewModel(
    private val gameId: String,
    private val session: GameSessionRepository,
    private val auth: AuthRepository,
    val mockController: MockGameController?,
) : ViewModel() {
    private val state = MutableStateFlow(EscortUiState())
    val uiState: StateFlow<EscortUiState> = state.asStateFlow()

    init {
        session.start(gameId)
        viewModelScope.launch { session.state.filterNotNull().collect { if (it.gameId == gameId) apply(it) } }
        viewModelScope.launch {
            session.notices.collect { notice ->
                if (notice.type == GameEventType.JAIL_CHECK_PENDING) state.update { it.copy(jailPendingAtEpochMillis = SystemEpochClock.nowMillis()) }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { session.refresh() }
    }

    fun mockJailArrival() {
        mockController?.simulateJailArrival(gameId)
        refresh()
    }

    private fun apply(s: GameSessionState) {
        val snapshot = s.snapshot
        if (snapshot == null) {
            state.update { it.copy(isLoading = s.syncError == null, error = s.syncError) }
            return
        }
        val game = snapshot.game
        val myId = auth.currentUser.value?.userId
        val escorted = game.players.firstOrNull { it.capturedBy == myId && it.status == PlayerStatus.ESCORTED }
        val jail = GeoPoint(game.centerLatitude, game.centerLongitude)
        val thiefLocation = escorted?.let { e -> snapshot.locations.firstOrNull { it.userId == e.userId } }
        state.update {
            it.copy(
                isLoading = false,
                error = null,
                escorted = escorted,
                distanceToJailMeters = s.myLocation?.let { l -> GeoMath.distanceMeters(GeoPoint(l.latitude, l.longitude), jail) },
                escortedDistanceToJailMeters = thiefLocation?.let { l -> GeoMath.distanceMeters(GeoPoint(l.latitude, l.longitude), jail) },
                jailRadiusMeters = game.jailRadiusMeters,
                locationError = s.myLocationError,
                jailPendingAtEpochMillis = if (escorted == null) null else it.jailPendingAtEpochMillis,
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
    val capturedCount: Int = 0,
    val nextShrinkAtEpochMillis: Long? = null,
    val myStatus: PlayerStatus? = null,
    /** 서버와 마지막으로 동기화한 시각(단말 시계). */
    val syncedAtEpochMillis: Long? = null,
    val syncError: String? = null,
    val gameEnded: Boolean = false,
)

/**
 * 절전 모드(지시서 21장): 지도를 그리지 않는 검은 화면에 남은 시간·체포 현황·다음 축소·연결 상태만 5초마다 갱신한다.
 * 위치 공유와 이벤트 수신은 앱 범위 [GameSessionRepository] 가 계속하므로 절전 중에도 유지된다.
 * 화면 밝기나 OS 전원 관리를 강제로 바꾸지는 않는다.
 */
class PowerSaveViewModel(
    private val gameId: String,
    private val session: GameSessionRepository,
    private val auth: AuthRepository,
    private val background: BackgroundLocationController,
    private val clock: EpochClock,
) : ViewModel() {
    private val state = MutableStateFlow(PowerSaveUiState(nowEpochMillis = clock.nowMillis(), backgroundSupported = background.isSupported))
    val uiState: StateFlow<PowerSaveUiState> = state.asStateFlow()

    init {
        session.start(gameId)
        viewModelScope.launch {
            while (isActive) {
                val s = session.state.value?.takeIf { it.gameId == gameId }
                val game = s?.snapshot?.game
                state.update {
                    it.copy(
                        endsAtEpochMillis = game?.endsAtEpochMillis ?: it.endsAtEpochMillis,
                        nowEpochMillis = clock.nowMillis(),
                        lastSentAtEpochMillis = s?.lastSentAtEpochMillis,
                        backgroundRunning = background.isRunning.value,
                        capturedCount = game?.capturedThiefCount ?: it.capturedCount,
                        nextShrinkAtEpochMillis = game?.nextZoneShrink?.scheduledAtEpochMillis,
                        myStatus = game?.players?.firstOrNull { p -> p.userId == auth.currentUser.value?.userId }?.status,
                        syncedAtEpochMillis = s?.syncedAtEpochMillis,
                        syncError = s?.syncError,
                        gameEnded = game?.isEnded == true,
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
