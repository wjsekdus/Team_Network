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
import com.nettwork.policethief.data.local.DevSettings
import com.nettwork.policethief.data.local.DevSettingsStore
import com.nettwork.policethief.data.local.ServerUrlProvider
import com.nettwork.policethief.domain.model.AppResult
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
    private val devSettings: DevSettingsStore,
    private val urls: ServerUrlProvider,
    private val mockController: MockGameController?,
    config: AppConfig,
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

    fun logout() {
        viewModelScope.launch {
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
                ToggleRow("백그라운드 위치 공유(테스트)", state.backgroundRunning) { viewModel.toggleBackground() }
                Text("화면이 꺼져도 약 15초마다 위치를 서버로 보냅니다. OS 정책상 주기가 보장되지 않습니다.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("이 플랫폼은 백그라운드 위치 공유가 아직 구현되지 않았습니다.", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (state.isDevBuild) DeveloperSection(state.dev, viewModel)
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
private fun DeveloperSection(dev: DevSettings, viewModel: SettingsViewModel) {
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

        ToggleRow("게임 화면 Mock 데이터 사용 (앱 재시작 후 적용)", dev.useMockGameData) { checked -> viewModel.updateDev { it.copy(useMockGameData = checked) } }
        Text("끄면 실제 서버 API 를 호출합니다. 현재 서버 게임 API 는 미구현(501)입니다.", style = MaterialTheme.typography.bodySmall)
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
