package com.nettwork.policethief.presentation.prepare

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nettwork.policethief.core.EpochClock
import com.nettwork.policethief.domain.model.AppResult
import com.nettwork.policethief.domain.model.Game
import com.nettwork.policethief.domain.model.GamePlayer
import com.nettwork.policethief.domain.model.PlayerLocation
import com.nettwork.policethief.domain.repository.AuthRepository
import com.nettwork.policethief.domain.repository.GameRepository
import com.nettwork.policethief.domain.repository.GameSessionRepository
import com.nettwork.policethief.platform.PlatformBackHandler
import com.nettwork.policethief.platform.map.GameMap
import com.nettwork.policethief.platform.map.GameMapState
import com.nettwork.policethief.platform.map.MapMarker
import com.nettwork.policethief.platform.map.MarkerKind
import com.nettwork.policethief.presentation.components.ConfirmDialog
import com.nettwork.policethief.presentation.components.EmptyState
import com.nettwork.policethief.presentation.components.ErrorState
import com.nettwork.policethief.presentation.components.LoadingState
import com.nettwork.policethief.presentation.components.MockBadge
import com.nettwork.policethief.presentation.components.NoticeBanner
import com.nettwork.policethief.presentation.components.PrimaryActionButton
import com.nettwork.policethief.presentation.components.RoleBadge
import com.nettwork.policethief.presentation.components.ScreenContainer
import com.nettwork.policethief.presentation.components.ScreenTitle
import com.nettwork.policethief.presentation.components.SectionCard
import com.nettwork.policethief.presentation.components.formatRemaining
import com.nettwork.policethief.presentation.theme.AppColors
import com.nettwork.policethief.shared.geo.GeoPoint
import com.nettwork.policethief.shared.model.GameStatus
import com.nettwork.policethief.shared.model.PlayerRole
import com.nettwork.policethief.shared.rules.GameRules
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** S-06 / S-07 공통 상태(보고서 4.2). */
sealed interface PrepareUiState {
    data object Loading : PrepareUiState
    data class Failed(val message: String) : PrepareUiState

    /** 비어 있음: 초기 위치 누락 등으로 구역을 만들 수 없음. */
    data object ZoneUnavailable : PrepareUiState

    data class Ready(
        val game: Game,
        val me: GamePlayer,
        val initialPositions: List<PlayerLocation>,
        val millisToStart: Long,
        /** 부분 상태: 역할은 확정됐지만 위치 정보가 아직 다 오지 않음. */
        val positionsPartial: Boolean,
        val myLocation: PlayerLocation? = null,
    ) : PrepareUiState
}

sealed interface PrepareEvent {
    data class Started(val role: PlayerRole) : PrepareEvent
    data object LeftGame : PrepareEvent
}

/**
 * 역할 공개·구역 확인. 서버가 정한 시작 시각(서버 시계 기준)까지 1초마다 카운트다운하고,
 * 서버 상태가 PLAYING 이 되면(버튼이 아니라 서버 확정 기준) 역할별 지도로 이동한다.
 */
class GameSessionViewModel(
    private val gameId: String,
    private val session: GameSessionRepository,
    private val auth: AuthRepository,
    private val games: GameRepository,
    private val clock: EpochClock,
    val isMock: Boolean,
) : ViewModel() {
    private val state = MutableStateFlow<PrepareUiState>(PrepareUiState.Loading)
    val uiState: StateFlow<PrepareUiState> = state.asStateFlow()

    private val eventChannel = Channel<PrepareEvent>(Channel.BUFFERED)
    val events: Flow<PrepareEvent> = eventChannel.receiveAsFlow()

    private var finished = false

    init {
        session.start(gameId)
        viewModelScope.launch {
            while (isActive && !finished) {
                render()
                delay(1_000)
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            session.refresh()
            render()
        }
    }

    private suspend fun render() {
        val s = session.state.value?.takeIf { it.gameId == gameId }
        val snapshot = s?.snapshot
        if (snapshot == null) {
            state.value = s?.syncError?.let { PrepareUiState.Failed(it) } ?: PrepareUiState.Loading
            return
        }
        val game = snapshot.game
        val me = game.players.firstOrNull { it.userId == auth.currentUser.value?.userId }
        if (me == null) {
            state.value = PrepareUiState.ZoneUnavailable
            return
        }
        when {
            game.isEnded -> finish(PrepareEvent.LeftGame)
            game.status == GameStatus.PLAYING -> finish(PrepareEvent.Started(me.role))
            else -> state.value = PrepareUiState.Ready(
                game = game,
                me = me,
                // 준비 단계에도 서버가 역할별로 걸러 준 위치만 쓴다(도둑에게 경찰 위치를 주지 않음).
                initialPositions = snapshot.locations.filter { it.userId != me.userId },
                millisToStart = ((game.startsAtEpochMillis ?: clock.nowMillis()) - clock.nowMillis()).coerceAtLeast(0),
                positionsPartial = snapshot.locations.isEmpty(),
                myLocation = s.myLocation ?: snapshot.locations.firstOrNull { it.userId == me.userId },
            )
        }
    }

    private suspend fun finish(event: PrepareEvent) {
        if (finished) return
        finished = true
        eventChannel.send(event)
    }

    /** D-02 확인 후: 기권 → 서버가 경기를 무효 처리한 뒤 결과 화면으로. */
    fun leaveGame() {
        viewModelScope.launch {
            when (val result = games.forfeit(gameId)) {
                is AppResult.Success -> {
                    session.refresh()
                    finish(PrepareEvent.LeftGame)
                }
                is AppResult.Failure -> state.value = PrepareUiState.Failed("나가기 요청 실패: ${result.error.userMessage}")
            }
        }
    }
}

@Composable
private fun rememberSessionViewModel(gameId: String): GameSessionViewModel = koinViewModel(key = "session-$gameId") { parametersOf(gameId) }

@Composable
private fun LeaveGameGuard(onLeave: () -> Unit) {
    var show by remember { mutableStateOf(false) }
    PlatformBackHandler(enabled = true) { show = true }
    if (show) {
        ConfirmDialog(
            title = "게임에서 나갈까요?",
            message = "게임에서 나가면 실격 처리되고 경기가 종료됩니다.",
            confirmLabel = "게임 나가기",
            onConfirm = { show = false; onLeave() },
            dismissLabel = "계속하기",
            onDismiss = { show = false },
        )
    }
}

/** S-06 역할 공개: 내 역할, 경찰 2명·도둑 2명, 승리 조건. 별도 수락 없이 진행한다. */
@Composable
fun RoleRevealScreen(gameId: String, onContinue: () -> Unit, onLeftGame: () -> Unit) {
    val viewModel = rememberSessionViewModel(gameId)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.events.collect { if (it is PrepareEvent.LeftGame) onLeftGame() else onContinue() }
    }
    LaunchedEffect(state is PrepareUiState.Ready) {
        if (state is PrepareUiState.Ready) {
            delay(ROLE_REVEAL_DISPLAY_MILLIS)
            onContinue()
        }
    }
    LeaveGameGuard(viewModel::leaveGame)
    ScreenContainer(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(24.dp))
        ScreenTitle("역할 공개")
        if (viewModel.isMock) MockBadge()
        when (val s = state) {
            PrepareUiState.Loading -> LoadingState("게임 정보를 준비 중입니다.")
            is PrepareUiState.Failed -> ErrorState("준비 정보를 확인하지 못했습니다.\n${s.message}", onPrimary = viewModel::load)
            PrepareUiState.ZoneUnavailable -> EmptyState("게임 구역을 만들 수 없습니다.")
            is PrepareUiState.Ready -> {
                val color = if (s.me.role == PlayerRole.POLICE) AppColors.Police else AppColors.Thief
                Text(
                    text = if (s.me.role == PlayerRole.POLICE) "당신은 경찰입니다" else "당신은 도둑입니다",
                    style = MaterialTheme.typography.displayMedium,
                    color = color,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (s.me.role == PlayerRole.POLICE) "승리 조건: 30분 안에 도둑 2명을 모두 체포(QR 스캔)" else "승리 조건: 30분 동안 한 명이라도 생존",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                TeamCard("경찰 ${GameRules.POLICE_COUNT}명", s.game.players.filter { it.role == PlayerRole.POLICE }, PlayerRole.POLICE)
                TeamCard("도둑 ${GameRules.THIEF_COUNT}명", s.game.players.filter { it.role == PlayerRole.THIEF }, PlayerRole.THIEF)
                Text("경기 시작까지 ${formatRemaining(s.millisToStart)}", style = MaterialTheme.typography.headlineMedium)
                PrimaryActionButton("게임 구역 확인", onContinue)
            }
        }
    }
}

@Composable
private fun TeamCard(title: String, players: List<GamePlayer>, role: PlayerRole) {
    SectionCard {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RoleBadge(role)
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        players.forEach { Text("· ${it.nickname}", style = MaterialTheme.typography.bodyLarge) }
    }
}

/** S-07 구역 확인: 4명 최초 위치, 반경 500m, 감옥(중심, 10m). 시작 시각이 되면 역할별 지도로 자동 이동. */
@Composable
fun ZoneConfirmScreen(
    gameId: String,
    onGameStarted: (PlayerRole) -> Unit,
    onBackToMatchmaking: () -> Unit,
    onLeftGame: () -> Unit,
) {
    val viewModel = rememberSessionViewModel(gameId)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is PrepareEvent.Started -> onGameStarted(event.role)
                PrepareEvent.LeftGame -> onLeftGame()
            }
        }
    }
    LeaveGameGuard(viewModel::leaveGame)
    ScreenContainer(Modifier.safeDrawingPadding()) {
        Spacer(Modifier.height(16.dp))
        ScreenTitle("게임 구역 확인")
        if (viewModel.isMock) MockBadge()
        when (val s = state) {
            PrepareUiState.Loading -> LoadingState("게임 정보를 준비 중입니다.")
            is PrepareUiState.Failed -> ErrorState("준비 정보를 확인하지 못했습니다.\n${s.message}", onPrimary = viewModel::load)
            PrepareUiState.ZoneUnavailable -> EmptyState(
                message = "게임 구역을 만들 수 없습니다. 위치를 다시 확인해 주세요.",
                actionLabel = "매칭 대기로",
                onAction = onBackToMatchmaking,
            )
            is PrepareUiState.Ready -> {
                Text(
                    "경기 시작까지 ${formatRemaining(s.millisToStart)} · 반경 ${s.game.currentRadiusMeters.toInt()}m · 감옥 반경 ${s.game.jailRadiusMeters.toInt()}m",
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (s.positionsPartial) NoticeBanner("일부 참가자 위치 확인 중")
                Text(
                    if (s.me.role == PlayerRole.POLICE) "경찰은 모든 참가자의 위치를 볼 수 있습니다." else "도둑은 같은 팀 도둑의 위치만 볼 수 있습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("제한 시간 ${GameRules.GAME_DURATION_MILLIS / 60_000}분 · 감옥은 구역 중심 · 구역을 벗어나면 경고 후 실격", style = MaterialTheme.typography.bodySmall)
                val center = GeoPoint(s.game.centerLatitude, s.game.centerLongitude)
                GameMap(
                    state = GameMapState(
                        center = center,
                        zoneRadiusMeters = s.game.currentRadiusMeters,
                        jail = center,
                        jailRadiusMeters = s.game.jailRadiusMeters,
                        markers = listOfNotNull(s.myLocation?.let { MapMarker(s.me.userId, GeoPoint(it.latitude, it.longitude), "${s.me.nickname}(나)", MarkerKind.ME) }) +
                            s.initialPositions.map { location -> location.toMarker(s.game) },
                    ),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** 위치 → 지도 마커. 닉네임과 역할은 경기 참가자 목록에서 찾는다. */
fun PlayerLocation.toMarker(game: Game): MapMarker {
    val player = userId?.let(game::player)
    return MapMarker(
        id = userId ?: "unknown",
        position = GeoPoint(latitude, longitude),
        label = player?.nickname ?: "참가자",
        kind = if (player?.role == PlayerRole.POLICE) MarkerKind.POLICE else MarkerKind.THIEF,
    )
}

private const val ROLE_REVEAL_DISPLAY_MILLIS = 8_000L
