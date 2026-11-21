package com.nettwork.policethief.platform

import com.nettwork.policethief.domain.model.PlayerLocation
import kotlinx.coroutines.flow.StateFlow

/*
 * Platform 계층의 "포트(인터페이스)" 모음.
 * 공통 코드(UseCase, ViewModel)는 이 인터페이스만 알고, 실제 구현은 androidMain / iosMain 에 있다.
 * 테스트에서는 가짜(Fake) 구현으로 바꿔 끼운다.
 */

/** 위치 하나를 얻는 공급자. 실제 GPS 구현(RealLocationProvider)과 테스트 좌표(MockLocationProvider)가 있다. */
interface LocationProvider {
    /** 실패 시 [LocationException] 을 던진다. */
    suspend fun getCurrentLocation(): PlayerLocation
}

/** 위치 수집 실패 원인. UI 는 원인별로 다른 안내(권한 요청 / 위치 설정 열기 / 다시 시도)를 보여준다. */
sealed class LocationException(message: String) : Exception(message) {
    class PermissionDenied : LocationException("위치 권한이 없습니다.")
    class ServiceDisabled : LocationException("휴대폰의 위치 서비스(GPS)가 꺼져 있습니다.")
    class Timeout : LocationException("제한 시간 안에 위치를 받지 못했습니다.")
    class Unavailable(reason: String) : LocationException("위치를 확인할 수 없습니다: $reason")
}

enum class AppPermission { LOCATION, CAMERA, NOTIFICATION }

enum class PermissionState { GRANTED, DENIED, NOT_DETERMINED }

/** 권한 상태 확인과 시스템 설정 열기. 권한 "요청" 팝업은 Compose 쪽 rememberPermissionRequester 가 담당한다. */
interface PermissionController {
    fun status(permission: AppPermission): PermissionState
    fun openAppSettings()
}

/**
 * 인증 토큰 보안 저장소.
 * Android: Android Keystore AES-GCM 으로 암호화해 SharedPreferences 에 저장.
 * iOS: Keychain.
 */
interface SecureTokenStorage {
    fun readToken(): String?
    fun writeToken(token: String)
    fun clear()
}

/**
 * 경기 알림(시스템 알림 + 진동). 알림 권한이 거부되어도 예외 없이 무시해야 한다(앱 안 배너는 따로 표시된다).
 * Android: NotificationManager + Vibrator, iOS: UNUserNotificationCenter + 시스템 진동.
 */
interface GameAlerts {
    fun notify(title: String, message: String)
    fun vibrate()
}

/**
 * 화면이 꺼져도 위치를 주기적으로 보내는 백그라운드 위치 서비스.
 * Android: Foreground Service, iOS: Core Location 백그라운드 위치 업데이트.
 * OS 정책 때문에 15초 주기가 보장되지 않는다.
 */
interface BackgroundLocationController {
    /** 이 플랫폼에서 구현되어 있는지. false 면 UI 는 "미지원" 으로 표시한다. */
    val isSupported: Boolean
    val isRunning: StateFlow<Boolean>
    fun start()
    fun stop()
}
