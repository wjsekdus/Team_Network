package com.nettwork.policethief.server

import com.nettwork.policethief.server.config.AppConfig
import com.nettwork.policethief.server.config.DatabaseConfig
import com.nettwork.policethief.server.database.AppDatabase
import com.nettwork.policethief.server.dev.DevClock
import com.nettwork.policethief.server.game.GameSettings
import com.nettwork.policethief.shared.api.ApiJson
import com.nettwork.policethief.shared.api.ApiPaths
import com.nettwork.policethief.shared.dto.GameDto
import com.nettwork.policethief.shared.dto.GameEventDto
import com.nettwork.policethief.shared.dto.GameResultDto
import com.nettwork.policethief.shared.dto.GameStateDto
import com.nettwork.policethief.shared.dto.GuestLoginRequest
import com.nettwork.policethief.shared.dto.GuestLoginResponse
import com.nettwork.policethief.shared.dto.JoinMatchmakingRequest
import com.nettwork.policethief.shared.dto.LocationUpdateRequest
import com.nettwork.policethief.shared.dto.MatchmakingTicketDto
import com.nettwork.policethief.shared.dto.QrScanRequest
import com.nettwork.policethief.shared.dto.QrScanResultDto
import com.nettwork.policethief.shared.dto.QrTokenDto
import com.nettwork.policethief.shared.dto.VisibleLocationsResponse
import com.nettwork.policethief.shared.geo.GeoMath
import com.nettwork.policethief.shared.geo.GeoPoint
import com.nettwork.policethief.shared.model.GameStatus
import com.nettwork.policethief.shared.model.LocationSource
import com.nettwork.policethief.shared.model.PlayerRole
import com.nettwork.policethief.shared.rules.GameRules
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** 테스트에서 시간을 직접 움직이기 위한 시계(토큰 만료 등). */
class TestClock(var now: Instant = Instant.parse("2026-11-13T00:00:00Z")) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now
}

/** 테스트마다 새 H2(PostgreSQL 호환 모드) 메모리 DB 를 만들고 Flyway 마이그레이션을 적용한다. */
fun newH2Database(): AppDatabase = AppDatabase.connect(
    DatabaseConfig(
        jdbcUrl = "jdbc:h2:mem:test_${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        user = "sa",
        password = "",
        maxPoolSize = 4,
    ),
)

/** 테스트는 스케줄러를 끄고 tick() 을 직접 호출해 시간을 통제한다. */
fun testConfig(
    allowMockLocations: Boolean = false,
    devToolsEnabled: Boolean = false,
    game: GameSettings = GameSettings(),
) = AppConfig(
    host = "127.0.0.1",
    port = 0,
    database = DatabaseConfig("unused", "unused", "unused", 1),
    allowMockLocations = allowMockLocations,
    tokenTtlDays = 30,
    appVersion = "test",
    devToolsEnabled = devToolsEnabled,
    schedulerEnabled = false,
    game = game,
)

/** 서버 모듈 전체를 띄운 상태에서 테스트 본문을 실행한다. */
fun serverTest(
    clock: TestClock = TestClock(),
    allowMockLocations: Boolean = false,
    block: suspend ApplicationTestBuilder.(HttpClient) -> Unit,
) = gameServerTest(clock, allowMockLocations) { builder.block(client) }

/** 경기 테스트용 컨텍스트: HTTP 클라이언트 + 서버 객체(tick 직접 호출) + 시계. */
class GameTestContext(
    val builder: ApplicationTestBuilder,
    val client: HttpClient,
    val deps: ServerDependencies,
    val clock: TestClock,
    val db: AppDatabase,
) {
    suspend fun player(nickname: String): TestPlayer {
        val login = client.guestLogin(nickname)
        return TestPlayer(this, login.accessToken, login.user.userId, nickname)
    }

    suspend fun players(count: Int, prefix: String = "참가자"): List<TestPlayer> = (1..count).map { player("$prefix$it") }

    fun advance(millis: Long) {
        clock.now = clock.now.plusMillis(millis)
    }

    suspend fun tick() = deps.games.tick()

    suspend fun advanceAndTick(millis: Long) {
        advance(millis)
        tick()
    }
}

fun gameServerTest(
    clock: TestClock = TestClock(),
    allowMockLocations: Boolean = false,
    devToolsEnabled: Boolean = false,
    game: GameSettings = GameSettings(),
    block: suspend GameTestContext.() -> Unit,
) {
    newH2Database().use { db ->
        val serverClock: Clock = if (devToolsEnabled) DevClock(clock) else clock
        val deps = ServerDependencies.create(testConfig(allowMockLocations || devToolsEnabled, devToolsEnabled, game), db, serverClock)
        testApplication {
            application { module(deps) }
            val client = createClient {
                install(ContentNegotiation) { json(ApiJson) }
                install(WebSockets)
            }
            GameTestContext(this, client, deps, clock, db).block()
        }
    }
}

suspend fun HttpClient.guestLogin(nickname: String = "테스터"): GuestLoginResponse =
    post(ApiPaths.AUTH_GUEST) {
        contentType(ContentType.Application.Json)
        setBody(GuestLoginRequest(nickname))
    }.body()

/** 테스트 기준점(서울시청)과 주변 좌표. */
val BASE = GeoPoint(37.5665, 126.9780)

fun around(meters: Double, bearingDegrees: Double, from: GeoPoint = BASE): GeoPoint =
    GeoMath.destination(from, meters, Math.toRadians(bearingDegrees))

/** REST 로 행동하는 테스트 참가자. */
class TestPlayer(val ctx: GameTestContext, val token: String, val userId: String, val nickname: String) {
    private val client get() = ctx.client

    fun location(point: GeoPoint, accuracy: Double = 5.0, measuredAt: Long = ctx.clock.millis(), source: LocationSource = LocationSource.GPS) =
        LocationUpdateRequest(point.latitude, point.longitude, accuracy, measuredAt, source)

    suspend fun join(point: GeoPoint, accuracy: Double = 5.0, source: LocationSource = LocationSource.GPS): HttpResponse =
        client.post(ApiPaths.MATCHMAKING) {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(JoinMatchmakingRequest(location(point, accuracy, source = source)))
        }

    suspend fun ticket(): MatchmakingTicketDto = client.get(ApiPaths.MATCHMAKING_ME) { bearerAuth(token) }.body()

    suspend fun cancel(): HttpResponse = client.delete(ApiPaths.MATCHMAKING_ME) { bearerAuth(token) }

    suspend fun currentGameResponse(): HttpResponse = client.get(ApiPaths.GAMES_CURRENT) { bearerAuth(token) }

    suspend fun game(gameId: String): GameDto = gameResponse(gameId).body()

    suspend fun gameResponse(gameId: String): HttpResponse = client.get(ApiPaths.game(gameId)) { bearerAuth(token) }

    suspend fun state(gameId: String): GameStateDto = client.get(ApiPaths.gameState(gameId)) { bearerAuth(token) }.body()

    suspend fun locate(point: GeoPoint, accuracy: Double = 5.0, measuredAt: Long = ctx.clock.millis(), source: LocationSource = LocationSource.GPS): HttpResponse =
        client.post(ApiPaths.LOCATIONS) {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(location(point, accuracy, measuredAt, source))
        }

    suspend fun visibleResponse(gameId: String): HttpResponse = client.get(ApiPaths.gameVisibleLocations(gameId)) { bearerAuth(token) }

    suspend fun visible(gameId: String): VisibleLocationsResponse = visibleResponse(gameId).body()

    suspend fun myQrResponse(gameId: String): HttpResponse = client.get(ApiPaths.gameMyQr(gameId)) { bearerAuth(token) }

    suspend fun myQr(gameId: String): QrTokenDto = myQrResponse(gameId).body()

    suspend fun captureResponse(gameId: String, qrToken: String, requestId: String = UUID.randomUUID().toString()): HttpResponse =
        client.post(ApiPaths.gameCaptures(gameId)) {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(QrScanRequest(qrToken, requestId, ctx.clock.millis()))
        }

    suspend fun capture(gameId: String, qrToken: String, requestId: String = UUID.randomUUID().toString()): QrScanResultDto =
        captureResponse(gameId, qrToken, requestId).body()

    suspend fun rescue(gameId: String, qrToken: String, requestId: String = UUID.randomUUID().toString()): QrScanResultDto =
        client.post(ApiPaths.gameRescues(gameId)) {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(QrScanRequest(qrToken, requestId, ctx.clock.millis()))
        }.body()

    suspend fun forfeit(gameId: String): GameDto = client.post(ApiPaths.gameForfeit(gameId)) { bearerAuth(token) }.body()

    suspend fun events(gameId: String, after: Long = 0): List<GameEventDto> =
        client.get(ApiPaths.gameEvents(gameId) + "?after=$after") { bearerAuth(token) }.body()

    suspend fun result(gameId: String): GameResultDto = client.get(ApiPaths.gameResult(gameId)) { bearerAuth(token) }.body()

    val uuid: UUID get() = UUID.fromString(userId)
}

fun GameDto.player(userId: String) = players.first { it.userId == userId }

/** 매칭되어 경기가 시작된 4인. */
data class StartedGame(val gameId: String, val police: List<TestPlayer>, val thieves: List<TestPlayer>, val game: GameDto) {
    val all: List<TestPlayer> get() = police + thieves
    val center: GeoPoint get() = GeoPoint(game.centerLatitude, game.centerLongitude)
}

/** 서울시청 주변 20m 에서 4명을 매칭하고, 준비 30초를 지나 PLAYING 으로 만든다. */
suspend fun GameTestContext.startedGame(prefix: String = "참가자"): StartedGame {
    val players = players(GameRules.PLAYERS_PER_GAME, prefix)
    players.forEachIndexed { index, player -> player.join(around(20.0, index * 90.0)) }
    val gameId = players.first().currentGameResponse().body<GameDto>().gameId
    advanceAndTick(GameRules.PREPARATION_MILLIS)
    val game = players.first().game(gameId)
    check(game.status == GameStatus.PLAYING) { "경기가 시작되지 않았습니다: ${game.status}" }
    val roleOf = game.players.associate { it.userId to it.role }
    return StartedGame(
        gameId = gameId,
        police = players.filter { roleOf[it.userId] == PlayerRole.POLICE },
        thieves = players.filter { roleOf[it.userId] == PlayerRole.THIEF },
        game = game,
    )
}
