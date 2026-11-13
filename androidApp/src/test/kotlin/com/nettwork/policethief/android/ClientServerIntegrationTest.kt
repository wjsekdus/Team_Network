package com.nettwork.policethief.android

import com.nettwork.policethief.core.SystemEpochClock
import com.nettwork.policethief.data.local.DevSettingsStore
import com.nettwork.policethief.data.local.ServerUrlProvider
import com.nettwork.policethief.data.local.SessionLocalDataSource
import com.nettwork.policethief.data.remote.PoliceThiefApi
import com.nettwork.policethief.data.remote.WebSocketRealtimeRepository
import com.nettwork.policethief.data.remote.createHttpClient
import com.nettwork.policethief.data.repository.AuthRepositoryImpl
import com.nettwork.policethief.data.repository.LocationRepositoryImpl
import com.nettwork.policethief.data.repository.RemoteGameRepository
import com.nettwork.policethief.domain.model.AppError
import com.nettwork.policethief.domain.model.AppResult
import com.nettwork.policethief.domain.model.PlayerLocation
import com.nettwork.policethief.domain.model.RealtimeConnectionState
import com.nettwork.policethief.domain.model.RealtimeEvent
import com.nettwork.policethief.domain.repository.SessionRestoreResult
import com.nettwork.policethief.platform.LocationProvider
import com.nettwork.policethief.platform.SecureTokenStorage
import com.nettwork.policethief.server.ServerDependencies
import com.nettwork.policethief.server.config.DatabaseConfig
import com.nettwork.policethief.server.database.AppDatabase
import com.nettwork.policethief.server.module
import com.nettwork.policethief.shared.model.LocationSource
import com.russhwolf.settings.MapSettings
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.util.UUID
import com.nettwork.policethief.config.AppConfig as ClientConfig
import com.nettwork.policethief.server.config.AppConfig as ServerConfig

/**
 * 클라이언트–서버 통합 테스트.
 * 앱의 실제 코드(PoliceThiefApi, AuthRepositoryImpl, LocationRepositoryImpl, WebSocketRealtimeRepository)를
 * 실제 서버 모듈(Ktor + Flyway + Exposed, H2 DB)에 연결해 계약이 맞는지 확인한다.
 * 네트워크 소켓 대신 Ktor 테스트 엔진을 사용한다(실기기·실네트워크 검증은 별도).
 */
class ClientServerIntegrationTest {
    private class MemoryTokenStorage : SecureTokenStorage {
        var token: String? = null
        override fun readToken() = token
        override fun writeToken(token: String) { this.token = token }
        override fun clear() { token = null }
    }

    @Test
    fun appClient_talksToRealServerModule_restAndWebSocket() = testApplication {
        val db = AppDatabase.connect(
            DatabaseConfig("jdbc:h2:mem:it_${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1", "sa", "", 2),
        )
        val serverConfig = ServerConfig("127.0.0.1", 0, DatabaseConfig("", "", "", 1), allowMockLocations = true, tokenTtlDays = 30, appVersion = "it")
        application { module(ServerDependencies.create(serverConfig, db, Clock.systemUTC())) }
        startApplication()

        val clientConfig = ClientConfig("http://localhost", isDevBuild = true, appVersion = "it", platformName = "jvm")
        val settings = MapSettings()
        val urls = ServerUrlProvider(clientConfig, DevSettingsStore(settings, clientConfig))
        val session = SessionLocalDataSource(MemoryTokenStorage(), settings)
        val http = createHttpClient(client.engine)
        val api = PoliceThiefApi(http, urls) { session.token() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // 서버 상태
            assertTrue((api.health() as AppResult.Success).value.status == "UP")

            // 게스트 로그인 → 세션 복구
            val auth = AuthRepositoryImpl(api, session)
            val user = (auth.guestLogin("통합테스트") as AppResult.Success).value
            val restored = (auth.restoreSession() as AppResult.Success).value
            assertEquals(SessionRestoreResult.Restored(user.copy(lastActiveAtEpochMillis = (restored as SessionRestoreResult.Restored).user.lastActiveAtEpochMillis), offline = false), restored)

            // 위치 전송 (개발자 테스트 좌표)
            val mockProvider = object : LocationProvider {
                override suspend fun getCurrentLocation() =
                    PlayerLocation(null, 37.5665, 126.9780, 5.0, System.currentTimeMillis(), LocationSource.MOCK)
            }
            val locations = LocationRepositoryImpl(mockProvider, api, SystemEpochClock)
            val sent = locations.sendLocation(locations.fetchCurrentLocation().let { (it as AppResult.Success).value })
            assertEquals(user.userId, (sent as AppResult.Success).value.userId)

            // 미구현 게임 API 는 정상 동작처럼 보이지 않고 NotImplemented 로 드러나야 한다
            val game = RemoteGameRepository(api, SystemEpochClock).getGame(UUID.randomUUID().toString())
            assertTrue((game as AppResult.Failure).error is AppError.NotImplemented)

            // WebSocket: 연결 → welcome → 테스트 이벤트
            val realtime = WebSocketRealtimeRepository(http, urls, { session.token() }, SystemEpochClock, scope)
            realtime.connect()
            withTimeout(10_000) { realtime.connectionState.first { it is RealtimeConnectionState.Connected } }
            val welcome = withTimeout(10_000) { realtime.events.first { it is RealtimeEvent.Welcome } } as RealtimeEvent.Welcome
            assertEquals(user.userId, welcome.userId)
            assertTrue(realtime.requestTestEvent("e2e") is AppResult.Success)
            val event = withTimeout(10_000) { realtime.events.first { it is RealtimeEvent.TestEvent } } as RealtimeEvent.TestEvent
            assertTrue(event.message.contains("e2e"))
            realtime.disconnect()
            assertEquals(RealtimeConnectionState.Disconnected, realtime.connectionState.value)

            // 로그아웃 후 인증 API 는 호출 전 차단
            auth.logout()
            assertEquals(AppError.Unauthorized, (api.me() as AppResult.Failure).error)
        } finally {
            scope.cancel()
            db.close()
        }
    }
}
