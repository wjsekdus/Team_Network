package com.nettwork.policethief.domain.model

import com.nettwork.policethief.shared.model.PlayerRole

/** 개발자 설정 값. 개발 빌드(AppConfig.isDevBuild)에서만 효과가 있다. */
data class DevSettings(
    val useMockLocation: Boolean = false,
    val mockLatitude: Double = DEFAULT_MOCK_LATITUDE,
    val mockLongitude: Double = DEFAULT_MOCK_LONGITUDE,
    val mockAccuracyMeters: Double = 5.0,
    /** 비어 있으면 빌드 기본 주소 사용. */
    val serverUrlOverride: String = "",
    /** true: 서버 없이 화면만 확인하는 Mock 경기 / false(기본): 실제 서버 판정. 앱 재시작 후 적용. */
    val useMockGameData: Boolean = false,
    val mockRole: PlayerRole? = null,
) {
    companion object {
        // 기본 테스트 좌표: 서울특별시청
        const val DEFAULT_MOCK_LATITUDE = 37.5665
        const val DEFAULT_MOCK_LONGITUDE = 126.9780
    }
}

/** 일반 사용자 설정(모든 빌드). 앱을 다시 켜도 유지된다. */
data class UserPreferences(
    /** 경기가 시작되면 절전 화면으로 바로 들어갈지(지시서 21.3). */
    val autoPowerSave: Boolean = false,
    val vibration: Boolean = true,
    /** 중요한 경기 이벤트를 시스템 알림으로도 띄울지(앱 안 배너는 항상 표시). */
    val systemNotifications: Boolean = true,
    /** 경기 중 화면이 꺼져도 위치를 보내도록 백그라운드 위치 서비스를 자동으로 켤지. */
    val backgroundLocation: Boolean = true,
)
