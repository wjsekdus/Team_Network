package com.nettwork.policethief.server

import com.nettwork.policethief.server.auth.AuthService
import com.nettwork.policethief.server.auth.ExposedAuthTokenRepository
import com.nettwork.policethief.server.auth.ExposedUserRepository
import com.nettwork.policethief.server.auth.publicAuthRoutes
import com.nettwork.policethief.server.auth.userRoutes
import com.nettwork.policethief.server.config.AppConfig
import com.nettwork.policethief.server.database.AppDatabase
import com.nettwork.policethief.server.event.ConnectionManager
import com.nettwork.policethief.server.event.realtimeRoutes
import com.nettwork.policethief.server.game.CaptureService
import com.nettwork.policethief.server.game.GameService
import com.nettwork.policethief.server.game.MatchmakingService
import com.nettwork.policethief.server.game.NotImplementedCaptureService
import com.nettwork.policethief.server.game.NotImplementedGameService
import com.nettwork.policethief.server.game.NotImplementedMatchmakingService
import com.nettwork.policethief.server.game.NotImplementedQrTokenService
import com.nettwork.policethief.server.game.NotImplementedRescueService
import com.nettwork.policethief.server.game.QrTokenService
import com.nettwork.policethief.server.game.RescueService
import com.nettwork.policethief.server.game.gameRoutes
import com.nettwork.policethief.server.health.healthRoutes
import com.nettwork.policethief.server.location.ExposedLocationRepository
import com.nettwork.policethief.server.location.LocationService
import com.nettwork.policethief.server.location.locationRoutes
import com.nettwork.policethief.server.notification.NotificationService
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
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration

fun main() {
    val log = LoggerFactory.getLogger("Application")
    val config = AppConfig.fromEnvironment()
    log.info("서버 설정: host={}, port={}, db={}, allowMockLocations={}", config.host, config.port, config.database, config.allowMockLocations)

    val database = AppDatabase.connect(config.database)
    val dependencies = ServerDependencies.create(config, database, Clock.systemUTC())

    embeddedServer(Netty, port = config.port, host = config.host) {
        module(dependencies)
        monitor.subscribe(ApplicationStopped) { database.close() }
    }.start(wait = true)
}

/**
 * 서버 객체 조립(수동 의존성 주입).
 *
 * 프레임워크 없이 생성자로 연결해 "어떤 객체가 무엇을 쓰는지"를 한 파일에서 볼 수 있게 했다.
 * 향후 기능을 구현하면 NotImplemented... 자리를 실제 구현으로 바꾸기만 하면 된다.
 */
class ServerDependencies(
    val clock: Clock,
    val appVersion: String,
    val isDatabaseHealthy: () -> Boolean,
    val authService: AuthService,
    val locationService: LocationService,
    val connections: ConnectionManager,
    val notifications: NotificationService,
    val matchmaking: MatchmakingService = NotImplementedMatchmakingService(),
    val games: GameService = NotImplementedGameService(),
    val qrTokens: QrTokenService = NotImplementedQrTokenService(),
    val captures: CaptureService = NotImplementedCaptureService(),
    val rescues: RescueService = NotImplementedRescueService(),
) {
    companion object {
        fun create(config: AppConfig, database: AppDatabase, clock: Clock): ServerDependencies {
            val connections = ConnectionManager()
            return ServerDependencies(
                clock = clock,
                appVersion = config.appVersion,
                isDatabaseHealthy = database::isHealthy,
                authService = AuthService(
                    users = ExposedUserRepository(database),
                    tokens = ExposedAuthTokenRepository(database),
                    clock = clock,
                    tokenTtl = Duration.ofDays(config.tokenTtlDays),
                ),
                locationService = LocationService(ExposedLocationRepository(database), clock, config.allowMockLocations),
                connections = connections,
                notifications = WebSocketNotificationService(connections),
            )
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
        healthRoutes(deps.isDatabaseHealthy, deps.appVersion, deps.clock)
        publicAuthRoutes(deps.authService)

        authenticate(AUTH_BEARER) {
            userRoutes(deps.authService)
            locationRoutes(deps.locationService)
            realtimeRoutes(deps.connections, deps.clock)
            gameRoutes(deps.matchmaking, deps.games, deps.qrTokens, deps.captures, deps.rescues)
        }
    }
}
