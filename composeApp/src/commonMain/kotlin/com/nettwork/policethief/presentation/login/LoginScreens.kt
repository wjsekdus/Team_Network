package com.nettwork.policethief.presentation.login

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nettwork.policethief.presentation.components.ErrorState
import com.nettwork.policethief.presentation.components.LoadingState
import com.nettwork.policethief.presentation.components.PrimaryActionButton
import com.nettwork.policethief.presentation.components.ScreenContainer
import com.nettwork.policethief.presentation.components.ScreenTitle
import com.nettwork.policethief.presentation.theme.AppColors
import com.nettwork.policethief.presentation.theme.LocalDimens
import com.nettwork.policethief.shared.rules.NicknamePolicy
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun LaunchScreen(onLoggedIn: () -> Unit, onNeedLogin: () -> Unit, viewModel: LaunchViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                LaunchEvent.GoHome -> onLoggedIn()
                LaunchEvent.GoLogin -> onNeedLogin()
            }
        }
    }
    ScreenContainer(Modifier.safeDrawingPadding()) {
        Spacer(Modifier.height(80.dp))
        ScreenTitle("경찰과 도둑")
        when (val s = state) {
            LaunchUiState.Restoring -> LoadingState("로그인 정보를 확인하는 중입니다…")
            is LaunchUiState.Failed -> ErrorState(
                message = s.message,
                onPrimary = viewModel::restore,
                secondaryLabel = "새로 로그인",
                onSecondary = viewModel::continueToLogin,
            )
        }
    }
}

/** S-01 게스트 로그인. 닉네임만 입력하면 서버가 고유 ID 와 토큰을 발급한다. */
@Composable
fun LoginScreen(onLoggedIn: (needsPermission: Boolean) -> Unit, viewModel: LoginViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event -> if (event is LoginEvent.LoggedIn) onLoggedIn(event.needsPermission) }
    }
    ScreenContainer(Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(48.dp))
        ScreenTitle("경찰과 도둑")
        Text(
            "반경 500m 안의 사람들과 바로 4인 경기를 시작하세요.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(LocalDimens.current.sectionGap))
        OutlinedTextField(
            value = state.nickname,
            onValueChange = viewModel::onNicknameChange,
            label = { Text("닉네임") },
            supportingText = {
                val error = state.fieldError
                Text(
                    error ?: "${NicknamePolicy.MIN_LENGTH}~${NicknamePolicy.MAX_LENGTH}자, 한글·영문·숫자·_ 사용 가능. 같은 닉네임도 서로 다른 계정입니다.",
                    color = if (error != null) AppColors.Error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            isError = state.fieldError != null,
            singleLine = true,
            enabled = !state.isSubmitting,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { viewModel.submit() }),
            modifier = Modifier.fillMaxWidth().testTag("nicknameField").semantics {
                state.fieldError?.let { error(it) }
            },
        )
        state.submitError?.let {
            Text(
                "⚠ $it",
                color = AppColors.Error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
        Spacer(Modifier.height(LocalDimens.current.itemGap))
        PrimaryActionButton(
            text = if (state.isSubmitting) "로그인 중…" else "게스트로 시작하기",
            onClick = viewModel::submit,
            enabled = state.canSubmit,
            height = LocalDimens.current.homePrimaryButton,
            modifier = Modifier.testTag("loginButton"),
        )
        Text(
            "비밀번호 없이 이 기기에 로그인 정보가 안전하게 저장됩니다. 앱을 삭제하면 계정을 복구할 수 없습니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
