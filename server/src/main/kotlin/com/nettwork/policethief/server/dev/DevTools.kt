package com.nettwork.policethief.server.dev

import com.nettwork.policethief.server.auth.AuthService
import com.nettwork.policethief.server.auth.requireUser
import com.nettwork.policethief.server.common.ConflictException
import com.nettwork.policethief.server.common.ForbiddenException
import com.nettwork.policethief.server.common.NotFoundException
import com.nettwork.policethief.server.common.ValidationException
import com.nettwork.policethief.server.game.GameEngine
import com.nettwork.policethief.server.game.gameId
import com.nettwork.policethief.server.game.uuidParameter
import com.nettwork.policethief.server.location.LocationService
import com.nettwork.policethief.server.matchmaking.MatchmakingService
import com.nettwork.policethief.shared.api.ApiPaths
import com.nettwork.policethief.shared.dto.DevBotDto
import com.nettwork.policethief.shared.dto.DevBotLocationRequest
import com.nettwork.policethief.shared.dto.DevBotPresenceRequest
import com.nettwork.policethief.shared.dto.DevBotScanRequest
import com.nettwork.policethief.shared.dto.DevBotsResponse
import com.nettwork.policethief.shared.dto.DevClockAdvanceRequest
import com.nettwork.policethief.shared.dto.DevCreateBotsRequest
import com.nettwork.policethief.shared.dto.DevStatusDto
import com.nettwork.policethief.shared.dto.JoinMatchmakingRequest
import com.nettwork.policethief.shared.dto.LocationUpdateRequest
import com.nettwork.policethief.shared.dto.QrScanRequest
import com.nettwork.policethief.shared.dto.QrScanResultDto
import com.nettwork.policethief.shared.geo.GeoMath
import com.nettwork.policethief.shared.geo.GeoPoint
import com.nettwork.policethief.shared.model.LocationSource
import com.nettwork.policethief.shared.model.PlayerRole
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI

/**
 * 개발자 도구용 시계. 실제 시계에 "앞으로 감은 시간(offset)"을 더한다.
 * PNT_DEV_TOOLS=true 인 서버에서만 사용되며, 게임 판정 코드는 이 시계를 일반 시계와 똑같이 사용한다.
 */
class DevClock(private val base: Clock = Clock.systemUTC()) : Clock() {
    private val offset = AtomicLong(0)
    val offsetMillis: Long get() = offset.get()

    fun advance(millis: Long) {
        offset.addAndGet(millis)
    }

    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = base.instant().plusMillis(offset.get())
}

/**
 * 개발자 테스트 도구(지시서 28장). 휴대폰 1대로 4인 경기 전체를 진행해 보기 위한 "봇" 참가자.
 * 봇의 매칭·이동·스캔은 일반 사용자와 같은 서비스(MatchmakingService, LocationService, GameEngine)를 거치므로
 * 판정을 건너뛰지 않는다. 봇 목록은 메모리에만 있어 서버를 재시작하면 사라진다.
 */
class DevToolsService(
    private val auth: AuthService,
    private val matchmaking: MatchmakingService,
    private val locations: LocationService,
    private val engine: GameEngine,
    private val clock: DevClock,
) {
    private val bots = ConcurrentHashMap<UUID, String>()
    private val botCounter = java.util.concurrent.atomic.AtomicInteger(0)

    fun status() = DevStatusDto(enabled = true, clockOffsetMillis = clock.offsetMillis, serverTimeEpochMillis = clock.millis())

    /** 요청자 주변(기본: 요청자의 마지막 위치)에 봇을 만들고 매칭 대기열에 넣는다. */
    suspend fun createBots(requesterId: UUID, request: DevCreateBotsRequest): DevBotsResponse {
        ensureHuman(requesterId)
        if (request.count !in 1..3) throw ValidationException("봇은 1~3명까지 만들 수 있습니다.", mapOf("count" to request.count.toString()))
        if (request.spreadMeters !in 0.0..200.0) throw ValidationException("spreadMeters 는 0~200m 입니다.", mapOf("spreadMeters" to request.spreadMeters.toString()))
        val latitude = request.latitude
        val longitude = request.longitude
        val anchor = if (latitude != null && longitude != null) {
            GeoPoint(latitude, longitude)
        } else {
            val latest = runCatching { locations.latest(requesterId) }.getOrNull()
                ?: throw ConflictException("먼저 내 위치를 한 번 전송하거나 좌표를 지정하세요.", "NO_ANCHOR_LOCATION")
            GeoPoint(latest.latitude, latest.longitude)
        }
        val created = (1..request.count).map { index ->
            val login = auth.guestLogin("봇${botCounter.incrementAndGet() % 1000}")
            val botId = UUID.fromString(login.user.userId)
            bots[botId] = login.accessToken
            val point = GeoMath.destination(anchor, request.spreadMeters, 2 * PI * index / request.count)
            matchmaking.join(botId, JoinMatchmakingRequest(mockLocation(point, 5.0)))
            DevBotDto(botId.toString(), login.user.nickname)
        }
        return DevBotsResponse(created)
    }

    suspend fun moveBot(requesterId: UUID, botId: UUID, request: DevBotLocationRequest) {
        ensureHuman(requesterId)
        ensureBot(botId)
        locations.submit(botId, mockLocation(GeoPoint(request.latitude, request.longitude), request.accuracyMeters))
    }

    /** 봇이 QR 을 스캔: 봇이 경찰이면 체포, 도둑이면 구출로 처리한다. */
    suspend fun botScan(requesterId: UUID, botId: UUID, request: DevBotScanRequest): QrScanResultDto {
        ensureHuman(requesterId)
        ensureBot(botId)
        val gameId = engine.activeGameIdOf(botId) ?: throw ConflictException("봇이 진행 중인 경기에 없습니다.", "BOT_NOT_IN_GAME")
        val role = engine.participants(gameId).first { it.userId == botId }.role
        val scan = QrScanRequest(request.token, "dev-${UUID.randomUUID()}", clock.millis())
        return if (role == PlayerRole.POLICE) engine.capture(botId, gameId, scan) else engine.rescue(botId, gameId, scan)
    }

    suspend fun botPresence(requesterId: UUID, botId: UUID, request: DevBotPresenceRequest) {
        ensureHuman(requesterId)
        ensureBot(botId)
        if (request.connected) engine.onUserConnected(botId) else engine.onUserDisconnected(botId)
    }

    /** 요청자가 참가한 경기의 봇 정보. 도둑 봇의 QR 만 포함한다(사람 참가자의 QR 은 개발 모드에서도 내려주지 않는다). */
    suspend fun gameBots(requesterId: UUID, gameId: UUID): DevBotsResponse {
        ensureHuman(requesterId)
        val players = engine.participants(gameId)
        if (players.none { it.userId == requesterId }) throw ForbiddenException("이 경기의 참가자가 아닙니다.", mapOf("reason" to "NOT_A_PARTICIPANT"))
        return DevBotsResponse(
            players.filter { bots.containsKey(it.userId) }.map { bot ->
                DevBotDto(
                    userId = bot.userId.toString(),
                    nickname = bot.nickname,
                    role = bot.role,
                    status = bot.status,
                    qrToken = if (bot.role == PlayerRole.THIEF) engine.qrTokenOf(gameId, bot.userId) else null,
                )
            },
        )
    }

    suspend fun advanceClock(requesterId: UUID, request: DevClockAdvanceRequest): DevStatusDto {
        ensureHuman(requesterId)
        if (request.millis !in 1..MAX_ADVANCE_MILLIS) throw ValidationException("1ms ~ 1시간까지 앞으로 돌릴 수 있습니다.", mapOf("millis" to request.millis.toString()))
        clock.advance(request.millis)
        engine.tick()
        return status()
    }

    private fun mockLocation(point: GeoPoint, accuracy: Double) =
        LocationUpdateRequest(point.latitude, point.longitude, accuracy, clock.millis(), LocationSource.MOCK)

    private fun ensureHuman(userId: UUID) {
        if (bots.containsKey(userId)) throw ForbiddenException("봇 계정으로는 개발자 도구를 쓸 수 없습니다.")
    }

    private fun ensureBot(botId: UUID) {
        if (!bots.containsKey(botId)) throw NotFoundException("개발자 도구로 만든 봇이 아닙니다.")
    }

    private companion object {
        const val MAX_ADVANCE_MILLIS = 60 * 60_000L
    }
}

/** /api/dev/... 경로. PNT_DEV_TOOLS=true 일 때만 등록되므로 운영 서버에는 존재하지 않는다(404). */
fun Route.devToolRoutes(dev: DevToolsService) {
    get(ApiPaths.DEV_STATUS) { call.respond(dev.status()) }
    get(ApiPaths.DEV_CLOCK) { call.respond(dev.status()) }
    post(ApiPaths.DEV_CLOCK_ADVANCE) {
        call.respond(dev.advanceClock(call.requireUser().userId, call.receive<DevClockAdvanceRequest>()))
    }
    post(ApiPaths.DEV_BOTS) {
        call.respond(HttpStatusCode.Created, dev.createBots(call.requireUser().userId, call.receive<DevCreateBotsRequest>()))
    }
    post(ApiPaths.devBotLocation("{botId}")) {
        dev.moveBot(call.requireUser().userId, call.uuidParameter("botId"), call.receive<DevBotLocationRequest>())
        call.respond(HttpStatusCode.NoContent)
    }
    post(ApiPaths.devBotScan("{botId}")) {
        call.respond(dev.botScan(call.requireUser().userId, call.uuidParameter("botId"), call.receive<DevBotScanRequest>()))
    }
    post(ApiPaths.devBotPresence("{botId}")) {
        dev.botPresence(call.requireUser().userId, call.uuidParameter("botId"), call.receive<DevBotPresenceRequest>())
        call.respond(HttpStatusCode.NoContent)
    }
    get(ApiPaths.devGameBots("{gameId}")) {
        call.respond(dev.gameBots(call.requireUser().userId, call.gameId()))
    }
}
