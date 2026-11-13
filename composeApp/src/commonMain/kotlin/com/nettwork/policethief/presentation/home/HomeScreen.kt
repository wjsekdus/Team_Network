package com.nettwork.policethief.presentation.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nettwork.policethief.domain.model.LocationFailureReason
import com.nettwork.policethief.domain.model.PlayerLocation
import com.nettwork.policethief.domain.model.RealtimeConnectionState
import com.nettwork.policethief.domain.model.RealtimeEvent
import com.nettwork.policethief.platform.map.GameMap
import com.nettwork.policethief.platform.map.GameMapState
import com.nettwork.policethief.platform.map.MapMarker
import com.nettwork.policethief.platform.map.MarkerKind
import com.nettwork.policethief.presentation.components.ErrorState
import com.nettwork.policethief.presentation.components.LoadingState
import com.nettwork.policethief.presentation.components.MockBadge
import com.nettwork.policethief.presentation.components.NoticeBanner
import com.nettwork.policethief.presentation.components.PrimaryActionButton
import com.nettwork.policethief.presentation.components.ScreenContainer
import com.nettwork.policethief.presentation.components.SecondaryActionButton
import com.nettwork.policethief.presentation.components.SectionCard
import com.nettwork.policethief.presentation.components.StatusIndicator
import com.nettwork.policethief.presentation.components.formatClockTime
import com.nettwork.policethief.presentation.components.formatCoordinate
import com.nettwork.policethief.presentation.theme.AppColors
import com.nettwork.policethief.presentation.theme.LocalDimens
import com.nettwork.policethief.shared.geo.GeoPoint
import com.nettwork.policethief.shared.model.LocationSource
import org.koin.compose.viewmodel.koinViewModel

/** S-04 홈. 상단: 닉네임·설정 / 중간: 서버·위치·지도 / 하단 엄지 영역: [매칭 찾기] 72dp. */
@Composable
fun HomeScreen(
    onStartMatchmaking: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPermissions: () -> Unit,
    viewModel: HomeViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ScreenContainer(Modifier.safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = state.user?.let { "${it.nickname} 님" } ?: "사용자 정보 없음",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f).testTag("homeNickname"),
            )
            TextButton(onClick = onOpenSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("설정") }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (state.isDevBuild) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MockBadge("개발 빌드")
                    if (state.isMockLocation) MockBadge("테스트 좌표 사용 중")
                    if (state.isMockGame) MockBadge("게임 화면 = MOCK")
                }
                Spacer(Modifier.height(8.dp))
            }
            ServerCard(state, onRetry = viewModel::refreshServer, onReconnect = viewModel::reconnectRealtime)
            Spacer(Modifier.height(8.dp))
            LocationCard(
                state = state,
                onRefresh = viewModel::refreshLocation,
                onSend = viewModel::sendLocation,
                onOpenPermissions = onOpenPermissions,
            )
        }
        PrimaryActionButton(
            text = "매칭 찾기",
            onClick = onStartMatchmaking,
            height = LocalDimens.current.homePrimaryButton,
            modifier = Modifier.testTag("startMatchmaking"),
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ServerCard(state: HomeUiState, onRetry: () -> Unit, onReconnect: () -> Unit) {
    SectionCard {
        Text("서버 연결", style = MaterialTheme.typography.titleMedium)
        when (val server = state.server) {
            ServerUiState.Checking -> StatusIndicator("서버 확인 중…", AppColors.TextSecondary)
            is ServerUiState.Online -> {
                StatusIndicator("REST 정상 (v${server.status.version}) · DB ${if (server.status.isDatabaseUp) "정상" else "오류"}", AppColors.Success)
                Text(server.status.baseUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is ServerUiState.Offline -> ErrorState(server.message, primaryLabel = "다시 확인", onPrimary = onRetry)
        }
        val (label, color) = realtimeLabel(state.realtime)
        StatusIndicator("실시간(WebSocket): $label", color, Modifier.testTag("realtimeStatus"))
        (state.lastRealtimeEvent as? RealtimeEvent.TestEvent)?.let {
            Text("최근 테스트 이벤트 #${it.sequence}: ${it.message}", style = MaterialTheme.typography.bodySmall)
        }
        if (state.realtime is RealtimeConnectionState.Failed || state.realtime is RealtimeConnectionState.Disconnected) {
            SecondaryActionButton("실시간 다시 연결", onReconnect)
        }
    }
}

fun realtimeLabel(state: RealtimeConnectionState) = when (state) {
    RealtimeConnectionState.Disconnected -> "연결 안 됨" to AppColors.TextSecondary
    RealtimeConnectionState.Connecting -> "연결 중…" to AppColors.Jail
    is RealtimeConnectionState.Connected -> "연결됨 (${formatClockTime(state.sinceEpochMillis)}부터)" to AppColors.Success
    is RealtimeConnectionState.Reconnecting -> "재연결 대기 ${state.attempt}회차 (${state.delayMillis / 1000}초 후)" to AppColors.Jail
    is RealtimeConnectionState.Failed -> "실패 — ${state.error.userMessage}" to AppColors.Error
}

@Composable
private fun LocationCard(state: HomeUiState, onRefresh: () -> Unit, onSend: () -> Unit, onOpenPermissions: () -> Unit) {
    SectionCard {
        Text("현재 위치", style = MaterialTheme.typography.titleMedium)
        when (val location = state.location) {
            LocationUiState.Idle, LocationUiState.Loading -> LoadingState("현재 위치 확인 중")
            is LocationUiState.Available -> {
                LocationDetails(location.location)
                MiniMap(location.location)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryActionButton("위치 새로고침", onRefresh)
                    SecondaryActionButton(if (state.isSending) "전송 중…" else "서버로 전송", onSend, enabled = !state.isSending)
                }
            }
            is LocationUiState.Failed -> {
                location.lastKnown?.let {
                    NoticeBanner("이전 정보 기준 · 마지막 측정 ${formatClockTime(it.timestampEpochMillis)}")
                    LocationDetails(it)
                }
                ErrorState(
                    message = location.message,
                    primaryLabel = "다시 시도",
                    onPrimary = onRefresh,
                    secondaryLabel = if (location.reason == LocationFailureReason.PERMISSION_DENIED ||
                        location.reason == LocationFailureReason.SERVICE_DISABLED
                    ) "위치 설정 열기" else null,
                    onSecondary = onOpenPermissions,
                )
            }
        }
        state.sendMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        state.lastSentAtEpochMillis?.let {
            Text("마지막 서버 전송 위치 측정 시각: ${formatClockTime(it)}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LocationDetails(location: PlayerLocation) {
    if (location.source == LocationSource.MOCK) MockBadge("테스트 좌표")
    Text(
        "위도 ${formatCoordinate(location.latitude)}, 경도 ${formatCoordinate(location.longitude)}",
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.testTag("currentLocationText"),
    )
    Text(
        "정확도 ±${location.accuracyMeters.toInt()}m · 측정 ${formatClockTime(location.timestampEpochMillis)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun MiniMap(location: PlayerLocation) {
    val me = GeoPoint(location.latitude, location.longitude)
    GameMap(
        state = GameMapState(center = me, markers = listOf(MapMarker("me", me, "나", MarkerKind.ME))),
        modifier = Modifier.fillMaxWidth().height(220.dp).testTag("homeMap"),
    )
}
