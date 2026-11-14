package com.nettwork.policethief.server.game

import com.nettwork.policethief.server.common.ConflictException
import com.nettwork.policethief.server.common.ForbiddenException
import com.nettwork.policethief.server.common.GameLock
import com.nettwork.policethief.server.common.NotFoundException
import com.nettwork.policethief.server.common.ValidationException
import com.nettwork.policethief.server.database.AppDatabase
import com.nettwork.policethief.server.game.rules.advanceTo
import com.nettwork.policethief.server.game.rules.applyLocation
import com.nettwork.policethief.server.game.rules.capture
import com.nettwork.policethief.server.game.rules.forfeit
import com.nettwork.policethief.server.game.rules.markDisconnected
import com.nettwork.policethief.server.game.rules.markReconnected
import com.nettwork.policethief.server.game.rules.rescue
import com.nettwork.policethief.server.game.rules.ringThiefPhone
import com.nettwork.policethief.server.location.LocationIngest
import com.nettwork.policethief.server.location.insertLocationRow
import com.nettwork.policethief.server.matchmaking.MatchmakingService
import com.nettwork.policethief.server.notification.NotificationService
import com.nettwork.policethief.server.notification.Outbox
import com.nettwork.policethief.shared.dto.GameDto
import com.nettwork.policethief.shared.dto.GameEventDto
import com.nettwork.policethief.shared.dto.GameResultDto
import com.nettwork.policethief.shared.dto.GameStateDto
import com.nettwork.policethief.shared.dto.LocationDto
import com.nettwork.policethief.shared.dto.LocationUpdateRequest
import com.nettwork.policethief.shared.dto.QrScanRequest
import com.nettwork.policethief.shared.dto.QrScanResultDto
import com.nettwork.policethief.shared.dto.QrTokenDto
import com.nettwork.policethief.shared.dto.RingThiefRequest
import com.nettwork.policethief.shared.dto.RingThiefResultDto
import com.nettwork.policethief.shared.dto.ServerMessage
import com.nettwork.policethief.shared.dto.VisibleLocationsResponse
import com.nettwork.policethief.shared.model.PlayerConnectionState
import com.nettwork.policethief.shared.model.PlayerRole
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

/** WebSocket 연결 변화를 경기 상태에 반영하기 위한 통로(RealtimeRoutes 가 호출). */
interface PresenceListener {
    suspend fun onUserConnected(userId: UUID)
    suspend fun onUserDisconnected(userId: UUID)
    suspend fun activeGameIdOf(userId: UUID): UUID?
}

/**
 * 경기 진행의 중심(Game Management). 모든 판정을 서버에서 확정한다.
 *
 * 처리 순서(모든 요청 공통)
 *  1. GameLock 획득 → 2. DB 트랜잭션 → 3. 경기를 GameSession 으로 불러옴
 *  4. advanceTo(now): 지난 예정 판정(시작·종료·축소·미션·유예)을 시간 순서대로 먼저 적용
 *  5. 요청 처리(rules/) → 6. 저장(버전 증가 + 이벤트) → 7. 커밋 후 WebSocket 전달
 * 4번 덕분에 "제한 시간이 지난 뒤 들어온 체포"는 항상 거절되고, 서버 재시작·시간 이동 후에도 같은 결과가 나온다.
 */
class GameEngine(
    private val db: AppDatabase,
    private val lock: GameLock,
    private val store: GameStore,
    private val matchmaking: MatchmakingService,
    private val notifications: NotificationService,
    private val clock: Clock,
    private val settings: GameSettings,
    private val mockLocationsExemptFromSpeed: Boolean,
    private val isUserConnected: (UUID) -> Boolean,
    private val random: Random = Random.Default,
) : LocationIngest, PresenceListener {
    private val log = LoggerFactory.getLogger(GameEngine::class.java)
    private var lastCleanupAt: Instant = Instant.EPOCH

    // ---------------- 시간 기반 판정 ----------------

    /** 스케줄러가 약 1초마다 호출: 매칭 만료·재시도, 모든 진행 중 경기의 예정 판정, 오래된 위치 정리. */
    suspend fun tick() = mutate { now, outbox ->
        matchmaking.tickLocked(now, outbox)
        store.activeGameIds().forEach { gameId ->
            val session = load(gameId) ?: return@forEach
            session.advanceTo(now)
            persist(session, outbox, now)
        }
        if (now.toEpochMilli() - lastCleanupAt.toEpochMilli() >= CLEANUP_INTERVAL_MILLIS) {
            val deleted = store.deleteLocationsBefore(now.minusMillis(settings.locationRetentionMillis))
            if (deleted > 0) log.info("보관 기간이 지난 위치 기록 {}건 삭제", deleted)
            lastCleanupAt = now
        }
    }

    /**
     * 서버 시작 직후 1회: 꺼져 있던 동안 지난 판정을 처리하고, 아직 다시 접속하지 않은 참가자에게 재접속 유예(3분)를 시작한다.
     * (서버가 꺼진 동안의 연결 상태는 알 수 없으므로 "지금 끊김"으로 본다.)
     */
    suspend fun recoverAfterRestart() = mutate { now, outbox ->
        store.activeGameIds().forEach { gameId ->
            val session = load(gameId) ?: return@forEach
            session.advanceTo(now)
            session.players
                .filter { it.connection == PlayerConnectionState.CONNECTED && !isUserConnected(it.userId) }
                .forEach { session.markDisconnected(it.userId, now) }
            persist(session, outbox, now)
        }
    }

    // ---------------- 조회 ----------------

    suspend fun currentGame(userId: UUID): GameDto = mutate { now, outbox ->
        val gameId = store.activeGameIdOf(userId) ?: throw NotFoundException("진행 중인 경기가 없습니다.", mapOf("reason" to "NO_ACTIVE_GAME"))
        val session = loadForParticipant(userId, gameId)
        session.advanceTo(now)
        persist(session, outbox, now)
        session.toDto(userId, now)
    }

    suspend fun getGame(userId: UUID, gameId: UUID): GameDto = mutate { now, outbox ->
        val session = loadForParticipant(userId, gameId)
        session.advanceTo(now)
        persist(session, outbox, now)
        session.toDto(userId, now)
    }

    /** 재접속·앱 재실행용 전체 상태. WebSocket 과 같은 공개 규칙(위치·미션·이벤트)으로 걸러진다. */
    suspend fun state(userId: UUID, gameId: UUID): GameStateDto = mutate { now, outbox ->
        val session = loadForParticipant(userId, gameId)
        session.advanceTo(now)
        persist(session, outbox, now)
        val role = session.player(userId)!!.role
        GameStateDto(
            game = session.toDto(userId, now),
            locations = if (session.isEnded) emptyList() else session.visibleLocationsFor(userId).map { it.toDto() },
            recentEvents = store.events(gameId, userId, role, 0, Int.MAX_VALUE).takeLast(RECENT_EVENTS),
            lastEventId = store.lastEventId(gameId),
        )
    }

    /** 역할별로 걸러진 위치. 경기가 끝나면 다른 참가자의 위치를 더는 볼 수 없다. */
    suspend fun visibleLocations(userId: UUID, gameId: UUID): VisibleLocationsResponse = mutate { now, outbox ->
        val session = loadForParticipant(userId, gameId)
        session.advanceTo(now)
        persist(session, outbox, now)
        if (session.isEnded) throw ConflictException("종료된 경기의 위치는 볼 수 없습니다.", "GAME_ENDED")
        VisibleLocationsResponse(gameId.toString(), session.visibleLocationsFor(userId).map { it.toDto() }, now.toEpochMilli())
    }

    suspend fun events(userId: UUID, gameId: UUID, afterEventId: Long): List<GameEventDto> = db.query {
        val session = loadForParticipant(userId, gameId)
        store.events(gameId, userId, session.player(userId)!!.role, afterEventId, MAX_EVENTS_PER_REQUEST)
    }

    /** 내 QR(도둑만). 경기별 고정 토큰이며 경기가 끝나면 폐기되어 조회할 수 없다. */
    suspend fun myQr(userId: UUID, gameId: UUID): QrTokenDto = mutate { now, outbox ->
        val session = loadForParticipant(userId, gameId)
        session.advanceTo(now)
        persist(session, outbox, now)
        if (session.player(userId)!!.role != PlayerRole.THIEF) throw ForbiddenException("경찰에게는 QR 이 없습니다.", mapOf("reason" to "POLICE_HAS_NO_QR"))
        if (session.isEnded) throw ConflictException("종료된 경기의 QR 은 더 이상 유효하지 않습니다.", "GAME_ENDED")
        store.qrTokenOf(gameId, userId) ?: throw NotFoundException("QR 토큰을 찾을 수 없습니다.")
    }

    suspend fun result(userId: UUID, gameId: UUID): GameResultDto = mutate { now, outbox ->
        val session = loadForParticipant(userId, gameId)
        session.advanceTo(now)
        persist(session, outbox, now)
        store.resultOf(session)
    }

    // ---------------- 행동 ----------------

    suspend fun capture(userId: UUID, gameId: UUID, request: QrScanRequest): QrScanResultDto =
        scan(userId, gameId, request, KIND_CAPTURE) { session, token, now -> session.capture(userId, token, now) }

    suspend fun rescue(userId: UUID, gameId: UUID, request: QrScanRequest): QrScanResultDto =
        scan(userId, gameId, request, KIND_RESCUE) { session, token, now -> session.rescue(userId, token, now) }

    /**
     * 체포·구출 공통 처리. 같은 (사용자, requestId) 는 한 번만 판정하고 이후에는 처음 결과를 돌려준다
     * (네트워크가 끊겨 앱이 재전송해도 두 번 체포되지 않는다).
     */
    private suspend fun scan(
        userId: UUID,
        gameId: UUID,
        request: QrScanRequest,
        kind: String,
        judge: (GameSession, com.nettwork.policethief.server.game.rules.ScannedToken, Instant) -> QrScanResultDto,
    ): QrScanResultDto = mutate { now, outbox ->
        if (request.requestId.isBlank() || request.requestId.length > MAX_REQUEST_ID_LENGTH) {
            throw ValidationException("requestId 는 1~$MAX_REQUEST_ID_LENGTH 자여야 합니다.", mapOf("requestId" to "INVALID"))
        }
        store.findScanResult(userId, request.requestId)?.let { (previousGameId, previous) ->
            if (previousGameId != gameId) throw ValidationException("다른 경기에서 이미 사용한 requestId 입니다.", mapOf("requestId" to "REUSED"))
            return@mutate previous
        }
        val session = loadForParticipant(userId, gameId)
        session.advanceTo(now)
        val result = judge(session, store.lookupToken(request.token, gameId), now)
        persist(session, outbox, now)
        store.saveScanResult(userId, request.requestId, gameId, kind, result, now)
        result
    }

    /** 명시적 기권 → 즉시 무효. 이미 끝난 경기라면 결과는 바뀌지 않는다. */
    suspend fun forfeit(userId: UUID, gameId: UUID): GameDto = mutate { now, outbox ->
        val session = loadForParticipant(userId, gameId)
        session.advanceTo(now)
        session.forfeit(userId, now)
        persist(session, outbox, now)
        session.toDto(userId, now)
    }

    suspend fun ring(userId: UUID, gameId: UUID, request: RingThiefRequest): RingThiefResultDto = mutate { now, outbox ->
        val target = runCatching { UUID.fromString(request.targetUserId) }.getOrNull()
            ?: throw ValidationException("targetUserId 형식이 올바르지 않습니다.", mapOf("targetUserId" to request.targetUserId))
        val session = loadForParticipant(userId, gameId)
        session.advanceTo(now)
        val result = session.ringThiefPhone(userId, target, now)
        persist(session, outbox, now)
        result
    }

    /**
     * POST /api/locations 의 실제 처리. 위치를 저장하고,
     * - 경기 중이면: 과속·구역·감옥·미션 판정 후 공개 범위에 맞는 참가자에게 새 위치를 알린다.
     * - 매칭 대기 중이면: 대기열 위치를 갱신하고 매칭을 다시 시도한다.
     */
    override suspend fun ingest(userId: UUID, request: LocationUpdateRequest, receivedAt: Instant): LocationDto = mutate { now, outbox ->
        val gameId = store.activeGameIdOf(userId)
        // 경기 상태(마지막 위치 포함)는 새 위치를 저장하기 전에 불러온다. 저장 후에 불러오면 새 위치가 "이전 위치"로 읽혀 중복으로 무시된다.
        val session = gameId?.let { load(it) }
        val stored = insertLocationRow(userId, gameId, request, now)
        if (gameId == null) {
            matchmaking.onWaiterLocationLocked(userId, request, now, outbox)
        } else {
            if (session == null) return@mutate stored
            session.advanceTo(now)
            val sample = LocationSample(userId, request.latitude, request.longitude, request.accuracyMeters, request.measuredAt(), now, request.source)
            val used = session.applyLocation(sample, now)
            persist(session, outbox, now)
            if (used && !session.isEnded) publishLocation(session, sample, outbox)
        }
        stored
    }

    // ---------------- 연결 상태 ----------------

    override suspend fun onUserConnected(userId: UUID) {
        mutate { now, outbox ->
            val gameId = store.activeGameIdOf(userId) ?: return@mutate
            val session = load(gameId) ?: return@mutate
            session.advanceTo(now)
            session.markReconnected(userId, now)
            persist(session, outbox, now)
        }
    }

    override suspend fun onUserDisconnected(userId: UUID) {
        mutate { now, outbox ->
            val gameId = store.activeGameIdOf(userId) ?: return@mutate
            val session = load(gameId) ?: return@mutate
            session.advanceTo(now)
            session.markDisconnected(userId, now)
            persist(session, outbox, now)
        }
    }

    override suspend fun activeGameIdOf(userId: UUID): UUID? = db.query { store.activeGameIdOf(userId) }

    /** 개발자 도구용: 경기 참가자 목록과 도둑 봇의 QR(사람의 QR 은 돌려주지 않는다). */
    suspend fun participants(gameId: UUID): List<PlayerState> = db.query { load(gameId)?.players.orEmpty() }

    suspend fun qrTokenOf(gameId: UUID, userId: UUID): String? = db.query { store.qrTokenOf(gameId, userId)?.token }

    // ---------------- 내부 ----------------

    private suspend fun <T> mutate(block: (Instant, Outbox) -> T): T {
        val outbox = Outbox()
        val result = lock.withLock { db.query { block(clock.instant(), outbox) } }
        notifications.deliver(outbox)
        return result
    }

    private fun load(gameId: UUID): GameSession? = store.loadSession(gameId, settings, mockLocationsExemptFromSpeed, random)

    /** 경기가 없으면 404, 참가자가 아니면 403. 다른 사람의 경기 정보는 볼 수도 바꿀 수도 없다. */
    private fun loadForParticipant(userId: UUID, gameId: UUID): GameSession {
        val session = load(gameId) ?: throw NotFoundException("경기를 찾을 수 없습니다.")
        if (session.player(userId) == null) throw ForbiddenException("이 경기의 참가자가 아닙니다.", mapOf("reason" to "NOT_A_PARTICIPANT"))
        return session
    }

    /** 저장 + 공개 범위에 맞춰 이벤트 전달 예약. */
    private fun persist(session: GameSession, outbox: Outbox, now: Instant) {
        val saved = store.save(session, now)
        saved.forEach { (recorded, dto) ->
            session.recipientsOf(recorded).forEach { outbox.add(it, ServerMessage.GameEventPush(dto)) }
        }
        session.transientEvents.forEach { recorded ->
            val dto = GameEventDto(0, session.core.id.toString(), recorded.type, session.core.version, recorded.data, recorded.at.toEpochMilli())
            session.recipientsOf(recorded).forEach { outbox.add(it, ServerMessage.GameEventPush(dto)) }
        }
        session.transientEvents.clear()
    }

    /** 새 위치는 볼 권한이 있는 참가자에게만 보낸다(경찰 위치는 도둑에게 가지 않는다). */
    private fun publishLocation(session: GameSession, sample: LocationSample, outbox: Outbox) {
        val ownerRole = session.player(sample.userId)?.role ?: return
        val message = ServerMessage.PlayerLocation(session.core.id.toString(), sample.toDto())
        session.players
            .filter { it.userId != sample.userId && GameSession.canSee(it.role, ownerRole) }
            .forEach { outbox.add(it.userId, message) }
    }

    private fun LocationUpdateRequest.measuredAt(): Instant = Instant.ofEpochMilli(measuredAtEpochMillis)

    private companion object {
        const val KIND_CAPTURE = "CAPTURE"
        const val KIND_RESCUE = "RESCUE"
        const val MAX_REQUEST_ID_LENGTH = 64
        const val RECENT_EVENTS = 30
        const val MAX_EVENTS_PER_REQUEST = 200
        const val CLEANUP_INTERVAL_MILLIS = 10 * 60_000L
    }
}
