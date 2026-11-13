package com.nettwork.policethief.presentation.permission

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nettwork.policethief.platform.AppPermission
import com.nettwork.policethief.platform.PermissionController
import com.nettwork.policethief.platform.PermissionState
import com.nettwork.policethief.platform.rememberPermissionRequester
import com.nettwork.policethief.presentation.components.PrimaryActionButton
import com.nettwork.policethief.presentation.components.ScreenContainer
import com.nettwork.policethief.presentation.components.ScreenTitle
import com.nettwork.policethief.presentation.components.SecondaryActionButton
import com.nettwork.policethief.presentation.components.SectionCard
import com.nettwork.policethief.presentation.components.StatusIndicator
import com.nettwork.policethief.presentation.theme.AppColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.compose.viewmodel.koinViewModel

/** 권한별 상태. 화면 진입·요청 결과·설정 복귀 시 다시 읽는다. */
class PermissionViewModel(private val permissions: PermissionController) : ViewModel() {
    private val state = MutableStateFlow(read())
    val uiState: StateFlow<Map<AppPermission, PermissionState>> = state.asStateFlow()

    fun refresh() {
        state.value = read()
    }

    fun openSettings() = permissions.openAppSettings()

    private fun read() = AppPermission.entries.associateWith(permissions::status)
}

private data class PermissionInfo(val permission: AppPermission, val title: String, val purpose: String, val required: Boolean)

private val permissionInfos = listOf(
    PermissionInfo(AppPermission.LOCATION, "위치", "반경 500m 매칭, 게임 구역·감옥 판정, 15초마다 내 위치 공유에 사용합니다. 경기 밖에서는 다른 사람에게 공개되지 않습니다.", true),
    PermissionInfo(AppPermission.CAMERA, "카메라", "경찰의 도둑 체포, 도둑의 동료 구출 시 QR 코드를 스캔할 때만 사용합니다.", true),
    PermissionInfo(AppPermission.NOTIFICATION, "알림", "화면이 꺼져 있을 때 위치 공유 중임을 알리고, 경기 이벤트를 알려 줍니다.", false),
)

/** S-03 권한 안내. 거부해도 홈은 사용할 수 있고, 매칭·스캔 시 다시 안내한다(보고서 4.7). */
@Composable
fun PermissionScreen(onDone: () -> Unit, viewModel: PermissionViewModel = koinViewModel()) {
    val states by viewModel.uiState.collectAsStateWithLifecycle()
    ScreenContainer(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(24.dp))
        ScreenTitle("권한 안내")
        Text("게임 진행에 필요한 권한과 사용 목적입니다.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        permissionInfos.forEach { info ->
            val status = states[info.permission] ?: PermissionState.NOT_DETERMINED
            val request = rememberPermissionRequester(info.permission) { viewModel.refresh() }
            SectionCard {
                Text("${info.title} ${if (info.required) "(필수)" else "(선택)"}", style = MaterialTheme.typography.titleMedium)
                Text(info.purpose, style = MaterialTheme.typography.bodyMedium)
                StatusIndicator(
                    label = when (status) {
                        PermissionState.GRANTED -> "허용됨"
                        PermissionState.DENIED -> "거부됨 — 설정에서 허용할 수 있습니다"
                        PermissionState.NOT_DETERMINED -> "아직 요청하지 않음"
                    },
                    color = when (status) {
                        PermissionState.GRANTED -> AppColors.Success
                        PermissionState.DENIED -> AppColors.Error
                        PermissionState.NOT_DETERMINED -> AppColors.TextSecondary
                    },
                )
                if (status != PermissionState.GRANTED) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryActionButton("권한 요청", request)
                        if (status == PermissionState.DENIED) SecondaryActionButton("설정 열기", viewModel::openSettings)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        PrimaryActionButton("확인", onClick = onDone)
        SecondaryActionButton("상태 다시 확인", viewModel::refresh)
        Spacer(Modifier.height(24.dp))
    }
}
