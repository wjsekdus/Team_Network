package com.nettwork.policethief.domain.repository

import com.nettwork.policethief.domain.model.DevSettings
import com.nettwork.policethief.domain.model.UserPreferences
import kotlinx.coroutines.flow.StateFlow

/** 개발자 설정 저장소(구현: data.local.DevSettingsStore). 일반 사용자 빌드에서는 항상 기본값이다. */
interface DevSettingsRepository {
    val current: StateFlow<DevSettings>
    fun update(transform: (DevSettings) -> DevSettings)
}

/** 사용자 설정 저장소(구현: data.local.UserPreferencesStore). */
interface UserPreferencesRepository {
    val current: StateFlow<UserPreferences>
    fun update(transform: (UserPreferences) -> UserPreferences)
}

/** 지금 접속하는 서버 주소(구현: data.local.ServerUrlProvider). */
interface ServerAddressProvider {
    fun baseUrl(): String
}
