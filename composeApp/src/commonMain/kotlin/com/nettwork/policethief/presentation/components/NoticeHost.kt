package com.nettwork.policethief.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nettwork.policethief.domain.repository.GameNotice
import com.nettwork.policethief.domain.repository.GameSessionRepository
import com.nettwork.policethief.presentation.theme.AppColors
import kotlinx.coroutines.delay
import org.koin.compose.koinInject

/**
 * 앱 안 경기 알림 배너(지시서 22.1). 새 알림이 오면 위쪽에 4초 동안 보이고, 누르면 바로 닫힌다.
 * 스크린리더는 liveRegion 으로 내용을 읽어 준다.
 */
@Composable
fun NoticeHost(session: GameSessionRepository = koinInject()) {
    var current by remember { mutableStateOf<GameNotice?>(null) }
    LaunchedEffect(session) { session.notices.collect { current = it } }
    LaunchedEffect(current) {
        if (current != null) {
            delay(NOTICE_DISPLAY_MILLIS)
            current = null
        }
    }
    val notice = current ?: return
    Box(Modifier.fillMaxWidth().safeDrawingPadding().padding(12.dp)) {
        Column(
            Modifier.fillMaxWidth()
                .background(if (notice.important) AppColors.SurfaceHigh else AppColors.Surface, RoundedCornerShape(12.dp))
                .clickable(onClickLabel = "알림 닫기") { current = null }
                .padding(12.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Text(notice.title, style = MaterialTheme.typography.titleMedium, color = if (notice.important) AppColors.Jail else AppColors.TextPrimary)
            Text(notice.message, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextPrimary)
        }
    }
}

private const val NOTICE_DISPLAY_MILLIS = 4_000L
