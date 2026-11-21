package com.nettwork.policethief.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * UI 설계 보고서 "공통 UI 규격"의 어두운 화면 팔레트.
 * 접근성: 파랑(#2563EB) 글자는 어두운 배경에서 명암비 3.74:1 로 미달이므로 글자색이 아니라
 *        버튼/도형 배경으로만 쓰고, 그 위 글자는 흰색으로 표시한다.
 */
object AppColors {
    val Background = Color(0xFF0A0E16)
    val Surface = Color(0xFF121A28)
    val SurfaceHigh = Color(0xFF1B2638)
    val TextPrimary = Color(0xFFE6EDF7)
    val TextSecondary = Color(0xFFA7B4C8)
    val Police = Color(0xFF2563EB)
    val Thief = Color(0xFFFB923C)
    val Success = Color(0xFF4ADE80)
    val Error = Color(0xFFF87171)
    val Jail = Color(0xFFFACC15)
    val OnAccent = Color(0xFFFFFFFF)
    val OnThief = Color(0xFF1A0F05)
    val Mock = Color(0xFFC084FC)
}

/** 보고서의 여백·터치 영역 규격. */
@Immutable
data class Dimens(
    val screenPadding: androidx.compose.ui.unit.Dp = 16.dp,
    val itemGap: androidx.compose.ui.unit.Dp = 8.dp,
    val sectionGap: androidx.compose.ui.unit.Dp = 20.dp,
    val homePrimaryButton: androidx.compose.ui.unit.Dp = 72.dp,
    val gamePrimaryButton: androidx.compose.ui.unit.Dp = 64.dp,
    val minTouch: androidx.compose.ui.unit.Dp = 48.dp,
)

val LocalDimens = staticCompositionLocalOf { Dimens() }

private val AppTypography = Typography(
    // 40sp: 역할·승패
    displayMedium = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold, lineHeight = 48.sp),
    // 28sp: 화면 제목·타이머
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp),
    titleMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp),
    // 16sp: 버튼·본문
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, lineHeight = 20.sp),
    // 14sp: 안내
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    // 12sp: 캡션
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, lineHeight = 16.sp),
)

private val DarkColors = darkColorScheme(
    primary = AppColors.Police,
    onPrimary = AppColors.OnAccent,
    secondary = AppColors.Thief,
    onSecondary = AppColors.OnThief,
    tertiary = AppColors.Jail,
    background = AppColors.Background,
    onBackground = AppColors.TextPrimary,
    surface = AppColors.Surface,
    onSurface = AppColors.TextPrimary,
    surfaceVariant = AppColors.SurfaceHigh,
    onSurfaceVariant = AppColors.TextSecondary,
    surfaceContainer = AppColors.Surface,
    surfaceContainerHigh = AppColors.SurfaceHigh,
    error = AppColors.Error,
    onError = AppColors.Background,
    outline = AppColors.TextSecondary,
)

/** 앱 테마. 요구사항에 따라 기본(현재 유일) 테마는 다크 모드다. */
@Composable
fun PoliceThiefTheme(content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalDimens provides Dimens()) {
        MaterialTheme(colorScheme = DarkColors, typography = AppTypography, content = content)
    }
}
