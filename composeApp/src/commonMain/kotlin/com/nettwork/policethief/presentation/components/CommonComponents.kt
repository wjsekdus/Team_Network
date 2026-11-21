package com.nettwork.policethief.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nettwork.policethief.presentation.theme.AppColors
import com.nettwork.policethief.presentation.theme.LocalDimens
import com.nettwork.policethief.shared.model.PlayerRole

/*
 * 여러 화면에서 반복되는 UI 조각. 화면별 상태(기본·로딩·비어 있음·오류·부분)를 같은 모양으로 보여 주기 위해 사용한다.
 */

/** 주 버튼. 홈은 72dp, 경기 화면은 64dp 높이(보고서 터치 영역 규격). */
@Composable
fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = LocalDimens.current.gamePrimaryButton,
    enabled: Boolean = true,
    containerColor: Color = AppColors.Police,
    contentColor: Color = AppColors.OnAccent,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = height),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = containerColor, contentColor = contentColor),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

/** 보조 버튼(최소 48dp). */
@Composable
fun SecondaryActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = LocalDimens.current.minTouch),
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.semantics { heading() },
    )
}

@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

/** 로딩 상태. 스크린리더가 진행 상황을 읽도록 liveRegion 을 지정한다. */
@Composable
fun LoadingState(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator()
        Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}

/** 오류 상태: 원인 문구 + 다음 행동 버튼. */
@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    primaryLabel: String? = "다시 시도",
    onPrimary: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp).semantics { liveRegion = LiveRegionMode.Assertive },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("⚠ $message", color = AppColors.Error, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (primaryLabel != null && onPrimary != null) SecondaryActionButton(primaryLabel, onPrimary)
            if (secondaryLabel != null && onSecondary != null) SecondaryActionButton(secondaryLabel, onSecondary)
        }
    }
}

/** 데이터 없음 상태. */
@Composable
fun EmptyState(message: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) SecondaryActionButton(actionLabel, onAction)
    }
}

/** 부분 데이터/지연 안내 띠(예: "오프라인 · 이전 정보 기준"). */
@Composable
fun NoticeBanner(message: String, modifier: Modifier = Modifier, color: Color = AppColors.Jail) {
    Text(
        text = message,
        color = AppColors.Background,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .fillMaxWidth()
            .background(color, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/**
 * Mock 데이터임을 알리는 배지. 개발 모드에서 Mock 기능을 실제 기능으로 오해하지 않도록 모든 Mock 화면에 붙인다.
 */
@Composable
fun MockBadge(text: String = "MOCK 데이터", modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = AppColors.Background,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .background(AppColors.Mock, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics { contentDescription = "모의 데이터 화면: $text" },
    )
}

/** 상태 표시등 + 문구. 색만으로 구분하지 않도록 항상 문구를 함께 표시한다. */
@Composable
fun StatusIndicator(label: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier = modifier.sizeIn(minHeight = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.size(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 역할 표시: 색 + 글자("경"/"도")로 색맹 사용자도 구분 가능. */
@Composable
fun RoleBadge(role: PlayerRole, modifier: Modifier = Modifier) {
    val (label, color, onColor) = when (role) {
        PlayerRole.POLICE -> Triple("경찰", AppColors.Police, AppColors.OnAccent)
        PlayerRole.THIEF -> Triple("도둑", AppColors.Thief, AppColors.OnThief)
    }
    Text(
        text = label,
        color = onColor,
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier.background(color, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** 확인 대화상자(D-01 매칭 취소, D-02 게임 나가기, D-03 호송 제한). */
@Composable
fun ConfirmDialog(
    title: String,
    message: String?,
    confirmLabel: String,
    onConfirm: () -> Unit,
    dismissLabel: String?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = message?.let { { Text(it, style = MaterialTheme.typography.bodyLarge) } },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp)) { Text(confirmLabel, color = AppColors.Error) }
        },
        dismissButton = dismissLabel?.let {
            { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(it) } }
        },
    )
}

/** 화면 공통 틀: 좌우 16dp 여백, 최대 폭 제한(태블릿·가로 화면에서 너무 넓어지지 않게). */
@Composable
fun ScreenContainer(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.widthIn(max = 600.dp).fillMaxSize().padding(horizontal = LocalDimens.current.screenPadding),
            verticalArrangement = Arrangement.spacedBy(LocalDimens.current.itemGap),
            content = content,
        )
    }
}
