package com.nettwork.policethief.server

import com.nettwork.policethief.server.auth.AuthService
import com.nettwork.policethief.server.auth.ExposedAuthTokenRepository
import com.nettwork.policethief.server.auth.ExposedUserRepository
import com.nettwork.policethief.server.auth.publicAuthRoutes
import com.nettwork.policethief.server.auth.userRoutes
import com.nettwork.policethief.server.config.AppConfig
import com.nettwork.policethief.server.database.AppDatabase
import com.nettwork.policethief.server.common.GameLock
import com.nettwork.policethief.server.dev.DevClock
import com.nettwork.policethief.server.dev.DevToolsService
import com.nettwork.policethief.server.dev.devToolRoutes
import com.nettwork.policethief.server.event.ConnectionManager
import com.nettwork.policethief.server.event.realtimeRoutes
import com.nettwork.policethief.server.game.GameEngine
import com.nettwork.policethief.server.game.GameStore
import com.nettwork.policethief.server.game.gameRoutes
import com.nettwork.policethief.server.health.healthRoutes
import com.nettwork.policethief.server.location.ExposedLocationRepository
import com.nettwork.policethief.server.location.LocationService
import com.nettwork.policethief.server.location.locationRoutes
import com.nettwork.policethief.server.matchmaking.MatchmakingService
import com.nettwork.policethief.server.notification.WebSocketNotificationService
import com.nettwork.policethief.server.plugins.AUTH_BEARER
import com.nettwork.policethief.server.plugins.configureErrorHandling
import com.nettwork.policethief.server.plugins.configureMonitoring
import com.nettwork.policethief.server.plugins.configureSecurity
import com.nettwork.policethief.server.plugins.configureSerialization
import com.nettwork.policethief.server.plugins.configureWebSockets
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.auth.authenticate
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration

fun main() {
    val log = LoggerFactory.getLogger("Application")
    val config = AppConfig.fromEnvironment()
    log.info(
        "서버 설정: host={}, port={}, db={}, allowMockLocations={}, devTools={}, game={}",
        config.host, config.port, config.database, config.allowMockLocations, config.devToolsEnabled, config.game,
    )
    if (config.devToolsEnabled) log.warn("⚠ 개발자 도구(/api/dev, 봇·시간 이동)가 켜져 있습니다. 실제 경기 서버에서는 PNT_DEV_TOOLS=false 로 실행하세요.")

    val database = AppDatabase.connect(config.database)
    val clock: Clock = if (config.devToolsEnabled) DevClock(Clock.systemUTC()) else Clock.systemUTC()
    val dependencies = ServerDependencies.create(config, database, clock)

    embeddedServer(Netty, port = config.port, host = config.host) {
        module(dependencies)
        monitor.subscribe(ApplicationStopped) { database.close() }
    }.start(wait = true)
}

/**
 * 서버 객체 조립(수동 의존성 주입).
 * 프레임워크 없이 생성자로 연결해 "어떤 객체가 무엇을 쓰는지"를 한 파일에서 볼 수 있게 했다.
 */
class ServerDependencies(
    val config: AppConfig,
    val clock: Clock,
    val isDatabaseHealthy: () -> Boolean,
    val authService: AuthService,
    val locationService: LocationService,
    val connections: ConnectionManager,
    val matchmaking: MatchmakingService,
    val games: GameEngine,
    /** 개발자 도구가 켜졌을 때만 존재. */
    val devTools: DevToolsService?,
) {
    companion object {
        fun create(config: AppConfig, database: AppDatabase, clock: Clock): ServerDependencies {
            require(!config.devToolsEnabled || (config.allowMockLocations && clock is DevClock)) {
                "PNT_DEV_TOOLS=true 는 PNT_ALLOW_MOCK_LOCATIONS=true 와 DevClock 이 필요합니다."
            }
            val connections = ConnectionManager()
            val notifications = WebSocketNotificationService(connections)
            val lock = GameLock()
            val store = GameStore()
            val authService = AuthService(
                users = ExposedUserRepository(database),
                tokens = ExposedAuthTokenRepository(database),
                clock = clock,
                tokenTtl = Duration.ofDays(config.tokenTtlDays),
            )
            val matchmaking = MatchmakingService(database, lock, store, clock, config.game, config.allowMockLocations, notifications)
            val engine = GameEngine(
                db = database,
                lock = lock,
                store = store,
                matchmaking = matchmaking,
                notifications = notifications,
                clock = clock,
                settings = config.game,
                // 테스트 좌표 이동은 개발 서버에서만 과속 판정 예외(지시서 20.5). 운영(MOCK 금지)에서는 MOCK 이 아예 들어오지 않는다.
                mockLocationsExemptFromSpeed = config.allowMockLocations,
                isUserConnected = connections::isConnected,
            )
            val locationService = LocationService(ExposedLocationRepository(database), clock, config.allowMockLocations, engine)
            val devTools = if (config.devToolsEnabled) DevToolsService(authService, matchmaking, locationService, engine, clock as DevClock) else null
            return ServerDependencies(config, clock, database::isHealthy, authService, locationService, connections, matchmaking, engine, devTools)
        }
    }
}

/** Ktor 애플리케이션 모듈. 테스트(testApplication)에서도 같은 함수를 사용한다. */
fun Application.module(deps: ServerDependencies) {
    configureSerialization()
    configureMonitoring()
    configureErrorHandling()
    configureSecurity(deps.authService)
    configureWebSockets()

    routing {
        healthRoutes(deps.isDatabaseHealthy, deps.config.appVersion, deps.clock)
        publicAuthRoutes(deps.authService)

        authenticate(AUTH_BEARER) {
            userRoutes(deps.authService)
            locationRoutes(deps.locationService)
            realtimeRoutes(deps.connections, deps.clock, deps.games)
            gameRoutes(deps.matchmaking, deps.games)
            deps.devTools?.let { devToolRoutes(it) }
        }
    }

    if (deps.config.schedulerEnabled) startGameScheduler(deps)
}

/**
 * 시간 기반 판정 스케줄러. 서버 시작 시 재시작 복구를 한 번 하고, 이후 약 1초마다 tick 한다.
 * 한 번의 tick 이 실패해도(예: DB 일시 중단) 다음 tick 에서 다시 시도한다. 상태는 DB 에 있으므로 잃지 않는다.
 */
private fun Application.startGameScheduler(deps: ServerDependencies) {
    val log = LoggerFactory.getLogger("GameScheduler")
    launch {
        runCatching { deps.games.recoverAfterRestart() }.onFailure { log.error("재시작 복구 실패(다음 tick 에서 계속 진행)", it) }
        while (isActive) {
            try {
                deps.games.tick()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("스케줄러 tick 실패: {}", e.message, e)
            }
            delay(deps.config.game.schedulerIntervalMillis)
        }
    }
}
