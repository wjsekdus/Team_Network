package com.nettwork.policethief.presentation.navigation

import kotlinx.serialization.Serializable

/*
 * 화면 경로(타입 안전 Navigation). 괄호 안은 UI 설계 보고서의 화면 ID.
 * 내비게이션 패턴은 보고서 2.1 에 따라 "스택"(하단 탭·드로어 없음)이다.
 */

/** 앱 시작: 저장된 세션 복구 후 로그인 또는 홈으로 이동. */
@Serializable data object LaunchRoute

/** S-01 게스트 로그인(닉네임). 보고서의 S-02 회원가입은 최신 요구사항(게스트 로그인)에 따라 제외. */
@Serializable data object LoginRoute

/** S-03 권한 안내. */
@Serializable data object PermissionRoute

/** S-04 홈. */
@Serializable data object HomeRoute

/** S-05 매칭 대기. */
@Serializable data object MatchmakingRoute

/** S-06 역할 공개. */
@Serializable data class RoleRevealRoute(val gameId: String)

/** S-07 게임 구역 확인. */
@Serializable data class ZoneConfirmRoute(val gameId: String)

/** S-08 경찰 게임 지도. */
@Serializable data class PoliceMapRoute(val gameId: String)

/** S-09 도둑 게임 지도. */
@Serializable data class ThiefMapRoute(val gameId: String)

/** S-10 QR 스캔(경찰: 체포 / 미체포 도둑: 동료 구출). */
@Serializable data class QrScanRoute(val gameId: String)

/** S-11 내 QR(도둑). */
@Serializable data class MyQrRoute(val gameId: String)

/** 호송 중(S-08 의 호송 상태를 별도 화면으로 확대). */
@Serializable data class EscortRoute(val gameId: String)

/** 절전 모드(최소 정보 표시). */
@Serializable data class PowerSaveRoute(val gameId: String)

/** S-12 게임 종료. */
@Serializable data class GameResultRoute(val gameId: String)

/** S-13 설정. */
@Serializable data object SettingsRoute
