package com.nettwork.policethief.presentation.matchmaking

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nettwork.policethief.core.EpochClock
import com.nettwork.policethief.domain.model.AppResult
import com.nettwork.policethief.domain.model.LocationFailureReason
import com.nettwork.policethief.domain.repository.LocationRepository
import com.nettwork.policethief.domain.repository.MatchmakingRepository
import com.nettwork.policethief.domain.usecase.locationReason
import com.nettwork.policethief.platform.PlatformBackHandler
import com.nettwork.policethief.presentation.components.ConfirmDialog
import com.nettwork.policethief.presentation.components.EmptyState
import com.nettwork.policethief.presentation.components.ErrorState
import com.nettwork.policethief.presentation.components.LoadingState
import com.nettwork.policethief.presentation.components.MockBadge
import com.nettwork.policethief.presentation.components.NoticeBanner
import com.nettwork.policethief.presentation.components.ScreenContainer
import com.nettwork.policethief.presentation.components.ScreenTitle
import com.nettwork.policethief.presentation.components.SecondaryActionButton
import com.nettwork.policethief.presentation.components.formatRemaining
import com.nettwork.policethief.shared.model.MatchmakingStatus
import com.nettwork.policethief.shared.rules.GameRules
import kotlinx.coroutines.Job
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

/** S-05 상태(보고서 4.1: 기본·로딩·비어 있음·오류·부분). */
sealed interface MatchmakingUiState {
    /** 로딩: 위치 조회·대기열 등록 중. */
    data object Locating : MatchmakingUiState

    /** 기본: 4명 미만 대기. dropped = true 면 부분 상태("참가자가 나가 n/4명"). */
    data class Waiting(val count: Int, val elapsedMillis: Long, val dropped: Boolean) : MatchmakingUiState

    /** 비어 있음: 10분 내 참가자 확보 실패. */
    data object TimedOut : MatchmakingUiState

    data class Failed(val message: String, val needsLocationSettings: Boolean) : MatchmakingUiState
}

sealed interface MatchmakingEvent {
    data class Matched(val gameId: String) : MatchmakingEvent
    data object Exit : MatchmakingEvent
}

class MatchmakingViewModel(
    private val matchmaking: MatchmakingRepository,
    private val locations: LocationRepository,
    private val clock: EpochClock,
    val isMock: Boolean,
) : ViewModel() {
    private val state = MutableStateFlow<MatchmakingUiState>(MatchmakingUiState.Locating)
    val uiState: StateFlow<MatchmakingUiState> = state.asStateFlow()

    private val eventChannel = Channel<MatchmakingEvent>(Channel.BUFFERED)
    val events: Flow<MatchmakingEvent> = eventChannel.receiveAsFlow()

    private var watchJob: Job? = null

    init {
        start()
    }

    fun start() {
        watchJob?.cancel()
        state.value = MatchmakingUiState.Locating
        watchJob = viewModelScope.launch {
            val location = when (val result = locations.fetchCurrentLocation()) {
                is AppResult.Success -> result.value
                is AppResult.Failure -> {
                    val reason = result.error.locationReason()
                    state.value = MatchmakingUiState.Failed(
                        result.error.userMessage,
                        reason == LocationFailureReason.PERMISSION_DENIED || reason == LocationFailureReason.SERVICE_DISABLED,
                    )
                    return@launch
                }
            }
            val joined = when (val result = matchmaking.join(location)) {
                is AppResult.Success -> result.value
                is AppResult.Failure -> {
                    state.value = MatchmakingUiState.Failed(result.error.userMessage, needsLocationSettings = false)
                    return@launch
                }
            }
            var maxCount = joined.nearbyWaitingCount
            while (isActive) {
                val ticket = matchmaking.ticket.value ?: joined
                val now = clock.nowMillis()
                when {
                    ticket.status == MatchmakingStatus.MATCHED && ticket.gameId != null -> {
                        eventChannel.send(MatchmakingEvent.Matched(ticket.gameId))
                        return@launch
                    }
                    ticket.status == MatchmakingStatus.EXPIRED || now >= ticket.expiresAtEpochMillis -> {
                        matchmaking.cancel()
                        state.value = MatchmakingUiState.TimedOut
                        return@launch
                    }
                    else -> {
                        val dropped = ticket.nearbyWaitingCount < maxCount
                        maxCount = maxOf(maxCount, ticket.nearbyWaitingCount)
                        state.value = MatchmakingUiState.Waiting(ticket.nearbyWaitingCount, now - ticket.joinedAtEpochMillis, dropped)
                    }
                }
                delay(500)
            }
        }
    }

    /** D-01 확인 후 호출. 서버 취소가 확정된 뒤 홈으로 돌아간다. */
    fun cancel() {
        watchJob?.cancel()
        viewModelScope.launch {
            matchmaking.cancel()
            eventChannel.send(MatchmakingEvent.Exit)
        }
    }
}

@Composable
fun MatchmakingScreen(
    onMatched: (gameId: String) -> Unit,
    onExit: () -> Unit,
    onOpenPermissions: () -> Unit,
    viewModel: MatchmakingViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showCancelDialog by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is MatchmakingEvent.Matched -> onMatched(event.gameId)
                MatchmakingEvent.Exit -> onExit()
            }
        }
    }
    val waiting = state is MatchmakingUiState.Waiting || state is MatchmakingUiState.Locating
    PlatformBackHandler(enabled = waiting) { showCancelDialog = true }
    if (showCancelDialog) {
        ConfirmDialog(
            title = "매칭을 취소할까요?",
            message = null,
            confirmLabel = "매칭 취소",
            onConfirm = { showCancelDialog = false; viewModel.cancel() },
            dismissLabel = "계속 기다리기",
            onDismiss = { showCancelDialog = false },
        )
    }

    ScreenContainer(Modifier.safeDrawingPadding()) {
        Spacer(Modifier.height(24.dp))
        ScreenTitle("매칭 대기")
        if (viewModel.isMock) MockBadge("MOCK 매칭 — 가상 참가자 3명이 자동 입장합니다")
        Text("반경 ${GameRules.MATCH_RADIUS_METERS.toInt()}m 안의 참가자를 찾고 있어요.", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        when (val s = state) {
            MatchmakingUiState.Locating -> LoadingState("현재 위치 확인 중")
            is MatchmakingUiState.Waiting -> {
                if (s.dropped) NoticeBanner("참가자가 나가 ${s.count}/${GameRules.PLAYERS_PER_GAME}명이에요. 남은 시간 동안 다시 찾습니다.")
                Text("매칭 중 · ${s.count}/${GameRules.PLAYERS_PER_GAME}명", style = MaterialTheme.typography.headlineMedium)
                LinearProgressIndicator(
                    progress = { s.count / GameRules.PLAYERS_PER_GAME.toFloat() },
                    modifier = Modifier.fillMaxWidth().height(10.dp).semantics {
                        contentDescription = "${GameRules.PLAYERS_PER_GAME}명 중 ${s.count}명 모임"
                    },
                )
                Text(
                    "경과 ${formatRemaining(s.elapsedMillis)} / 최대 ${GameRules.MATCH_TIMEOUT_MILLIS / 60_000}분",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(24.dp))
                SecondaryActionButton("매칭 취소", { showCancelDialog = true }, Modifier.fillMaxWidth())
            }
            MatchmakingUiState.TimedOut -> {
                EmptyState(message = "10분 내 참가자를 찾지 못했어요.", actionLabel = "다시 찾기", onAction = viewModel::start)
                SecondaryActionButton("홈으로", onExit, Modifier.fillMaxWidth())
            }
            is MatchmakingUiState.Failed -> ErrorState(
                message = s.message,
                primaryLabel = "다시 시도",
                onPrimary = viewModel::start,
                secondaryLabel = if (s.needsLocationSettings) "위치 설정 열기" else "홈으로",
                onSecondary = if (s.needsLocationSettings) onOpenPermissions else onExit,
            )
        }
    }
}
