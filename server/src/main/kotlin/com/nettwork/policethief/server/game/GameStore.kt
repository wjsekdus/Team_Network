package com.nettwork.policethief.server.game

import com.nettwork.policethief.server.common.toUtcOffsetDateTime
import com.nettwork.policethief.server.database.ActiveParticipationsTable
import com.nettwork.policethief.server.database.EscortsTable
import com.nettwork.policethief.server.database.GameEventsTable
import com.nettwork.policethief.server.database.GamePlayersTable
import com.nettwork.policethief.server.database.GameResultsTable
import com.nettwork.policethief.server.database.GameZoneStagesTable
import com.nettwork.policethief.server.database.GamesTable
import com.nettwork.policethief.server.database.MissionsTable
import com.nettwork.policethief.server.database.PlayerLocationsTable
import com.nettwork.policethief.server.database.QrTokensTable
import com.nettwork.policethief.server.database.ScanRequestsTable
import com.nettwork.policethief.server.database.UsersTable
import com.nettwork.policethief.server.game.rules.ScannedToken
import com.nettwork.policethief.server.location.insertLocationRow
import com.nettwork.policethief.shared.api.ApiJson
import com.nettwork.policethief.shared.dto.GameEventDto
import com.nettwork.policethief.shared.dto.GameResultDto
import com.nettwork.policethief.shared.dto.LocationUpdateRequest
import com.nettwork.policethief.shared.dto.PlayerResultDto
import com.nettwork.policethief.shared.dto.QrScanResultDto
import com.nettwork.policethief.shared.dto.QrTokenDto
import com.nettwork.policethief.shared.geo.GeoMath
import com.nettwork.policethief.shared.geo.GeoPoint
import com.nettwork.policethief.shared.model.GameEndReason
import com.nettwork.policethief.shared.model.GameEventKeys
import com.nettwork.policethief.shared.model.GameEventType
import com.nettwork.policethief.shared.model.GameResult
import com.nettwork.policethief.shared.model.GameStatus
import com.nettwork.policethief.shared.model.LocationSource
import com.nettwork.policethief.shared.model.MissionStatus
import com.nettwork.policethief.shared.model.PlayerConnectionState
import com.nettwork.policethief.shared.model.PlayerRole
import com.nettwork.policethief.shared.model.PlayerStatus
import com.nettwork.policethief.shared.rules.GameRules
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.random.Random

/** 매칭이 확정된 참가자 한 명(대기열에서 읽은 위치 포함). */
data class MatchMember(
    val userId: UUID,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val source: LocationSource,
    val locatedAt: Instant,
)

/** 새로 만든 경기와 그 준비(GAME_PREPARING) 이벤트. */
data class CreatedGame(val gameId: UUID, val preparingEvent: GameEventDto)

/**
 * 경기 데이터 접근(Repository). 모든 함수는 이미 열린 DB 트랜잭션 안에서 호출해야 한다(GameEngine 이 연다).
 * SQL 은 Exposed DSL 로만 만들어 파라미터 바인딩을 사용한다(SQL Injection 방지).
 */
class GameStore(private val secureRandom: SecureRandom = SecureRandom()) {

    // ---------------- 조회 ----------------

    fun activeGameIdOf(userId: UUID): UUID? =
        ActiveParticipationsTable.selectAll().where { ActiveParticipationsTable.userId eq userId }.singleOrNull()?.get(ActiveParticipationsTable.gameId)

    fun activeGameIds(): List<UUID> =
        GamesTable.selectAll()
            .where { GamesTable.status inList listOf(GameStatus.READY.name, GameStatus.PLAYING.name) }
            .map { it[GamesTable.id] }

    fun loadSession(gameId: UUID, settings: GameSettings, mockExempt: Boolean, random: Random): GameSession? {
        val gameRow = GamesTable.selectAll().where { GamesTable.id eq gameId }.singleOrNull() ?: return null
        val players = (GamePlayersTable innerJoin UsersTable).selectAll()
            .where { GamePlayersTable.gameId eq gameId }
            .orderBy(GamePlayersTable.joinedAt to SortOrder.ASC, GamePlayersTable.userId to SortOrder.ASC)
            .map { it.toPlayer() }
        val stages = GameZoneStagesTable.selectAll().where { GameZoneStagesTable.gameId eq gameId }.map {
            ZoneStage(
                stageNo = it[GameZoneStagesTable.stageNo],
                scheduledAt = it[GameZoneStagesTable.scheduledAt].toInstant(),
                radiusMeters = it[GameZoneStagesTable.radiusMeters],
                warnedAt = it[GameZoneStagesTable.warnedAt]?.toInstant(),
                appliedAt = it[GameZoneStagesTable.appliedAt]?.toInstant(),
            )
        }
        val missions = MissionsTable.selectAll().where { MissionsTable.gameId eq gameId }.orderBy(MissionsTable.startedAt).map {
            Mission(
                id = it[MissionsTable.id],
                target = GeoPoint(it[MissionsTable.targetLatitude], it[MissionsTable.targetLongitude]),
                radiusMeters = it[MissionsTable.targetRadiusMeters],
                startedAt = it[MissionsTable.startedAt].toInstant(),
                deadlineAt = it[MissionsTable.deadlineAt].toInstant(),
                status = MissionStatus.valueOf(it[MissionsTable.status]),
                completedBy = it[MissionsTable.completedBy],
                endedAt = it[MissionsTable.endedAt]?.toInstant(),
            )
        }
        val latest = players.mapNotNull { latestSample(gameId, it.userId) }.associateBy { it.userId }
        return GameSession(gameRow.toCore(), players, stages, missions, latest, settings, mockExempt, random)
    }

    /** 그 경기에서 측정 시각이 가장 늦은 위치(같으면 늦게 들어온 것). */
    private fun latestSample(gameId: UUID, userId: UUID): LocationSample? =
        PlayerLocationsTable.selectAll()
            .where { (PlayerLocationsTable.gameId eq gameId) and (PlayerLocationsTable.userId eq userId) }
            .orderBy(PlayerLocationsTable.measuredAt to SortOrder.DESC, PlayerLocationsTable.id to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.let {
                LocationSample(
                    userId = userId,
                    latitude = it[PlayerLocationsTable.latitude],
                    longitude = it[PlayerLocationsTable.longitude],
                    accuracyMeters = it[PlayerLocationsTable.accuracyMeters],
                    measuredAt = it[PlayerLocationsTable.measuredAt].toInstant(),
                    receivedAt = it[PlayerLocationsTable.receivedAt].toInstant(),
                    source = LocationSource.valueOf(it[PlayerLocationsTable.locationSource]),
                )
            }

    // ---------------- 경기 생성 ----------------

    /**
     * 매칭 확정: 경기·참가자(무작위 2:2)·진행 중 참가 기록·도둑 QR·구역 축소 일정·최초 위치·준비 이벤트를 한 트랜잭션에서 만든다.
     * active_participations 기본키 때문에 이미 다른 경기에 있는 사용자가 섞이면 예외가 나고 전체가 취소된다.
     */
    fun createGame(members: List<MatchMember>, now: Instant, settings: GameSettings): CreatedGame {
        require(members.size == GameRules.PLAYERS_PER_GAME) { "경기 인원은 ${GameRules.PLAYERS_PER_GAME}명이어야 합니다." }
        val center = GeoMath.centroid(members.map { GeoPoint(it.latitude, it.longitude) })
        val gameId = UUID.randomUUID()
        val startsAt = now.plusMillis(GameRules.PREPARATION_MILLIS)
        val endsAt = startsAt.plusMillis(GameRules.GAME_DURATION_MILLIS)
        GamesTable.insert {
            it[id] = gameId
            it[status] = GameStatus.READY.name
            it[version] = 1
            it[centerLatitude] = center.latitude
            it[centerLongitude] = center.longitude
            it[initialRadiusMeters] = GameRules.INITIAL_ZONE_RADIUS_METERS
            it[currentRadiusMeters] = GameRules.INITIAL_ZONE_RADIUS_METERS
            it[jailRadiusMeters] = GameRules.JAIL_RADIUS_METERS
            it[createdAt] = now.toUtcOffsetDateTime()
            it[GamesTable.startsAt] = startsAt.toUtcOffsetDateTime()
            it[GamesTable.endsAt] = endsAt.toUtcOffsetDateTime()
        }
        // 역할 배정은 경기 생성 시 단 한 번. 예측할 수 없도록 SecureRandom 으로 섞는다.
        val shuffled = members.shuffled(java.util.Random(secureRandom.nextLong()))
        shuffled.forEachIndexed { index, member ->
            val role = if (index < GameRules.POLICE_COUNT) PlayerRole.POLICE else PlayerRole.THIEF
            GamePlayersTable.insert {
                it[GamePlayersTable.gameId] = gameId
                it[userId] = member.userId
                it[GamePlayersTable.role] = role.name
                it[status] = PlayerStatus.ACTIVE.name
                it[warningCount] = 0
                it[connectionState] = PlayerConnectionState.CONNECTED.name
                it[speedCandidateCount] = 0
                it[joinedAt] = now.toUtcOffsetDateTime()
            }
            ActiveParticipationsTable.insert {
                it[userId] = member.userId
                it[ActiveParticipationsTable.gameId] = gameId
                it[joinedAt] = now.toUtcOffsetDateTime()
            }
            if (role == PlayerRole.THIEF) {
                QrTokensTable.insert {
                    it[id] = UUID.randomUUID()
                    it[QrTokensTable.gameId] = gameId
                    it[userId] = member.userId
                    it[token] = newQrToken()
                    it[createdAt] = now.toUtcOffsetDateTime()
                }
            }
            // 매칭에 쓴 위치를 이 경기의 최초 위치로 남긴다(구역 확인 화면·첫 판정용).
            insertLocationRow(
                member.userId,
                gameId,
                LocationUpdateRequest(member.latitude, member.longitude, member.accuracyMeters, member.locatedAt.toEpochMilli(), member.source),
                now,
            )
        }
        settings.zoneShrinkStages
            .filter { it.offsetMillis < GameRules.GAME_DURATION_MILLIS }
            .forEachIndexed { index, plan ->
                GameZoneStagesTable.insert {
                    it[GameZoneStagesTable.gameId] = gameId
                    it[stageNo] = index + 1
                    it[scheduledAt] = startsAt.plusMillis(plan.offsetMillis).toUtcOffsetDateTime()
                    it[radiusMeters] = plan.radiusMeters
                }
            }
        val event = insertEvent(
            gameId,
            RecordedEvent(
                GameEventType.GAME_PREPARING,
                Visibility.ALL,
                null,
                eventData(GameEventKeys.AT_EPOCH_MILLIS to startsAt),
                now,
            ),
            version = 1,
        )
        return CreatedGame(gameId, event)
    }

    private fun newQrToken(): String {
        val bytes = ByteArray(QR_TOKEN_BYTES).also(secureRandom::nextBytes)
        return QR_TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    // ---------------- 저장 ----------------

    /**
     * 세션의 변경을 저장한다. 바뀐 것이 있으면 경기 버전을 1 올리고, 기록된 이벤트를 그 버전으로 저장한다.
     * 이번 처리에서 경기가 끝났다면 종료 부수 효과(QR 폐기, 참가 기록 삭제, 호송 종료, 결과 저장)를 함께 한다.
     * @return 저장된 이벤트(전달용).
     */
    fun save(session: GameSession, now: Instant): List<Pair<RecordedEvent, GameEventDto>> {
        if (!session.changed && session.events.isEmpty()) return emptyList()
        val version = session.core.version + 1
        session.updateCore { copy(version = version) }
        val core = session.core
        GamesTable.update({ GamesTable.id eq core.id }) {
            it[status] = core.status.name
            it[GamesTable.version] = core.version
            it[currentRadiusMeters] = core.currentRadiusMeters
            it[startedAt] = core.startedAt?.toUtcOffsetDateTime()
            it[endedAt] = core.endedAt?.toUtcOffsetDateTime()
            it[result] = core.result?.name
            it[endReason] = core.endReason?.name
            it[nextMissionAt] = core.nextMissionAt?.toUtcOffsetDateTime()
        }
        session.players.forEach { p ->
            GamePlayersTable.update({ (GamePlayersTable.gameId eq core.id) and (GamePlayersTable.userId eq p.userId) }) {
                it[status] = p.status.name
                it[capturedBy] = p.capturedBy
                it[escortingUserId] = p.escortingUserId
                it[warningCount] = p.warningCount
                it[connectionState] = p.connection.name
                it[disconnectedAt] = p.disconnectedAt?.toUtcOffsetDateTime()
                it[outOfZoneSince] = p.outOfZoneSince?.toUtcOffsetDateTime()
                it[speedCandidateCount] = p.speedCandidateCount
                it[lastSpeedWarningAt] = p.lastSpeedWarningAt?.toUtcOffsetDateTime()
                it[lastRingAt] = p.lastRingAt?.toUtcOffsetDateTime()
            }
        }
        session.stages.forEach { s ->
            GameZoneStagesTable.update({ (GameZoneStagesTable.gameId eq core.id) and (GameZoneStagesTable.stageNo eq s.stageNo) }) {
                it[scheduledAt] = s.scheduledAt.toUtcOffsetDateTime()
                it[warnedAt] = s.warnedAt?.toUtcOffsetDateTime()
                it[appliedAt] = s.appliedAt?.toUtcOffsetDateTime()
            }
        }
        session.missions.forEach { m ->
            if (m.id in session.newMissionIds) {
                MissionsTable.insert {
                    it[id] = m.id
                    it[gameId] = core.id
                    it[targetLatitude] = m.target.latitude
                    it[targetLongitude] = m.target.longitude
                    it[targetRadiusMeters] = m.radiusMeters
                    it[startedAt] = m.startedAt.toUtcOffsetDateTime()
                    it[deadlineAt] = m.deadlineAt.toUtcOffsetDateTime()
                    it[status] = m.status.name
                    it[completedBy] = m.completedBy
                    it[endedAt] = m.endedAt?.toUtcOffsetDateTime()
                }
            } else {
                MissionsTable.update({ MissionsTable.id eq m.id }) {
                    it[status] = m.status.name
                    it[completedBy] = m.completedBy
                    it[endedAt] = m.endedAt?.toUtcOffsetDateTime()
                }
            }
        }
        session.newMissionIds.clear()
        session.escortStarts.forEach { (policeId, thiefId) ->
            EscortsTable.insert {
                it[gameId] = core.id
                it[EscortsTable.policeId] = policeId
                it[EscortsTable.thiefId] = thiefId
                it[startedAt] = now.toUtcOffsetDateTime()
            }
        }
        session.escortJailed.forEach { thiefId -> closeEscorts(core.id, thiefId, "JAILED", now) }
        session.escortStarts.clear()
        session.escortJailed.clear()

        val saved = session.events.map { event -> event to insertEvent(core.id, event, version) }
        session.events.clear()

        if (!session.wasEndedAtLoad && session.isEnded) onGameEnded(core, now)
        return saved
    }

    private fun onGameEnded(core: GameCore, now: Instant) {
        QrTokensTable.update({ (QrTokensTable.gameId eq core.id) and QrTokensTable.revokedAt.isNull() }) {
            it[revokedAt] = now.toUtcOffsetDateTime()
        }
        ActiveParticipationsTable.deleteWhere { ActiveParticipationsTable.gameId eq core.id }
        closeEscorts(core.id, null, "GAME_ENDED", now)
        // game_results 의 기본키가 game_id 이므로 같은 경기의 결과를 두 번 저장하면 DB 가 거부한다.
        GameResultsTable.insert {
            it[gameId] = core.id
            it[result] = core.result!!.name
            it[endReason] = core.endReason!!.name
            it[decidedAt] = (core.endedAt ?: now).toUtcOffsetDateTime()
            it[decidedVersion] = core.version
        }
    }

    private fun closeEscorts(gameId: UUID, thiefId: UUID?, reason: String, now: Instant) {
        EscortsTable.update({
            val open = (EscortsTable.gameId eq gameId) and EscortsTable.endedAt.isNull()
            if (thiefId == null) open else open and (EscortsTable.thiefId eq thiefId)
        }) {
            it[endedAt] = now.toUtcOffsetDateTime()
            it[endReason] = reason
        }
    }

    private fun insertEvent(gameId: UUID, event: RecordedEvent, version: Long): GameEventDto {
        val id = GameEventsTable.insert {
            it[GameEventsTable.gameId] = gameId
            it[eventType] = event.type.name
            it[eventData] = event.data.toString()
            it[gameVersion] = version
            it[visibility] = event.visibility.name
            it[targetUserId] = event.targetUserId
            it[createdAt] = event.at.toUtcOffsetDateTime()
        }[GameEventsTable.id]
        return GameEventDto(id, gameId.toString(), event.type, version, event.data, event.at.toEpochMilli())
    }

    // ---------------- QR ----------------

    /** 스캔한 문자열을 DB 에서 찾는다. 토큰의 경기·소유자는 DB 가 기준이므로 QR 내용을 위조해도 통과할 수 없다. */
    fun lookupToken(token: String, gameId: UUID): ScannedToken {
        if (token.isBlank() || token.length > MAX_TOKEN_LENGTH) return ScannedToken.Unknown
        val row = QrTokensTable.selectAll().where { QrTokensTable.token eq token }.singleOrNull() ?: return ScannedToken.Unknown
        return when {
            row[QrTokensTable.gameId] != gameId -> ScannedToken.OtherGame
            row[QrTokensTable.revokedAt] != null -> ScannedToken.Revoked
            else -> ScannedToken.Valid(row[QrTokensTable.userId])
        }
    }

    fun qrTokenOf(gameId: UUID, userId: UUID): QrTokenDto? =
        QrTokensTable.selectAll()
            .where { (QrTokensTable.gameId eq gameId) and (QrTokensTable.userId eq userId) and QrTokensTable.revokedAt.isNull() }
            .singleOrNull()
            ?.let { QrTokenDto(gameId.toString(), userId.toString(), it[QrTokensTable.token], it[QrTokensTable.createdAt].toInstant().toEpochMilli()) }

    /** 같은 사용자가 같은 requestId 로 이미 처리한 스캔이 있으면 (경기 ID, 처음 결과). */
    fun findScanResult(userId: UUID, requestId: String): Pair<UUID, QrScanResultDto>? =
        ScanRequestsTable.selectAll()
            .where { (ScanRequestsTable.userId eq userId) and (ScanRequestsTable.requestId eq requestId) }
            .singleOrNull()
            ?.let { it[ScanRequestsTable.gameId] to ApiJson.decodeFromString(QrScanResultDto.serializer(), it[ScanRequestsTable.resultJson]) }

    fun saveScanResult(userId: UUID, requestId: String, gameId: UUID, kind: String, result: QrScanResultDto, now: Instant) {
        ScanRequestsTable.insert {
            it[ScanRequestsTable.userId] = userId
            it[ScanRequestsTable.requestId] = requestId
            it[ScanRequestsTable.gameId] = gameId
            it[ScanRequestsTable.kind] = kind
            it[resultJson] = ApiJson.encodeToString(QrScanResultDto.serializer(), result)
            it[createdAt] = now.toUtcOffsetDateTime()
        }
    }

    // ---------------- 이벤트·결과 ----------------

    /** [viewer] 가 볼 수 있는 이벤트만(ALL, 내 역할, 나에게 온 것). */
    fun events(gameId: UUID, viewer: UUID, viewerRole: PlayerRole, afterEventId: Long, limit: Int): List<GameEventDto> =
        GameEventsTable.selectAll()
            .where {
                (GameEventsTable.gameId eq gameId) and (GameEventsTable.id greater afterEventId) and (
                    (GameEventsTable.visibility eq Visibility.ALL.name) or
                        (GameEventsTable.visibility eq viewerRole.name) or
                        ((GameEventsTable.visibility eq Visibility.USER.name) and (GameEventsTable.targetUserId eq viewer))
                    )
            }
            .orderBy(GameEventsTable.id to SortOrder.ASC)
            .limit(limit)
            .map { it.toEventDto() }

    fun lastEventId(gameId: UUID): Long =
        GameEventsTable.selectAll().where { GameEventsTable.gameId eq gameId }
            .orderBy(GameEventsTable.id to SortOrder.DESC).limit(1).singleOrNull()?.get(GameEventsTable.id) ?: 0

    /** 이번 경기의 플레이어별 주요 활동(누적 점수·랭킹 없음). */
    fun resultOf(session: GameSession): GameResultDto {
        val events = GameEventsTable.selectAll()
            .where {
                (GameEventsTable.gameId eq session.core.id) and
                    (GameEventsTable.eventType inList listOf(GameEventType.THIEF_ARRESTED.name, GameEventType.THIEF_RESCUED.name, GameEventType.MISSION_COMPLETED.name))
            }
            .map { GameEventType.valueOf(it[GameEventsTable.eventType]) to ApiJson.parseToJsonElement(it[GameEventsTable.eventData]).jsonObject }
        fun count(type: GameEventType, key: String, userId: UUID) = events.count { (t, data) -> t == type && data.string(key) == userId.toString() }
        return GameResultDto(
            gameId = session.core.id.toString(),
            status = session.core.status,
            result = session.core.result,
            endReason = session.core.endReason,
            startedAtEpochMillis = session.core.startedAt?.toEpochMilli(),
            endedAtEpochMillis = session.core.endedAt?.toEpochMilli(),
            players = session.players.map { p ->
                PlayerResultDto(
                    userId = p.userId.toString(),
                    nickname = p.nickname,
                    role = p.role,
                    finalStatus = p.status,
                    capturesMade = count(GameEventType.THIEF_ARRESTED, GameEventKeys.BY_USER_ID, p.userId),
                    rescuesMade = count(GameEventType.THIEF_RESCUED, GameEventKeys.BY_USER_ID, p.userId),
                    timesCaptured = count(GameEventType.THIEF_ARRESTED, GameEventKeys.USER_ID, p.userId),
                    missionsCompleted = count(GameEventType.MISSION_COMPLETED, GameEventKeys.BY_USER_ID, p.userId),
                    speedWarnings = p.warningCount,
                )
            },
        )
    }

    /** 보관 기간이 지난 위치 기록 삭제(위치 정보 최소 보관). 진행 중 경기(최대 31분)의 기록은 해당하지 않는다. */
    fun deleteLocationsBefore(cutoff: Instant): Int =
        PlayerLocationsTable.deleteWhere { PlayerLocationsTable.receivedAt less cutoff.toUtcOffsetDateTime() }

    // ---------------- 행 변환 ----------------

    private fun ResultRow.toCore() = GameCore(
        id = this[GamesTable.id],
        status = GameStatus.valueOf(this[GamesTable.status]),
        version = this[GamesTable.version],
        center = GeoPoint(this[GamesTable.centerLatitude], this[GamesTable.centerLongitude]),
        initialRadiusMeters = this[GamesTable.initialRadiusMeters],
        currentRadiusMeters = this[GamesTable.currentRadiusMeters],
        jailRadiusMeters = this[GamesTable.jailRadiusMeters],
        createdAt = this[GamesTable.createdAt].toInstant(),
        startsAt = this[GamesTable.startsAt]!!.toInstant(),
        startedAt = this[GamesTable.startedAt]?.toInstant(),
        endsAt = this[GamesTable.endsAt]!!.toInstant(),
        endedAt = this[GamesTable.endedAt]?.toInstant(),
        result = this[GamesTable.result]?.let(GameResult::valueOf),
        endReason = this[GamesTable.endReason]?.let(GameEndReason::valueOf),
        nextMissionAt = this[GamesTable.nextMissionAt]?.toInstant(),
    )

    private fun ResultRow.toPlayer() = PlayerState(
        userId = this[GamePlayersTable.userId],
        nickname = this[UsersTable.nickname],
        role = PlayerRole.valueOf(this[GamePlayersTable.role]),
        joinedAt = this[GamePlayersTable.joinedAt].toInstant(),
        status = PlayerStatus.valueOf(this[GamePlayersTable.status]),
        capturedBy = this[GamePlayersTable.capturedBy],
        escortingUserId = this[GamePlayersTable.escortingUserId],
        warningCount = this[GamePlayersTable.warningCount],
        connection = PlayerConnectionState.valueOf(this[GamePlayersTable.connectionState]),
        disconnectedAt = this[GamePlayersTable.disconnectedAt]?.toInstant(),
        outOfZoneSince = this[GamePlayersTable.outOfZoneSince]?.toInstant(),
        speedCandidateCount = this[GamePlayersTable.speedCandidateCount],
        lastSpeedWarningAt = this[GamePlayersTable.lastSpeedWarningAt]?.toInstant(),
        lastRingAt = this[GamePlayersTable.lastRingAt]?.toInstant(),
    )

    private fun ResultRow.toEventDto() = GameEventDto(
        eventId = this[GameEventsTable.id],
        gameId = this[GameEventsTable.gameId].toString(),
        type = GameEventType.valueOf(this[GameEventsTable.eventType]),
        gameVersion = this[GameEventsTable.gameVersion],
        data = ApiJson.parseToJsonElement(this[GameEventsTable.eventData]).jsonObject,
        createdAtEpochMillis = this[GameEventsTable.createdAt].toInstant().toEpochMilli(),
    )

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content

    private companion object {
        const val QR_TOKEN_PREFIX = "PNT1."
        const val QR_TOKEN_BYTES = 24
        const val MAX_TOKEN_LENGTH = 128
    }
}
