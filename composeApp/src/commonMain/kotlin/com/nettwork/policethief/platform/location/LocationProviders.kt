package com.nettwork.policethief.platform.location

import com.nettwork.policethief.config.AppConfig
import com.nettwork.policethief.core.EpochClock
import com.nettwork.policethief.domain.model.PlayerLocation
import com.nettwork.policethief.domain.repository.DevSettingsRepository
import com.nettwork.policethief.platform.LocationProvider
import com.nettwork.policethief.shared.model.LocationSource

/**
 * 개발자 테스트 좌표 공급자. 설정 화면에서 지정한 좌표를 "지금 측정한 것처럼" 돌려준다.
 * source = MOCK 으로 표시되어 서버가 실제 위치와 구분해 저장한다.
 */
class MockLocationProvider(
    private val devSettings: DevSettingsRepository,
    private val clock: EpochClock,
) : LocationProvider {
    override suspend fun getCurrentLocation(): PlayerLocation {
        val settings = devSettings.current.value
        return PlayerLocation(
            userId = null,
            latitude = settings.mockLatitude,
            longitude = settings.mockLongitude,
            accuracyMeters = settings.mockAccuracyMeters,
            timestampEpochMillis = clock.nowMillis(),
            source = LocationSource.MOCK,
        )
    }
}

/**
 * 실제 GPS 와 테스트 좌표 중 하나를 고른다. 테스트 좌표는 개발 빌드에서만 선택된다.
 * 일반(릴리스) 빌드에서는 설정 값과 관계없이 항상 실제 GPS 를 사용한다.
 */
class DevAwareLocationProvider(
    private val real: LocationProvider,
    private val mock: LocationProvider,
    private val devSettings: DevSettingsRepository,
    private val config: AppConfig,
) : LocationProvider {
    override suspend fun getCurrentLocation(): PlayerLocation =
        if (config.isDevBuild && devSettings.current.value.useMockLocation) mock.getCurrentLocation() else real.getCurrentLocation()
}
