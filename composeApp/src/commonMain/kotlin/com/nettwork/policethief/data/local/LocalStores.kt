package com.nettwork.policethief.data.local

import com.nettwork.policethief.config.AppConfig
import com.nettwork.policethief.domain.model.User
import com.nettwork.policethief.platform.SecureTokenStorage
import com.nettwork.policethief.shared.model.PlayerRole
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 로그인 세션 로컬 저장소.
 * - 토큰(비밀): SecureTokenStorage (Keystore / Keychain)
 * - 사용자 프로필(비밀 아님): Settings — 오프라인일 때도 홈에 닉네임을 보여주기 위한 캐시
 */
class SessionLocalDataSource(
    private val tokenStorage: SecureTokenStorage,
    private val settings: Settings,
) {
    // 요청마다 Keystore 복호화를 하지 않도록 메모리에 한 번 읽어 둔다.
    private var cachedToken: String? = null
    private var tokenLoaded = false

    fun token(): String? {
        if (!tokenLoaded) {
            cachedToken = tokenStorage.readToken()
            tokenLoaded = true
        }
        return cachedToken
    }

    fun saveSession(token: String, user: User) {
        tokenStorage.writeToken(token)
        cachedToken = token
        tokenLoaded = true
        saveUser(user)
    }

    fun saveUser(user: User) {
        settings.putString(KEY_USER_ID, user.userId)
        settings.putString(KEY_NICKNAME, user.nickname)
        settings.putLong(KEY_CREATED_AT, user.createdAtEpochMillis)
        settings.putLong(KEY_LAST_ACTIVE_AT, user.lastActiveAtEpochMillis)
    }

    fun cachedUser(): User? {
        val id = settings.getStringOrNull(KEY_USER_ID) ?: return null
        val nickname = settings.getStringOrNull(KEY_NICKNAME) ?: return null
        return User(id, nickname, settings.getLong(KEY_CREATED_AT, 0), settings.getLong(KEY_LAST_ACTIVE_AT, 0))
    }

    fun clear() {
        tokenStorage.clear()
        cachedToken = null
        tokenLoaded = true
        listOf(KEY_USER_ID, KEY_NICKNAME, KEY_CREATED_AT, KEY_LAST_ACTIVE_AT).forEach(settings::remove)
    }

    private companion object {
        const val KEY_USER_ID = "session.userId"
        const val KEY_NICKNAME = "session.nickname"
        const val KEY_CREATED_AT = "session.createdAt"
        const val KEY_LAST_ACTIVE_AT = "session.lastActiveAt"
    }
}

/** 개발자 설정 값. 개발 빌드(AppConfig.isDevBuild)에서만 효과가 있다. */
data class DevSettings(
    val useMockLocation: Boolean = false,
    val mockLatitude: Double = DEFAULT_MOCK_LATITUDE,
    val mockLongitude: Double = DEFAULT_MOCK_LONGITUDE,
    val mockAccuracyMeters: Double = 5.0,
    /** 비어 있으면 빌드 기본 주소 사용. */
    val serverUrlOverride: String = "",
    /** true: 게임 화면이 Mock 데이터 사용 / false: 실제 서버 API 호출(현재 501). 앱 재시작 후 적용. */
    val useMockGameData: Boolean = true,
    val mockRole: PlayerRole? = null,
) {
    companion object {
        // 기본 테스트 좌표: 서울특별시청
        const val DEFAULT_MOCK_LATITUDE = 37.5665
        const val DEFAULT_MOCK_LONGITUDE = 126.9780
    }
}

/**
 * 개발자 설정 저장소. 일반 사용자 빌드(isDevBuild=false)에서는 항상 기본값(실제 GPS, 기본 서버 주소)을 돌려준다.
 */
class DevSettingsStore(private val settings: Settings, private val config: AppConfig) {
    private val state = MutableStateFlow(load())
    val current: StateFlow<DevSettings> = state.asStateFlow()

    fun update(transform: (DevSettings) -> DevSettings) {
        if (!config.isDevBuild) return
        val next = transform(state.value)
        settings.putBoolean(KEY_USE_MOCK_LOCATION, next.useMockLocation)
        settings.putDouble(KEY_MOCK_LAT, next.mockLatitude)
        settings.putDouble(KEY_MOCK_LON, next.mockLongitude)
        settings.putDouble(KEY_MOCK_ACCURACY, next.mockAccuracyMeters)
        settings.putString(KEY_SERVER_URL, next.serverUrlOverride.trim())
        settings.putBoolean(KEY_USE_MOCK_GAME, next.useMockGameData)
        settings.putString(KEY_MOCK_ROLE, next.mockRole?.name ?: "")
        state.value = next
    }

    private fun load(): DevSettings {
        if (!config.isDevBuild) return DevSettings(useMockGameData = false)
        return DevSettings(
            useMockLocation = settings.getBoolean(KEY_USE_MOCK_LOCATION, false),
            mockLatitude = settings.getDouble(KEY_MOCK_LAT, DevSettings.DEFAULT_MOCK_LATITUDE),
            mockLongitude = settings.getDouble(KEY_MOCK_LON, DevSettings.DEFAULT_MOCK_LONGITUDE),
            mockAccuracyMeters = settings.getDouble(KEY_MOCK_ACCURACY, 5.0),
            serverUrlOverride = settings.getString(KEY_SERVER_URL, ""),
            useMockGameData = settings.getBoolean(KEY_USE_MOCK_GAME, true),
            mockRole = settings.getString(KEY_MOCK_ROLE, "").takeIf { it.isNotEmpty() }?.let(PlayerRole::valueOf),
        )
    }

    private companion object {
        const val KEY_USE_MOCK_LOCATION = "dev.useMockLocation"
        const val KEY_MOCK_LAT = "dev.mockLatitude"
        const val KEY_MOCK_LON = "dev.mockLongitude"
        const val KEY_MOCK_ACCURACY = "dev.mockAccuracy"
        const val KEY_SERVER_URL = "dev.serverUrlOverride"
        const val KEY_USE_MOCK_GAME = "dev.useMockGameData"
        const val KEY_MOCK_ROLE = "dev.mockRole"
    }
}

/** 지금 사용할 서버 주소. 개발 빌드에서 설정 화면 값이 있으면 그것을, 없으면 빌드 기본값을 쓴다. */
class ServerUrlProvider(private val config: AppConfig, private val devSettings: DevSettingsStore) {
    fun baseUrl(): String {
        val override = devSettings.current.value.serverUrlOverride.takeIf { config.isDevBuild && it.isNotBlank() }
        return (override ?: config.defaultServerUrl).trim().trimEnd('/')
    }

    /** http → ws, https → wss. 외부(Cloudflare Tunnel) 접속은 https/wss 를 사용한다. */
    fun webSocketUrl(path: String): String {
        val base = baseUrl()
        val wsBase = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> base
        }
        return wsBase + path
    }
}
