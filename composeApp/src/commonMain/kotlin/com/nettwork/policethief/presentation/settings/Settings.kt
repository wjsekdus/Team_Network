package com.nettwork.policethief.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nettwork.policethief.config.AppConfig
import com.nettwork.policethief.domain.model.DevSettings
import com.nettwork.policethief.domain.model.UserPreferences
import com.nettwork.policethief.domain.repository.DevSettingsRepository
import com.nettwork.policethief.domain.repository.ServerAddressProvider
import com.nettwork.policethief.domain.repository.UserPreferencesRepository
import com.nettwork.policethief.domain.model.AppResult
import com.nettwork.policethief.domain.model.map
import com.nettwork.policethief.domain.repository.DevToolsRepository
import com.nettwork.policethief.domain.repository.GameSessionRepository
import com.nettwork.policethief.domain.repository.LocationRepository
import com.nettwork.policethief.domain.model.RealtimeConnectionState
import com.nettwork.policethief.domain.model.RealtimeEvent
import com.nettwork.policethief.domain.model.User
import com.nettwork.policethief.domain.repository.AuthRepository
import com.nettwork.policethief.domain.repository.MockGameController
import com.nettwork.policethief.domain.repository.RealtimeRepository
import com.nettwork.policethief.domain.usecase.CheckServerStatusUseCase
import com.nettwork.policethief.platform.AppPermission
import com.nettwork.policethief.platform.BackgroundLocationController
import com.nettwork.policethief.platform.PermissionController
import com.nettwork.policethief.platform.PermissionState
import com.nettwork.policethief.presentation.components.MockBadge
import com.nettwork.policethief.presentation.components.ScreenTitle
import com.nettwork.policethief.presentation.components.ScreenContainer
import com.nettwork.policethief.presentation.components.SecondaryActionButton
import com.nettwork.policethief.presentation.components.SectionCard
import com.nettwork.policethief.presentation.components.StatusIndicator
import com.nettwork.policethief.presentation.components.formatClockTime
import com.nettwork.policethief.presentation.home.realtimeLabel
import com.nettwork.policethief.presentation.theme.AppColors
import com.nettwork.policethief.shared.model.PlayerRole
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

data class SettingsUiState(
    val user: User? = null,
    val effectiveServerUrl: String = "",
    val healthMessage: String? = null,
    val realtime: RealtimeConnectionState = RealtimeConnectionState.Disconnected,
    val realtimeEvents: List<String> = emptyList(),
    val permissions: Map<AppPermission, PermissionState> = emptyMap(),
    val backgroundSupported: Boolean = false,
    val backgroundRunning: Boolean = false,
    val dev: DevSettings = DevSettings(),
    val isDevBuild: Boolean = false,
    val appVersion: String = "",
    val platformName: String = "",
    val preferences: UserPreferences = UserPreferences(),
    val devMessage: String? = null,
)

sealed interface SettingsEvent {
    data object LoggedOut : SettingsEvent
}

/** S-13 설정: 알림·권한 상태, 서버 연결 확인, WebSocket 테스트, 개발자 테스트 모드, 로그아웃. */
class SettingsViewModel(
    private val auth: AuthRepository,
    private val checkServer: CheckServerStatusUseCase,
    private val realtime: RealtimeRepository,
    private val permissions: PermissionController,
    private val background: BackgroundLocationController,
    private val devSettings: DevSettingsRepository,
    private val urls: ServerAddressProvider,
    private val mockController: MockGameController?,
    config: AppConfig,
    private val preferences: UserPreferencesRepository,
    private val devTools: DevToolsRepository,
    private val locations: LocationRepository,
    private val session: GameSessionRepository,
) : ViewModel() {
    private val state = MutableStateFlow(
        SettingsUiState(
            isDevBuild = config.isDevBuild,
            appVersion = config.appVersion,
            platformName = config.platformName,
            backgroundSupported = background.isSupported,
        ),
    )
    val uiState: StateFlow<SettingsUiState> = state.asStateFlow()

    private val eventChannel = Channel<SettingsEvent>(Channel.BUFFERED)
    val events: Flow<SettingsEvent> = eventChannel.receiveAsFlow()

    init {
        refreshLocalState()
        viewModelScope.launch { preferences.current.collect { prefs -> state.update { it.copy(preferences = prefs) } } }
        viewModelScope.launch { auth.currentUser.collect { user -> state.update { it.copy(user = user) } } }
        viewModelScope.launch { realtime.connectionState.collect { c -> state.update { it.copy(realtime = c) } } }
        viewModelScope.launch { background.isRunning.collect { running -> state.update { it.copy(backgroundRunning = running) } } }
        viewModelScope.launch {
            realtime.events.collect { event ->
                val line = when (event) {
                    is RealtimeEvent.Welcome -> "welcome (userId=${event.userId.take(8)}…)"
                    is RealtimeEvent.TestEvent -> "test_event #${event.sequence}: ${event.message}"
                    is RealtimeEvent.Pong -> "pong ${event.roundTripMillis}ms"
                    is RealtimeEvent.Game -> "game_event ${event.event.type}"
                    is RealtimeEvent.Matchmaking -> "matchmaking ${event.type} (${event.ticket.nearbyWaitingCount}명)"
                    is RealtimeEvent.Location -> "player_location ${event.location.userId?.take(8)}…"
                    is RealtimeEvent.SessionReplaced -> "session_replaced: ${event.message}"
                    is RealtimeEvent.ServerError -> "error: ${event.message}"
                }
                state.update { it.copy(realtimeEvents = (listOf("${formatClockTime(event.receivedAtEpochMillis)} $line") + it.realtimeEvents).take(5)) }
            }
        }
    }

    fun refreshLocalState() = state.update {
        it.copy(
            permissions = AppPermission.entries.associateWith(permissions::status),
            dev = devSettings.current.value,
            effectiveServerUrl = urls.baseUrl(),
        )
    }

    fun checkHealth() {
        state.update { it.copy(healthMessage = "확인 중…") }
        viewModelScope.launch {
            val message = when (val result = checkServer()) {
                is AppResult.Success -> "정상 · 서버 v${result.value.version} · DB ${if (result.value.isDatabaseUp) "정상" else "오류"}"
                is AppResult.Failure -> result.error.userMessage
            }
            state.update { it.copy(healthMessage = message) }
        }
    }

    fun connectRealtime() = realtime.connect()
    fun disconnectRealtime() = realtime.disconnect()

    fun sendTestEvent() {
        viewModelScope.launch {
            val result = realtime.requestTestEvent("설정 화면 테스트")
            if (result is AppResult.Failure) {
                state.update { it.copy(realtimeEvents = (listOf("전송 실패: ${result.error.userMessage}") + it.realtimeEvents).take(5)) }
            }
        }
    }

    fun openAppSettings() = permissions.openAppSettings()

    fun toggleBackground() = if (background.isRunning.value) background.stop() else background.start()

    fun updateDev(transform: (DevSettings) -> DevSettings) {
        devSettings.update(transform)
        mockController?.setMockRole(devSettings.current.value.mockRole)
        refreshLocalState()
    }

    fun updatePreferences(transform: (UserPreferences) -> UserPreferences) = preferences.update(transform)

    // ---- 개발자 도구(개발 서버 PNT_DEV_TOOLS=true 에서만 동작) ----

    /** 내 현재 위치 주변에 봇 3명을 만들어 매칭 대기열에 넣는다. 이후 [매칭 찾기]를 누르면 4명이 모인다. */
    fun createBots() = devAction("봇 생성") {
        val near = (locations.fetchCurrentLocation() as? AppResult.Success)?.value ?: locations.lastLocation.value
        devTools.createBots(3, near).map { "봇 ${it}명을 만들었습니다. 홈에서 [매칭 찾기]를 누르세요." }
    }

    /** 서버 시계를 앞으로 돌린다(준비 30초·구역 축소·30분 종료 확인용). */
    fun advanceClock(millis: Long) = devAction("시간 이동") {
        devTools.advanceClock(millis).map { "서버 시간이 ${it / 1000}초 앞으로 이동한 상태입니다." }.also { session.refresh() }
    }

    /** 현재 경기의 봇 중 하나로 다른 봇 QR 을 스캔(봇 경찰이 봇 도둑 체포 / 봇 도둑이 수감된 봇 도둑 구출). */
    fun botScan() = devAction("봇 스캔") {
        val gameId = session.state.value?.gameId ?: return@devAction AppResult.Success("진행 중인 경기가 없습니다.")
        when (val bots = devTools.gameBots(gameId)) {
            is AppResult.Failure -> bots
            is AppResult.Success -> {
                val police = bots.value.firstOrNull { it.role == PlayerRole.POLICE }
                val target = bots.value.firstOrNull { it.role == PlayerRole.THIEF && it.qrToken != null }
                if (police == null || target == null) {
                    AppResult.Success("스캔할 봇 경찰/도둑 조합이 없습니다.")
                } else {
                    devTools.botScan(police.userId, target.qrToken!!).map { r ->
                        if (r.accepted) "봇 ${police.nickname} → ${target.nickname} 체포" else "거절: ${r.rejectReason}"
                    }.also { session.refresh() }
                }
            }
        }
    }

    private fun devAction(name: String, block: suspend () -> AppResult<String>) {
        state.update { it.copy(devMessage = "$name 중…") }
        viewModelScope.launch {
            val message = when (val result = block()) {
                is AppResult.Success -> result.value
                is AppResult.Failure -> "$name 실패: ${result.error.userMessage} (서버가 PNT_DEV_TOOLS=true 인지 확인)"
            }
            state.update { it.copy(devMessage = message) }
        }
    }

    fun logout() {
        viewModelScope.launch {
            session.stop()
            background.stop()
            realtime.disconnect()
            auth.logout()
            eventChannel.send(SettingsEvent.LoggedOut)
        }
    }
}

private data class LocationPreset(val name: String, val latitude: Double, val longitude: Double)

private val presets = listOf(
    LocationPreset("서울시청", 37.5665, 126.9780),
    LocationPreset("강남역", 37.4979, 127.0276),
    LocationPreset("부산역", 35.1151, 129.0414),
)

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLoggedOut: () -> Unit,
    onOpenPermissions: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.events.collect { onLoggedOut() } }
    ScreenContainer(Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("← 홈") }
            ScreenTitle("설정")
        }
        SectionCard {
            Text("계정", style = MaterialTheme.typography.titleMedium)
            Text(state.user?.let { "${it.nickname} (ID ${it.userId.take(8)}…)" } ?: "로그인 정보 없음")
            Text("게스트 계정은 이 기기에만 저장됩니다.", style = MaterialTheme.typography.bodySmall)
        }
        SectionCard {
            Text("서버 연결", style = MaterialTheme.typography.titleMedium)
            Text(state.effectiveServerUrl, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("serverUrl"))
            SecondaryActionButton("서버 상태 확인 (GET /api/health)", viewModel::checkHealth, Modifier.fillMaxWidth())
            state.healthMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            val (label, color) = realtimeLabel(state.realtime)
            StatusIndicator("WebSocket: $label", color)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryActionButton("연결", viewModel::connectRealtime)
                SecondaryActionButton("해제", viewModel::disconnectRealtime)
                SecondaryActionButton("테스트 이벤트", viewModel::sendTestEvent)
            }
            state.realtimeEvents.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        SectionCard {
            Text("권한·알림", style = MaterialTheme.typography.titleMedium)
            state.permissions.forEach { (permission, status) ->
                val name = when (permission) {
                    AppPermission.LOCATION -> "위치"
                    AppPermission.CAMERA -> "카메라"
                    AppPermission.NOTIFICATION -> "알림"
                }
                StatusIndicator(
                    "$name: ${if (status == PermissionState.GRANTED) "허용" else "허용 안 됨"}",
                    if (status == PermissionState.GRANTED) AppColors.Success else AppColors.Error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryActionButton("다시 확인", viewModel::refreshLocalState)
                SecondaryActionButton("권한 안내", onOpenPermissions)
                SecondaryActionButton("설정 열기", viewModel::openAppSettings)
            }
            if (state.backgroundSupported) {
                ToggleRow("경기 중 백그라운드 위치 공유 자동 시작", state.preferences.backgroundLocation) { checked ->
                    viewModel.updatePreferences { it.copy(backgroundLocation = checked) }
                }
                ToggleRow("백그라운드 위치 공유 (지금 켜기/끄기)", state.backgroundRunning) { viewModel.toggleBackground() }
                Text("화면이 꺼져도 약 15초마다 위치를 서버로 보냅니다. OS 정책상 주기가 보장되지 않습니다. 경기가 끝나면 자동으로 꺼집니다.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("이 플랫폼은 백그라운드 위치 공유를 지원하지 않습니다.", style = MaterialTheme.typography.bodySmall)
            }
        }
        SectionCard {
            Text("경기 알림·절전", style = MaterialTheme.typography.titleMedium)
            ToggleRow("중요 이벤트 시스템 알림", state.preferences.systemNotifications) { checked ->
                viewModel.updatePreferences { it.copy(systemNotifications = checked) }
            }
            ToggleRow("진동", state.preferences.vibration) { checked -> viewModel.updatePreferences { it.copy(vibration = checked) } }
            ToggleRow("경기 시작 시 절전 모드 자동 실행", state.preferences.autoPowerSave) { checked ->
                viewModel.updatePreferences { it.copy(autoPowerSave = checked) }
            }
            Text("알림 권한을 거부해도 앱 안 배너로 알려 드립니다. 절전 모드는 지도 그리기만 줄이고 위치 공유는 유지합니다.", style = MaterialTheme.typography.bodySmall)
        }
        if (state.isDevBuild) DeveloperSection(state.dev, state.devMessage, viewModel)
        SectionCard {
            Text("앱 정보", style = MaterialTheme.typography.titleMedium)
            Text("버전 ${state.appVersion} · ${state.platformName}${if (state.isDevBuild) " · 개발 빌드" else ""}")
            Text("지도 데이터 © OpenFreeMap © OpenMapTiles © OpenStreetMap contributors", style = MaterialTheme.typography.bodySmall)
        }
        SecondaryActionButton("로그아웃", viewModel::logout, Modifier.fillMaxWidth().testTag("logoutButton"))
        Spacer(Modifier.height(24.dp))
    }
}

/** 개발자 테스트 모드. 개발 빌드에서만 보인다. */
@Composable
private fun DeveloperSection(dev: DevSettings, devMessage: String?, viewModel: SettingsViewModel) {
    var serverUrl by remember(dev.serverUrlOverride) { mutableStateOf(dev.serverUrlOverride) }
    var latText by remember(dev.mockLatitude) { mutableStateOf(dev.mockLatitude.toString()) }
    var lonText by remember(dev.mockLongitude) { mutableStateOf(dev.mockLongitude.toString()) }
    SectionCard {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("개발자 테스트 모드", style = MaterialTheme.typography.titleMedium)
            MockBadge("DEV")
        }
        Text("서버 주소 변경 (Cloudflare Tunnel URL 등). 비워 두면 빌드 기본값.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("서버 주소 (https://….trycloudflare.com)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        SecondaryActionButton("서버 주소 저장", { viewModel.updateDev { it.copy(serverUrlOverride = serverUrl) } })

        ToggleRow("테스트 좌표 사용 (GPS 대신)", dev.useMockLocation) { checked -> viewModel.updateDev { it.copy(useMockLocation = checked) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(latText, { latText = it }, label = { Text("위도") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(lonText, { lonText = it }, label = { Text("경도") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        val parsedLat = latText.toDoubleOrNull()
        val parsedLon = lonText.toDoubleOrNull()
        val valid = parsedLat != null && parsedLon != null && parsedLat in -90.0..90.0 && parsedLon in -180.0..180.0
        if (!valid) Text("위도 -90~90, 경도 -180~180 숫자를 입력하세요.", color = AppColors.Error, style = MaterialTheme.typography.bodySmall)
        SecondaryActionButton("좌표 저장", { viewModel.updateDev { it.copy(mockLatitude = parsedLat!!, mockLongitude = parsedLon!!) } }, enabled = valid)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { preset ->
                SecondaryActionButton(preset.name, { viewModel.updateDev { it.copy(mockLatitude = preset.latitude, mockLongitude = preset.longitude) } })
            }
        }
        Text("테스트 좌표는 source=MOCK 으로 전송되어 서버에서 실제 위치와 구분됩니다(서버 PNT_ALLOW_MOCK_LOCATIONS=true 필요).", style = MaterialTheme.typography.bodySmall)

        Text("서버 개발자 도구 (서버 PNT_DEV_TOOLS=true 일 때만 동작)", style = MaterialTheme.typography.bodyMedium)
        Text("휴대폰 1대로 4인 경기를 시험할 때: [봇 3명 만들기] → 홈에서 [매칭 찾기] → 시간 이동으로 준비·종료 확인.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryActionButton("봇 3명 만들기", viewModel::createBots)
            SecondaryActionButton("봇 체포 스캔", viewModel::botScan)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryActionButton("+30초", { viewModel.advanceClock(30_000) })
            SecondaryActionButton("+5분", { viewModel.advanceClock(5 * 60_000) })
            SecondaryActionButton("+30분", { viewModel.advanceClock(30 * 60_000) })
        }
        devMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AppColors.Mock) }

        ToggleRow("서버 없이 Mock 경기로 화면만 확인 (앱 재시작 후 적용)", dev.useMockGameData) { checked -> viewModel.updateDev { it.copy(useMockGameData = checked) } }
        Text("켜면 서버 판정 없이 가상 참가자로 화면 흐름만 봅니다. 실제 경기는 꺼 둔 상태(기본)에서 진행하세요.", style = MaterialTheme.typography.bodySmall)
        Text("Mock 역할 지정", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(null to "무작위", PlayerRole.POLICE to "경찰", PlayerRole.THIEF to "도둑").forEach { (role, label) ->
                SecondaryActionButton(
                    if (dev.mockRole == role) "✓ $label" else label,
                    { viewModel.updateDev { it.copy(mockRole = role) } },
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.semantics { contentDescription = label })
    }
}
