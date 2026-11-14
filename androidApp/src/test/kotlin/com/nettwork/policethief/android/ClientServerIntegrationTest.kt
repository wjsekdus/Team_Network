package com.nettwork.policethief.android

import com.nettwork.policethief.core.ServerClock
import com.nettwork.policethief.core.SystemEpochClock
import com.nettwork.policethief.data.mapper.toRequest
import com.nettwork.policethief.shared.dto.JoinMatchmakingRequest
import com.nettwork.policethief.shared.geo.GeoMath
import com.nettwork.policethief.shared.geo.GeoPoint
import com.nettwork.policethief.shared.model.GameEndReason
import com.nettwork.policethief.shared.model.GameResult
import com.nettwork.policethief.shared.model.GameStatus
import com.nettwork.policethief.shared.model.MatchmakingStatus
import com.nettwork.policethief.shared.model.PlayerRole
import com.nettwork.policethief.shared.rules.GameRules
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
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
        // 스케줄러는 끈다: 테스트가 끝나 DB 를 닫은 뒤 tick 이 돌며 오류 로그를 남기지 않도록.
        val serverConfig = ServerConfig("127.0.0.1", 0, DatabaseConfig("", "", "", 1), allowMockLocations = true, tokenTtlDays = 30, appVersion = "it", schedulerEnabled = false)
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

            // 경기가 없을 때 현재 경기 조회는 "없음"(null)
            val noGame = RemoteGameRepository(api, SystemEpochClock, ServerClock(SystemEpochClock)).currentGame()
            assertEquals(null, (noGame as AppResult.Success).value)

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

    /** 서버 시계를 테스트가 앞으로 돌릴 수 있게 한 시계(준비 30초를 실제로 기다리지 않기 위함). */
    private class OffsetClock : Clock() {
        @Volatile var offsetMillis = 0L
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = Instant.now().plusMillis(offsetMillis)
    }

    /** 앱 쪽 한 사람(토큰·API·저장소 묶음). */
    private class AppClient(http: io.ktor.client.HttpClient, urls: ServerUrlProvider, val point: GeoPoint) {
        val session = SessionLocalDataSource(MemoryTokenStorage(), MapSettings())
        val api = PoliceThiefApi(http, urls) { session.token() }
        val auth = AuthRepositoryImpl(api, session)
        val games = RemoteGameRepository(api, SystemEpochClock, ServerClock(SystemEpochClock))
        val locations = LocationRepositoryImpl(
            object : LocationProvider {
                override suspend fun getCurrentLocation() = PlayerLocation(null, point.latitude, point.longitude, 5.0, System.currentTimeMillis(), LocationSource.MOCK)
            },
            api,
            SystemEpochClock,
        )
        lateinit var userId: String
    }

    /**
     * 4인 시나리오: 앱의 실제 Remote 저장소 코드 → 실제 서버 모듈(H2).
     * 로그인 → 4명 매칭 → 경찰 2/도둑 2 → 준비 30초 → 시작 → 역할별 위치 공개 → QR 체포 2회 → 경찰 승리 → 결과.
     */
    @Test
    fun fourAppClients_playAFullGameAgainstRealServer() = testApplication {
        val db = AppDatabase.connect(
            DatabaseConfig("jdbc:h2:mem:it4_${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1", "sa", "", 4),
        )
        val clock = OffsetClock()
        val serverConfig = ServerConfig(
            "127.0.0.1", 0, DatabaseConfig("", "", "", 1),
            allowMockLocations = true, tokenTtlDays = 30, appVersion = "it", schedulerEnabled = false,
        )
        val deps = ServerDependencies.create(serverConfig, db, clock)
        application { module(deps) }
        startApplication()

        val clientConfig = ClientConfig("http://localhost", isDevBuild = true, appVersion = "it", platformName = "jvm")
        val urls = ServerUrlProvider(clientConfig, DevSettingsStore(MapSettings(), clientConfig))
        val http = createHttpClient(client.engine)
        val base = GeoPoint(37.5665, 126.9780)
        val clients = (0 until 4).map { AppClient(http, urls, GeoMath.destination(base, 25.0, it * Math.PI / 2)) }
        try {
            clients.forEachIndexed { i, c -> c.userId = (c.auth.guestLogin("앱참가자$i") as AppResult.Success).value.userId }

            // 매칭: 4번째 사람이 들어오는 순간 서버가 경기를 만든다.
            val tickets = clients.map { c ->
                val here = (c.locations.fetchCurrentLocation() as AppResult.Success).value
                (c.api.joinMatchmaking(JoinMatchmakingRequest(here.toRequest())) as AppResult.Success).value
            }
            assertEquals(MatchmakingStatus.MATCHED, tickets.last().status)
            val game = (clients[0].games.currentGame() as AppResult.Success).value!!
            assertEquals(GameStatus.READY, game.status)
            assertEquals(2, game.players.count { it.role == PlayerRole.POLICE })
            assertEquals(2, game.players.count { it.role == PlayerRole.THIEF })
            clients.forEach { assertEquals(game.gameId, (it.games.currentGame() as AppResult.Success).value?.gameId) }

            // 준비 30초 → 서버가 시작을 확정
            clock.offsetMillis += GameRules.PREPARATION_MILLIS
            deps.games.tick()
            assertEquals(GameStatus.PLAYING, (clients[0].games.getGame(game.gameId) as AppResult.Success).value.status)

            val roleOf = game.players.associate { it.userId to it.role }
            val police = clients.filter { roleOf[it.userId] == PlayerRole.POLICE }
            val thieves = clients.filter { roleOf[it.userId] == PlayerRole.THIEF }

            // 위치 공유: 모두 위치를 보낸 뒤, 도둑의 상태 응답에는 경찰 위치가 없어야 한다.
            clients.forEach { c -> assertTrue(c.locations.sendLocation((c.locations.fetchCurrentLocation() as AppResult.Success).value) is AppResult.Success) }
            val thiefView = (thieves[0].games.state(game.gameId) as AppResult.Success).value
            assertTrue(thiefView.locations.none { roleOf[it.userId] == PlayerRole.POLICE })
            assertEquals(2, thiefView.locations.size)
            assertEquals(4, (police[0].games.state(game.gameId) as AppResult.Success).value.locations.size)

            // QR 체포: 경찰 2명이 각각 도둑 1명씩 → 두 번째 체포에서 즉시 경찰 승리
            val qr1 = (thieves[0].games.myQrToken(game.gameId) as AppResult.Success).value.token
            val qr2 = (thieves[1].games.myQrToken(game.gameId) as AppResult.Success).value.token
            assertTrue((police[0].games.capture(game.gameId, qr1) as AppResult.Success).value.accepted)
            val last = (police[1].games.capture(game.gameId, qr2) as AppResult.Success).value
            assertTrue(last.accepted)
            assertEquals(GameResult.POLICE_WIN, last.gameResult)

            val result = (thieves[1].games.result(game.gameId) as AppResult.Success).value
            assertEquals(GameStatus.FINISHED, result.status)
            assertEquals(GameEndReason.ALL_THIEVES_CAPTURED, result.endReason)
            // 종료 후에는 진행 중 경기가 없고 다시 매칭할 수 있다.
            assertEquals(null, (clients[0].games.currentGame() as AppResult.Success).value)
        } finally {
            db.close()
        }
    }
}
