package com.nettwork.policethief.presentation.game

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nettwork.policethief.domain.model.GamePlayer
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
import com.nettwork.policethief.presentation.components.SecondaryActionButton
import com.nettwork.policethief.presentation.components.SectionCard
import com.nettwork.policethief.presentation.components.formatClockTime
import com.nettwork.policethief.presentation.components.formatMeters
import com.nettwork.policethief.presentation.components.formatRemaining
import com.nettwork.policethief.presentation.prepare.toMarker
import com.nettwork.policethief.presentation.theme.AppColors
import com.nettwork.policethief.presentation.theme.LocalDimens
import com.nettwork.policethief.shared.geo.GeoPoint
import com.nettwork.policethief.shared.model.GameEndReason
import com.nettwork.policethief.shared.model.GameResult
import com.nettwork.policethief.shared.model.PlayerRole
import com.nettwork.policethief.shared.model.PlayerStatus
import com.nettwork.policethief.shared.rules.GameRules
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * S-08 경찰 지도 / S-09 도둑 지도. 같은 지도 구조를 쓰되 보이는 참가자와 하단 조작이 역할마다 다르다(보고서 3.2).
 * 실제 역할은 서버(현재 Mock) 응답의 내 역할을 따른다. [expectedRole] 은 로딩 중 제목 표시에만 쓴다.
 */
@Composable
fun GameMapScreen(
    gameId: String,
    expectedRole: PlayerRole,
    onOpenQrScan: () -> Unit,
    onOpenMyQr: () -> Unit,
    onOpenEscort: () -> Unit,
    onOpenPowerSave: () -> Unit,
    onGameEnded: () -> Unit,
) {
    val viewModel: GameMapViewModel = koinViewModel(key = "map-$gameId") { parametersOf(gameId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showLeaveDialog by remember { mutableStateOf(false) }
    var showMockPanel by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                GameMapEvent.GameEnded -> onGameEnded()
                GameMapEvent.OpenPowerSave -> onOpenPowerSave()
            }
        }
    }
    PlatformBackHandler(enabled = true) { showLeaveDialog = true }
    if (showLeaveDialog) {
        ConfirmDialog(
            title = "게임에서 나갈까요?",
            message = "게임에서 나가면 실격 처리되고 경기가 종료됩니다.",
            confirmLabel = "게임 나가기",
            onConfirm = { showLeaveDialog = false; viewModel.forfeit() },
            dismissLabel = "계속하기",
            onDismiss = { showLeaveDialog = false },
        )
    }

    val role = state.me?.role ?: expectedRole
    ScreenContainer(Modifier.safeDrawingPadding()) {
        // 상단: 남은 시간이 가장 먼저 읽히도록(시각적 위계). 설정성 버튼은 상단에 둔다.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RoleBadge(role)
            Spacer(Modifier.padding(4.dp))
            Text(
                formatRemaining(state.remainingMillis),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f).semantics { contentDescription = "남은 시간 ${formatRemaining(state.remainingMillis)}" },
            )
            TextButton(onClick = onOpenPowerSave, modifier = Modifier.heightIn(min = 48.dp)) { Text("절전") }
            TextButton(onClick = { showLeaveDialog = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("나가기", color = AppColors.Error) }
        }
        if (viewModel.mockController != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                MockBadge("MOCK 경기 — 다른 참가자는 가상 인물")
                TextButton(onClick = { showMockPanel = !showMockPanel }) { Text(if (showMockPanel) "Mock 조작 닫기" else "Mock 조작") }
            }
        }
        when {
            state.isLoading -> LoadingState("경기 정보를 불러오는 중")
            state.loadError != null && state.game == null -> ErrorState(state.loadError!!, onPrimary = viewModel::refresh)
            else -> {
                StatusLine(state, role)
                if (state.isLocationStale || state.othersError != null) {
                    NoticeBanner(
                        "오프라인 · 이전 정보 기준" +
                            (state.othersReceivedAtEpochMillis?.let { " · 마지막 위치 확인: ${formatClockTime(it)}" } ?: ""),
                    )
                }
                state.myLocationError?.let { NoticeBanner("내 위치 오류: $it", color = AppColors.Error) }
                if (showMockPanel) MockPanel(viewModel, role)
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    GameMap(state.toMapState(), Modifier.fillMaxSize().testTag("gameMap"))
                    if (state.others.isEmpty() && state.othersError == null) {
                        Text(
                            "아직 표시할 위치 정보가 없습니다.",
                            modifier = Modifier.align(Alignment.Center).background(AppColors.Background.copy(alpha = 0.8f)).padding(8.dp),
                        )
                    }
                }
                BottomActions(state, role, onOpenQrScan, onOpenMyQr, onOpenEscort)
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun StatusLine(state: GameMapUiState, role: PlayerRole) {
    val game = state.game ?: return
    val thieves = game.players.filter { it.role == PlayerRole.THIEF }
    if (game.status == com.nettwork.policethief.shared.model.GameStatus.READY) {
        NoticeBanner("준비 중 · 경기 시작까지 ${formatRemaining((game.startsAtEpochMillis ?: state.nowEpochMillis) - state.nowEpochMillis)}")
    }
    val text = if (role == PlayerRole.POLICE) {
        "체포 ${state.capturedCount}/${GameRules.THIEF_COUNT} · 경고 ${state.me?.warningCount ?: 0}/${GameRules.SPEED_WARNINGS_TO_DISQUALIFY}"
    } else {
        "도둑 " + thieves.joinToString(" · ") { "${it.nickname} ${it.status.label()}" } +
            " · 경고 ${state.me?.warningCount ?: 0}/${GameRules.SPEED_WARNINGS_TO_DISQUALIFY}"
    }
    Text(text, style = MaterialTheme.typography.bodyLarge)
    val zone = buildString {
        append("구역 ${game.currentRadiusMeters.toInt()}m")
        game.nextZoneShrink?.let { append(" · ${formatRemaining(it.scheduledAtEpochMillis - state.nowEpochMillis)} 뒤 ${it.radiusMeters.toInt()}m 로 축소") }
    }
    Text(zone, style = MaterialTheme.typography.bodyMedium)
    if (state.isOutOfZone) {
        NoticeBanner("⚠ 게임 구역 밖입니다! 바로 구역 안으로 돌아오세요. 계속 벗어나면 실격(무효 경기)됩니다.", color = AppColors.Error)
    }
    game.activeMission?.let { mission ->
        NoticeBanner(
            "미션: 초록 원 안으로 이동 · 남은 시간 ${formatRemaining(mission.deadlineAtEpochMillis - state.nowEpochMillis)}",
            color = AppColors.Success,
        )
    }
    state.lastSentAtEpochMillis?.let {
        Text("내 위치 전송: ${formatClockTime(it)}" + (state.sendError?.let { e -> " (최근 전송 실패: $e)" } ?: ""), style = MaterialTheme.typography.bodySmall)
    } ?: state.sendError?.let { Text("위치 전송 실패: $it", style = MaterialTheme.typography.bodySmall, color = AppColors.Error) }
}

fun PlayerStatus.label() = when (this) {
    PlayerStatus.ACTIVE -> "생존"
    PlayerStatus.ESCORTED -> "호송 중"
    PlayerStatus.JAILED -> "수감"
    PlayerStatus.ELIMINATED -> "실격"
}

private fun GameMapUiState.toMapState(): GameMapState {
    val game = game ?: return GameMapState(center = null)
    val center = GeoPoint(game.centerLatitude, game.centerLongitude)
    val meMarker = myLocation?.let { MapMarker("me", GeoPoint(it.latitude, it.longitude), "나", MarkerKind.ME) }
    val mission = game.activeMission
    return GameMapState(
        center = center,
        zoneRadiusMeters = game.currentRadiusMeters,
        jail = center,
        jailRadiusMeters = game.jailRadiusMeters,
        // 오래된(30초 초과) 좌표는 "현재 위치"로 오해하지 않도록 라벨에 마지막 수신 시각을 붙인다.
        markers = listOfNotNull(meMarker) + others.map { location ->
            val marker = location.toMarker(game)
            if (isStale(location)) marker.copy(label = "${marker.label} (${formatClockTime(location.timestampEpochMillis)} 기준)") else marker
        },
        initialZoneRadiusMeters = game.initialRadiusMeters,
        mission = mission?.let { GeoPoint(it.targetLatitude, it.targetLongitude) },
        missionRadiusMeters = mission?.targetRadiusMeters,
    )
}

@Composable
private fun BottomActions(
    state: GameMapUiState,
    role: PlayerRole,
    onOpenQrScan: () -> Unit,
    onOpenMyQr: () -> Unit,
    onOpenEscort: () -> Unit,
) {
    val gameButton = LocalDimens.current.gamePrimaryButton
    if (role == PlayerRole.POLICE) {
        val escorted = state.escortedThief
        if (escorted != null) {
            NoticeBanner("호송 중: ${escorted.nickname} — 감옥까지 함께 이동하세요")
            SecondaryActionButton("호송 상태 보기", onOpenEscort, Modifier.fillMaxWidth())
        }
        PrimaryActionButton("QR 스캔해서 체포", onOpenQrScan, height = gameButton, modifier = Modifier.testTag("captureButton"))
    } else {
        when (state.me?.status) {
            PlayerStatus.ESCORTED -> NoticeBanner("체포됨 · 경찰과 함께 감옥으로 이동 중입니다. 호송 중에는 구출될 수 없습니다.")
            PlayerStatus.JAILED -> NoticeBanner("수감 중 · 동료 도둑이 내 QR 을 스캔하면 구출됩니다. [내 QR 보기]를 눌러 보여 주세요.")
            else -> Unit
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryActionButton(
                "내 QR 보기",
                onOpenMyQr,
                height = gameButton,
                containerColor = AppColors.Thief,
                contentColor = AppColors.OnThief,
                modifier = Modifier.weight(1f),
            )
            if (state.me?.status == PlayerStatus.ACTIVE) {
                PrimaryActionButton(
                    "동료 구출 스캔",
                    onOpenQrScan,
                    height = gameButton,
                    containerColor = AppColors.SurfaceHigh,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun MockPanel(viewModel: GameMapViewModel, role: PlayerRole) {
    SectionCard {
        Text("Mock 조작 (개발 모드 전용 · 실제 서버 판정 아님)", style = MaterialTheme.typography.bodySmall, color = AppColors.Mock)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryActionButton(if (role == PlayerRole.POLICE) "감옥 도착" else "동료 수감됨", viewModel::mockJailArrival)
            SecondaryActionButton("도둑 승리", { viewModel.mockFinish(GameResult.THIEF_WIN, GameEndReason.TIME_OVER_THIEVES_SURVIVED) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryActionButton("경찰 승리", { viewModel.mockFinish(GameResult.POLICE_WIN, GameEndReason.ALL_THIEVES_CAPTURED) })
            SecondaryActionButton("과속 실격", { viewModel.mockFinish(GameResult.INVALID, GameEndReason.PLAYER_DISQUALIFIED_SPEEDING) })
        }
    }
}

/** 호송 중: 담당 도둑, 감옥까지 거리, 10m 이내 도착 안내. 수감 확정은 서버만 한다(보고서 4.5). */
@Composable
fun EscortScreen(gameId: String, onClose: () -> Unit) {
    val viewModel: EscortViewModel = koinViewModel(key = "escort-$gameId") { parametersOf(gameId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ScreenContainer(Modifier.safeDrawingPadding()) {
        Spacer(Modifier.height(16.dp))
        ScreenTitle("호송 중")
        if (viewModel.mockController != null) MockBadge()
        when {
            state.isLoading -> LoadingState("서버 호송 상태 확인 중")
            state.error != null -> ErrorState(state.error!!, onPrimary = viewModel::refresh)
            state.escorted == null -> EmptyState(
                message = "호송 중인 도둑이 없거나 수감이 완료되었습니다.\n지도로 돌아가 체포를 계속하세요.",
            )
            else -> EscortDetails(state.escorted!!, state, viewModel)
        }
        Spacer(Modifier.weight(1f))
        SecondaryActionButton("지도로 돌아가기", onClose, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun EscortDetails(escorted: GamePlayer, state: EscortUiState, viewModel: EscortViewModel) {
    SectionCard {
        Text("호송 대상: ${escorted.nickname}", style = MaterialTheme.typography.titleMedium, color = AppColors.Thief)
        Text(
            state.distanceToJailMeters?.let { "감옥까지 ${formatMeters(it)} (반경 ${state.jailRadiusMeters.toInt()}m 안으로)" } ?: "내 위치를 확인하는 중…",
            style = MaterialTheme.typography.headlineMedium,
        )
        state.escortedDistanceToJailMeters?.let {
            Text("${escorted.nickname} → 감옥 ${formatMeters(it)}", style = MaterialTheme.typography.bodyLarge, color = AppColors.Thief)
        }
        Text("경찰과 도둑 두 사람 모두 감옥 반경 안에 들어오면 서버가 수감을 확정합니다.", style = MaterialTheme.typography.bodyMedium)
        if (state.isInsideJail) NoticeBanner("감옥 도착 확인 중 — 서버 승인 전까지 수감 완료가 아닙니다")
        if (state.jailPendingAtEpochMillis != null) {
            NoticeBanner("GPS 정확도가 낮아 수감 판정을 보류했습니다. 하늘이 보이는 곳에서 잠시 멈춰 GPS 신호를 다시 잡아 주세요.", color = AppColors.Error)
        }
        state.locationError?.let { NoticeBanner("위치 확인 실패: $it", color = AppColors.Error) }
        if (viewModel.mockController != null) SecondaryActionButton("Mock: 서버 수감 확정", viewModel::mockJailArrival)
    }
}

/** 절전 모드: 최소 정보만 표시. 화면 아무 곳이나 누르면 지도로 돌아간다. */
@Composable
fun PowerSaveScreen(gameId: String, onClose: () -> Unit) {
    val viewModel: PowerSaveViewModel = koinViewModel(key = "power-$gameId") { parametersOf(gameId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black).clickable(onClickLabel = "지도로 돌아가기", onClick = onClose)
            .safeDrawingPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text("절전 모드", style = MaterialTheme.typography.titleMedium, color = AppColors.TextSecondary)
        Text(
            state.endsAtEpochMillis?.let { formatRemaining(it - state.nowEpochMillis) } ?: "--:--",
            style = MaterialTheme.typography.displayMedium,
            color = AppColors.TextSecondary,
        )
        Text("체포 ${state.capturedCount}/${GameRules.THIEF_COUNT}" + (state.myStatus?.let { " · 내 상태 ${it.label()}" } ?: ""), color = AppColors.TextSecondary)
        state.nextShrinkAtEpochMillis?.let {
            Text("다음 구역 축소까지 ${formatRemaining(it - state.nowEpochMillis)}", color = AppColors.TextSecondary)
        }
        Text(
            when {
                state.gameEnded -> "경기가 끝났습니다 · 화면을 눌러 결과를 확인하세요"
                state.syncError != null -> "서버 연결 불안정 · ${state.syncError.orEmpty().lineSequence().first()}"
                else -> "서버 연결 정상" + (state.syncedAtEpochMillis?.let { " · ${formatClockTime(it)} 동기화" } ?: "")
            },
            color = if (state.syncError != null) AppColors.Error else AppColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
        Text(
            state.lastSentAtEpochMillis?.let { "마지막 위치 전송 ${formatClockTime(it)}" } ?: "아직 위치를 전송하지 않았습니다",
            color = AppColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
        if (state.backgroundSupported) {
            SecondaryActionButton(
                if (state.backgroundRunning) "백그라운드 위치 공유 끄기" else "화면이 꺼져도 위치 공유 켜기",
                viewModel::toggleBackground,
            )
            Text("OS 정책에 따라 15초 주기가 지켜지지 않을 수 있습니다.", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
        } else {
            Text("이 플랫폼은 백그라운드 위치 공유를 지원하지 않습니다.", style = MaterialTheme.typography.bodySmall, color = AppColors.Error)
        }
        Text("절전 모드는 지도 그리기와 화면 갱신만 줄입니다. 위치 공유와 경기 알림은 계속됩니다.", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary, textAlign = TextAlign.Center)
        Text("화면을 누르면 지도로 돌아갑니다", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
    }
}
