package com.nettwork.policethief.presentation.qr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nettwork.policethief.core.EpochClock
import com.nettwork.policethief.domain.model.AppError
import com.nettwork.policethief.domain.model.AppResult
import com.nettwork.policethief.domain.model.GamePlayer
import com.nettwork.policethief.domain.model.QrToken
import com.nettwork.policethief.domain.repository.AuthRepository
import com.nettwork.policethief.domain.repository.GameRepository
import com.nettwork.policethief.domain.repository.MockGameController
import com.nettwork.policethief.platform.AppPermission
import com.nettwork.policethief.platform.PermissionController
import com.nettwork.policethief.platform.PermissionState
import com.nettwork.policethief.platform.QrCodeImage
import com.nettwork.policethief.platform.rememberPermissionRequester
import com.nettwork.policethief.presentation.components.ConfirmDialog
import com.nettwork.policethief.presentation.components.EmptyState
import com.nettwork.policethief.presentation.components.ErrorState
import com.nettwork.policethief.presentation.components.LoadingState
import com.nettwork.policethief.presentation.components.MockBadge
import com.nettwork.policethief.presentation.components.NoticeBanner
import com.nettwork.policethief.presentation.components.ScreenContainer
import com.nettwork.policethief.presentation.components.ScreenTitle
import com.nettwork.policethief.presentation.components.SecondaryActionButton
import com.nettwork.policethief.presentation.components.SectionCard
import com.nettwork.policethief.presentation.components.formatClockTime
import com.nettwork.policethief.presentation.game.label
import com.nettwork.policethief.presentation.theme.AppColors
import com.nettwork.policethief.shared.model.PlayerRole
import com.nettwork.policethief.shared.model.PlayerStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

// ---------------- S-10 QR 스캔 ----------------

/** 스캔 결과 상태(보고서 4.4). */
sealed interface ScanPhase {
    data object Ready : ScanPhase
    data object Verifying : ScanPhase
    data class Accepted(val message: String) : ScanPhase
    data class Rejected(val message: String) : ScanPhase

    /** 부분: 인식 후 통신 실패. 서버 판정 대기·미확정. */
    data class Unconfirmed(val message: String, val atEpochMillis: Long) : ScanPhase
}

data class QrScanUiState(
    val me: GamePlayer? = null,
    val loadError: String? = null,
    val cameraPermission: PermissionState = PermissionState.NOT_DETERMINED,
    val phase: ScanPhase = ScanPhase.Ready,
    val showEscortLimitDialog: Boolean = false,
    val lastScannedToken: String? = null,
) {
    val isPolice: Boolean get() = me?.role == PlayerRole.POLICE
    val title: String get() = if (isPolice) "도둑 QR 스캔" else "동료 구출 QR 스캔"
}

/**
 * QR 스캔. 경찰은 체포(capture), 미체포 도둑은 구출(rescue) 요청을 서버에 보낸다.
 * 실제 카메라 인식은 TODO(QR): CameraX + ML Kit(Android), AVFoundation(iOS). 지금은 Mock 스캔 버튼으로 흐름만 확인한다.
 */
class QrScanViewModel(
    private val gameId: String,
    private val games: GameRepository,
    private val auth: AuthRepository,
    private val permissions: PermissionController,
    private val clock: EpochClock,
    val mockController: MockGameController?,
) : ViewModel() {
    private val state = MutableStateFlow(QrScanUiState(cameraPermission = permissions.status(AppPermission.CAMERA)))
    val uiState: StateFlow<QrScanUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            when (val result = games.getGame(gameId)) {
                is AppResult.Success -> state.update { s ->
                    s.copy(me = result.value.players.firstOrNull { it.userId == auth.currentUser.value?.userId } ?: result.value.players.firstOrNull())
                }
                is AppResult.Failure -> state.update { it.copy(loadError = result.error.userMessage) }
            }
        }
    }

    fun mockTokens(): Map<String, String> = mockController?.sampleTokens(gameId).orEmpty()

    fun refreshPermission() = state.update { it.copy(cameraPermission = permissions.status(AppPermission.CAMERA)) }

    fun onScanned(token: String) {
        if (state.value.phase == ScanPhase.Verifying) return // 중복 요청 방지
        state.update { it.copy(phase = ScanPhase.Verifying, lastScannedToken = token) }
        viewModelScope.launch {
            val result = if (state.value.isPolice) games.capture(gameId, token) else games.rescue(gameId, token)
            state.update { s ->
                when (result) {
                    is AppResult.Success -> when {
                        result.value.accepted -> s.copy(
                            phase = ScanPhase.Accepted(if (s.isPolice) "체포 확정! 감옥으로 호송하세요." else "구출 성공! 동료가 다시 자유로워졌습니다."),
                        )
                        result.value.rejectReason == "ESCORT_LIMIT" -> s.copy(phase = ScanPhase.Ready, showEscortLimitDialog = true)
                        else -> s.copy(phase = ScanPhase.Rejected(rejectMessage(result.value.rejectReason)))
                    }
                    is AppResult.Failure -> when (result.error) {
                        is AppError.Network -> s.copy(phase = ScanPhase.Unconfirmed("서버 판정 대기·미확정", clock.nowMillis()))
                        else -> s.copy(phase = ScanPhase.Rejected(result.error.userMessage))
                    }
                }
            }
        }
    }

    /** 통신 실패 후 같은 토큰으로 재검증. 서버는 requestId 로 중복 처리를 막아야 한다(TODO 서버 구현). */
    fun retryLast() {
        state.value.lastScannedToken?.let {
            state.update { s -> s.copy(phase = ScanPhase.Ready) }
            onScanned(it)
        }
    }

    fun resetScan() = state.update { it.copy(phase = ScanPhase.Ready) }

    fun dismissEscortDialog() = state.update { it.copy(showEscortLimitDialog = false) }

    private fun rejectMessage(reason: String?) = when (reason) {
        "OTHER_GAME" -> "다른 경기의 QR 입니다."
        "ALREADY_CAPTURED" -> "이미 체포된 도둑입니다."
        "TARGET_ESCORTED" -> "호송 중인 도둑은 구출할 수 없습니다."
        "TARGET_NOT_JAILED" -> "수감된 동료만 구출할 수 있습니다."
        "NOT_ALLOWED" -> "지금은 이 스캔 권한이 없습니다."
        else -> "유효하지 않은 QR 입니다."
    }
}

@Composable
fun QrScanScreen(gameId: String, onClose: () -> Unit) {
    val viewModel: QrScanViewModel = koinViewModel(key = "scan-$gameId") { parametersOf(gameId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val requestCamera = rememberPermissionRequester(AppPermission.CAMERA) { viewModel.refreshPermission() }
    if (state.showEscortLimitDialog) {
        ConfirmDialog(
            title = "이미 도둑 1명을 호송 중입니다.",
            message = "감옥에 수감한 뒤 다시 체포할 수 있습니다.",
            confirmLabel = "확인",
            onConfirm = viewModel::dismissEscortDialog,
            dismissLabel = null,
            onDismiss = viewModel::dismissEscortDialog,
        )
    }
    ScreenContainer(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(16.dp))
        ScreenTitle(state.title)
        if (viewModel.mockController != null) MockBadge("MOCK 판정 — 실제 서버 검증 아님")
        state.loadError?.let { ErrorState(it, primaryLabel = "닫기", onPrimary = onClose) }

        // 카메라 미리보기 영역
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).background(AppColors.Surface, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            when (state.cameraPermission) {
                PermissionState.GRANTED -> Text(
                    "카메라 QR 인식은 아직 구현되지 않았습니다.\n(TODO: CameraX + ML Kit / AVFoundation)",
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(16.dp),
                )
                else -> EmptyState(message = "QR 스캔에는 카메라 권한이 필요합니다.", actionLabel = "카메라 권한 요청", onAction = requestCamera)
            }
        }

        when (val phase = state.phase) {
            ScanPhase.Ready -> Text("QR 을 화면 중앙에 맞춰 주세요.", style = MaterialTheme.typography.bodyLarge)
            ScanPhase.Verifying -> LoadingState("서버에서 확인 중")
            is ScanPhase.Accepted -> {
                NoticeBanner(phase.message, color = AppColors.Success)
                SecondaryActionButton("지도로 돌아가기", onClose, Modifier.fillMaxWidth())
            }
            is ScanPhase.Rejected -> ErrorState(phase.message, primaryLabel = "다시 스캔", onPrimary = viewModel::resetScan)
            is ScanPhase.Unconfirmed -> {
                NoticeBanner("${phase.message} · 처리 시각 ${formatClockTime(phase.atEpochMillis)}")
                SecondaryActionButton("다시 확인", viewModel::retryLast, Modifier.fillMaxWidth())
            }
        }

        val tokens = viewModel.mockTokens()
        if (tokens.isNotEmpty() && state.phase == ScanPhase.Ready) {
            SectionCard {
                Text("Mock 스캔 시뮬레이션 (개발 모드)", style = MaterialTheme.typography.bodySmall, color = AppColors.Mock)
                tokens.forEach { (label, token) -> SecondaryActionButton("스캔: $label", { viewModel.onScanned(token) }, Modifier.fillMaxWidth()) }
            }
        }
        SecondaryActionButton("닫기", onClose, Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
    }
}

// ---------------- S-11 내 QR ----------------

sealed interface MyQrUiState {
    data object Loading : MyQrUiState
    data class Failed(val message: String) : MyQrUiState
    data class Ready(val token: QrToken, val myStatus: PlayerStatus?) : MyQrUiState
}

class MyQrViewModel(
    private val gameId: String,
    private val games: GameRepository,
    private val auth: AuthRepository,
    val isMock: Boolean,
) : ViewModel() {
    private val state = MutableStateFlow<MyQrUiState>(MyQrUiState.Loading)
    val uiState: StateFlow<MyQrUiState> = state.asStateFlow()

    init {
        load()
    }

    fun load() {
        state.value = MyQrUiState.Loading
        viewModelScope.launch {
            state.value = when (val token = games.myQrToken(gameId)) {
                is AppResult.Success -> {
                    val status = (games.getGame(gameId) as? AppResult.Success)?.value?.players
                        ?.firstOrNull { it.userId == auth.currentUser.value?.userId }?.status
                    MyQrUiState.Ready(token.value, status)
                }
                is AppResult.Failure -> MyQrUiState.Failed(token.error.userMessage)
            }
        }
    }
}

/** 도둑 개인 QR. 수감 중에도 열 수 있다(동료가 구출 스캔). 경기 동안 고정된 토큰이다. */
@Composable
fun MyQrScreen(gameId: String, onClose: () -> Unit) {
    val viewModel: MyQrViewModel = koinViewModel(key = "myqr-$gameId") { parametersOf(gameId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ScreenContainer(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(16.dp))
        ScreenTitle("내 QR")
        if (viewModel.isMock) MockBadge()
        when (val s = state) {
            MyQrUiState.Loading -> LoadingState("QR 을 불러오는 중")
            is MyQrUiState.Failed -> ErrorState("QR 을 받지 못했습니다.\n${s.message}", primaryLabel = "다시 조회", onPrimary = viewModel::load)
            is MyQrUiState.Ready -> {
                s.myStatus?.let { Text("내 상태: ${it.label()}", style = MaterialTheme.typography.titleMedium) }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    QrCodeImage(
                        content = s.token.token,
                        contentDescription = "내 QR 코드. 경찰 또는 동료 도둑에게 보여 주세요.",
                        modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f),
                    )
                }
                Text(
                    "경찰이 스캔하면 체포, 수감 중일 때 동료 도둑이 스캔하면 구출됩니다. 판정은 서버가 합니다.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        SecondaryActionButton("지도로 돌아가기", onClose, Modifier.fillMaxWidth())
    }
}
