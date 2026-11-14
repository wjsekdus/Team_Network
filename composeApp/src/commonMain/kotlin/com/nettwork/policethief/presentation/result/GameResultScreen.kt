package com.nettwork.policethief.presentation.result

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nettwork.policethief.domain.model.AppResult
import com.nettwork.policethief.domain.model.GameResultSummary
import com.nettwork.policethief.domain.model.PlayerResult
import com.nettwork.policethief.domain.repository.GameRepository
import com.nettwork.policethief.domain.repository.GameSessionRepository
import com.nettwork.policethief.presentation.components.NoticeBanner
import com.nettwork.policethief.shared.model.PlayerRole
import com.nettwork.policethief.platform.PlatformBackHandler
import com.nettwork.policethief.presentation.components.EmptyState
import com.nettwork.policethief.presentation.components.ErrorState
import com.nettwork.policethief.presentation.components.LoadingState
import com.nettwork.policethief.presentation.components.MockBadge
import com.nettwork.policethief.presentation.components.PrimaryActionButton
import com.nettwork.policethief.presentation.components.RoleBadge
import com.nettwork.policethief.presentation.components.ScreenContainer
import com.nettwork.policethief.presentation.components.SectionCard
import com.nettwork.policethief.presentation.components.formatRemaining
import com.nettwork.policethief.presentation.game.label
import com.nettwork.policethief.presentation.theme.AppColors
import com.nettwork.policethief.presentation.theme.LocalDimens
import com.nettwork.policethief.shared.model.GameEndReason
import com.nettwork.policethief.shared.model.GameResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

sealed interface ResultUiState {
    data object Loading : ResultUiState
    data class Failed(val message: String) : ResultUiState

    /** result == null 이면 부분 상태: 결과가 아직 확정되지 않음(다시 조회). */
    data class Ready(val summary: GameResultSummary) : ResultUiState
}

/** S-12 결과. 종료 여부·승패는 단말이 판단하지 않고 서버 결과(GET /result)만 표시한다. */
class GameResultViewModel(
    private val gameId: String,
    private val games: GameRepository,
    private val session: GameSessionRepository,
    val isMock: Boolean,
) : ViewModel() {
    private val state = MutableStateFlow<ResultUiState>(ResultUiState.Loading)
    val uiState: StateFlow<ResultUiState> = state.asStateFlow()

    init {
        load()
    }

    fun load() {
        state.value = ResultUiState.Loading
        viewModelScope.launch {
            state.value = when (val result = games.result(gameId)) {
                is AppResult.Success -> {
                    // 경기가 끝났으면 동기화·위치 전송을 멈춘다(게임 종료 후 위치 공유 중단).
                    if (result.value.result != null) session.stop()
                    ResultUiState.Ready(result.value)
                }
                is AppResult.Failure -> ResultUiState.Failed(result.error.userMessage)
            }
        }
    }
}

/** 플레이어별 이번 경기 활동 요약(누적 점수 아님). */
fun playerActivity(p: PlayerResult): String = buildList {
    if (p.role == PlayerRole.POLICE) add("체포 ${p.capturesMade}회") else {
        add("체포당함 ${p.timesCaptured}회")
        add("구출 ${p.rescuesMade}회")
        add("미션 성공 ${p.missionsCompleted}회")
    }
    if (p.speedWarnings > 0) add("과속 경고 ${p.speedWarnings}회")
}.joinToString(" · ")

fun resultHeadline(result: GameResult?) = when (result) {
    GameResult.POLICE_WIN -> "경찰 승리"
    GameResult.THIEF_WIN -> "도둑 승리"
    GameResult.INVALID -> "중도 종료"
    null -> "결과 확인 중"
}

fun endReasonText(reason: GameEndReason?) = when (reason) {
    GameEndReason.ALL_THIEVES_CAPTURED -> "모든 도둑 체포"
    GameEndReason.TIME_OVER_THIEVES_SURVIVED -> "제한 시간 생존"
    GameEndReason.PLAYER_FORFEITED -> "참가자 이탈로 종료 (무효 경기)"
    GameEndReason.PLAYER_DISCONNECT_TIMEOUT -> "재접속 시간(3분) 초과로 종료 (무효 경기)"
    GameEndReason.PLAYER_DISQUALIFIED_SPEEDING -> "과속 실격으로 종료 (무효 경기)"
    GameEndReason.PLAYER_DISQUALIFIED_ZONE_OUT -> "게임 구역 이탈 실격으로 종료 (무효 경기)"
    null -> "종료 사유 확인 중"
}

@Composable
fun GameResultScreen(gameId: String, onGoHome: () -> Unit) {
    val viewModel: GameResultViewModel = koinViewModel(key = "result-$gameId") { parametersOf(gameId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PlatformBackHandler(enabled = true, onBack = onGoHome)
    ScreenContainer(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(32.dp))
        if (viewModel.isMock) MockBadge()
        when (val s = state) {
            ResultUiState.Loading -> LoadingState("경기 결과를 확인 중입니다.")
            is ResultUiState.Failed -> ErrorState("결과를 불러오지 못했습니다.\n${s.message}", primaryLabel = "다시 조회", onPrimary = viewModel::load)
            is ResultUiState.Ready -> {
                val game = s.summary
                if (game.result == null) NoticeBanner("경기 결과가 아직 확정되지 않았습니다. 잠시 뒤 다시 조회하세요.")
                Text(
                    resultHeadline(game.result),
                    style = MaterialTheme.typography.displayMedium,
                    color = when (game.result) {
                        GameResult.POLICE_WIN -> AppColors.Police
                        GameResult.THIEF_WIN -> AppColors.Thief
                        else -> AppColors.TextPrimary
                    },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(endReasonText(game.endReason), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                val start = game.startedAtEpochMillis
                val end = game.endedAtEpochMillis
                if (start != null && end != null) Text("경기 시간 ${formatRemaining(end - start)}", style = MaterialTheme.typography.bodyLarge)
                SectionCard {
                    Text("이번 경기 주요 활동", style = MaterialTheme.typography.titleMedium)
                    if (game.players.isEmpty()) {
                        EmptyState("세부 기록이 없습니다.")
                    } else {
                        game.players.forEach { p ->
                            androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                                RoleBadge(p.role)
                                Text("${p.nickname} · ${p.finalStatus.label()}", style = MaterialTheme.typography.bodyLarge)
                            }
                            Text(playerActivity(p), style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                        }
                    }
                }
                Text("점수·랭킹은 제공하지 않습니다.", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
            }
        }
        Spacer(Modifier.height(16.dp))
        PrimaryActionButton("홈으로", onGoHome, height = LocalDimens.current.gamePrimaryButton)
    }
}
