package com.nettwork.policethief.server.matchmaking

import com.nettwork.policethief.server.common.ConflictException
import com.nettwork.policethief.server.common.GameLock
import com.nettwork.policethief.server.common.ValidationException
import com.nettwork.policethief.server.common.toUtcOffsetDateTime
import com.nettwork.policethief.server.database.AppDatabase
import com.nettwork.policethief.server.database.MatchmakingQueueTable
import com.nettwork.policethief.server.game.GameSettings
import com.nettwork.policethief.server.game.GameStore
import com.nettwork.policethief.server.game.MatchMember
import com.nettwork.policethief.server.location.LocationService
import com.nettwork.policethief.server.location.insertLocationRow
import com.nettwork.policethief.server.notification.NotificationService
import com.nettwork.policethief.server.notification.Outbox
import com.nettwork.policethief.shared.dto.JoinMatchmakingRequest
import com.nettwork.policethief.shared.dto.LocationUpdateRequest
import com.nettwork.policethief.shared.dto.MatchmakingTicketDto
import com.nettwork.policethief.shared.dto.ServerMessage
import com.nettwork.policethief.shared.geo.GeoMath
import com.nettwork.policethief.shared.geo.GeoPoint
import com.nettwork.policethief.shared.model.LocationSource
import com.nettwork.policethief.shared.model.MatchmakingEventType
import com.nettwork.policethief.shared.model.MatchmakingStatus
import com.nettwork.policethief.shared.rules.GameRules
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 위치 기반 자동 매칭(Matchmaking 기능 영역).
 *
 * 상태: IDLE → WAITING → MATCHED / CANCELLED / EXPIRED(10분).
 * - 같은 사용자는 대기열에 1행만 가진다(matchmaking_queue 기본키 = user_id). 대기 중 다시 요청하면 같은 대기를 이어간다.
 * - 진행 중인 경기가 있는 사용자는 대기열에 들어올 수 없다(active_participations).
 * - 매칭 확정은 GameLock + 트랜잭션 + 대기열 행 잠금(SELECT ... FOR UPDATE) 안에서 "아직 WAITING 인지" 다시 확인한 뒤 한다.
 *   그래서 확정 직전에 취소한 사람, 다른 경기에 먼저 들어간 사람은 섞이지 않는다.
 * - 대기 중 위치가 오래되면(GameSettings.matchLocationMaxAgeMillis) 후보에서 빠진다(앱이 꺼졌거나 연결이 끊긴 사람).
 */
class MatchmakingService(
    private val db: AppDatabase,
    private val lock: GameLock,
    private val store: GameStore,
    private val clock: Clock,
    private val settings: GameSettings,
    private val allowMockLocations: Boolean,
    private val notifications: NotificationService,
) {
    /** 대기자에게 마지막으로 알린 주변 인원(같은 숫자를 반복해서 보내지 않기 위함). 메모리 값이라 재시작 시 초기화돼도 무방하다. */
    private val lastNotifiedCount = ConcurrentHashMap<UUID, Int>()

    suspend fun join(userId: UUID, request: JoinMatchmakingRequest): MatchmakingTicketDto = locked { now, outbox ->
        val location = request.location
        validateMatchLocation(location, now)
        if (store.activeGameIdOf(userId) != null) throw ConflictException("이미 진행 중인 경기가 있습니다.", "ALREADY_IN_GAME")
        insertLocationRow(userId, null, location, now)
        val existing = queueRow(userId)
        if (existing != null && existing.status == MatchmakingStatus.WAITING && existing.expiresAt.isAfter(now)) {
            // 같은 사용자의 중복 요청: 새 대기를 만들지 않고 위치만 갱신한다(대기 시작 시각·10분 기한 유지).
            updateWaiterLocation(userId, location, now)
        } else {
            enqueue(userId, location, now, replace = existing != null)
        }
        matchAndNotify(now, outbox)
        ticketOf(userId, now)
    }

    /** 취소는 서버가 확정한다. 여러 번 보내도 결과는 같다. 이미 매칭이 확정됐다면 취소가 아니라 기권(forfeit) 대상이다. */
    suspend fun cancel(userId: UUID) = locked { now, outbox ->
        val row = queueRow(userId)
        when {
            row == null -> Unit
            row.status == MatchmakingStatus.WAITING -> {
                setStatus(listOf(userId), MatchmakingStatus.CANCELLED, null, now)
                lastNotifiedCount.remove(userId)
                outbox.add(userId, ServerMessage.MatchmakingUpdate(MatchmakingEventType.MATCHING_CANCELLED, ticketOf(userId, now)))
                matchAndNotify(now, outbox)
            }
            row.status == MatchmakingStatus.MATCHED && store.activeGameIdOf(userId) != null ->
                throw ConflictException("이미 매칭이 확정되었습니다. 경기에서 나가려면 게임 나가기(기권)를 사용하세요.", "ALREADY_MATCHED")
            else -> Unit
        }
    }

    suspend fun myTicket(userId: UUID): MatchmakingTicketDto = locked { now, outbox ->
        expireOverdue(now, outbox)
        ticketOf(userId, now)
    }

    // ---- GameEngine 이 잠금·트랜잭션 안에서 호출하는 함수 ----

    /** 대기 중인 사용자의 새 위치를 대기열에 반영하고 매칭을 다시 시도한다. 대기 중이 아니면 아무 일도 없다. */
    fun onWaiterLocationLocked(userId: UUID, location: LocationUpdateRequest, now: Instant, outbox: Outbox) {
        val row = queueRow(userId) ?: return
        if (row.status != MatchmakingStatus.WAITING || !row.expiresAt.isAfter(now)) return
        if (location.accuracyMeters > settings.matchMaxAccuracyMeters) return
        updateWaiterLocation(userId, location, now)
        matchAndNotify(now, outbox)
    }

    /** 스케줄러: 10분 만료 처리 + 조건이 맞게 된 그룹 매칭. */
    fun tickLocked(now: Instant, outbox: Outbox) = matchAndNotify(now, outbox)

    // ---- 내부 ----

    private suspend fun <T> locked(block: (Instant, Outbox) -> T): T {
        val outbox = Outbox()
        val result = lock.withLock { db.query { block(clock.instant(), outbox) } }
        notifications.deliver(outbox)
        return result
    }

    private fun validateMatchLocation(location: LocationUpdateRequest, now: Instant) {
        LocationService.validate(location, now, allowMockLocations)
        if (location.accuracyMeters > settings.matchMaxAccuracyMeters) {
            throw ValidationException(
                "GPS 정확도가 낮아 매칭할 수 없습니다(±${location.accuracyMeters.toInt()}m). 하늘이 보이는 곳에서 다시 시도하세요.",
                mapOf("accuracyMeters" to "ACCURACY_TOO_LOW"),
            )
        }
        if (now.toEpochMilli() - location.measuredAtEpochMillis > settings.matchLocationMaxAgeMillis) {
            throw ValidationException("위치가 오래되었습니다. 현재 위치를 다시 확인하세요.", mapOf("measuredAtEpochMillis" to "LOCATION_TOO_OLD"))
        }
    }

    private fun matchAndNotify(now: Instant, outbox: Outbox) {
        expireOverdue(now, outbox)
        val fresh = freshWaiters(now)
        val groups = MatchFinder.findGroups(fresh.map { Waiter(it.userId, it.point, it.joinedAt) })
        for (group in groups) {
            val ids = group.map { it.userId }
            // 확정 직전 재확인: 행을 잠그고 아직 WAITING 인지, 다른 경기에 들어가지 않았는지 본다.
            val locked = MatchmakingQueueTable.selectAll()
                .where { (MatchmakingQueueTable.userId inList ids) and (MatchmakingQueueTable.status eq MatchmakingStatus.WAITING.name) }
                .forUpdate()
                .map { it.toQueueRow() }
            if (locked.size != ids.size || ids.any { store.activeGameIdOf(it) != null }) continue

            val created = store.createGame(
                locked.map { MatchMember(it.userId, it.latitude, it.longitude, it.accuracyMeters, it.source, it.updatedAt) },
                now,
                settings,
            )
            setStatus(ids, MatchmakingStatus.MATCHED, created.gameId, now)
            ids.forEach { userId ->
                lastNotifiedCount.remove(userId)
                outbox.add(userId, ServerMessage.MatchmakingUpdate(MatchmakingEventType.MATCHING_COMPLETED, ticketOf(userId, now)))
                outbox.add(userId, ServerMessage.GameEventPush(created.preparingEvent))
            }
        }
        notifyNearbyCounts(now, outbox)
    }

    private fun notifyNearbyCounts(now: Instant, outbox: Outbox) {
        val waiting = waitingRows(now)
        val fresh = waiting.filter { it.isFresh(now) }
        waiting.forEach { row ->
            val count = nearbyCount(row, fresh)
            if (lastNotifiedCount.put(row.userId, count) != count) {
                outbox.add(row.userId, ServerMessage.MatchmakingUpdate(MatchmakingEventType.MATCHING_UPDATED, row.toTicket(count, now)))
            }
        }
    }

    private fun expireOverdue(now: Instant, outbox: Outbox) {
        val overdue = MatchmakingQueueTable.selectAll()
            .where { MatchmakingQueueTable.status eq MatchmakingStatus.WAITING.name }
            .map { it.toQueueRow() }
            .filter { !it.expiresAt.isAfter(now) }
        if (overdue.isEmpty()) return
        setStatus(overdue.map { it.userId }, MatchmakingStatus.EXPIRED, null, now)
        overdue.forEach {
            lastNotifiedCount.remove(it.userId)
            outbox.add(it.userId, ServerMessage.MatchmakingUpdate(MatchmakingEventType.MATCHING_TIMEOUT, ticketOf(it.userId, now)))
        }
    }

    private fun ticketOf(userId: UUID, now: Instant): MatchmakingTicketDto {
        val row = queueRow(userId) ?: return idle(userId, now)
        return when (row.status) {
            MatchmakingStatus.WAITING -> row.toTicket(nearbyCount(row, freshWaiters(now)), now)
            // 끝난 경기의 MATCHED 기록은 "대기열에 없음"으로 본다(앱 재실행 시 지난 경기로 돌아가지 않도록).
            MatchmakingStatus.MATCHED -> if (row.gameId != null && store.activeGameIdOf(userId) == row.gameId) row.toTicket(GameRules.PLAYERS_PER_GAME, now) else idle(userId, now)
            else -> row.toTicket(0, now)
        }
    }

    private fun idle(userId: UUID, now: Instant) =
        MatchmakingTicketDto(userId.toString(), MatchmakingStatus.IDLE, serverTimeEpochMillis = now.toEpochMilli())

    /** 나를 포함해 내 반경 500m 안에서 기다리는 인원. 다른 사람 좌표는 응답에 넣지 않는다. */
    private fun nearbyCount(row: QueueRow, fresh: List<QueueRow>): Int =
        1 + fresh.count { it.userId != row.userId && GeoMath.distanceMeters(it.point, row.point) <= GameRules.MATCH_RADIUS_METERS }

    private fun freshWaiters(now: Instant) = waitingRows(now).filter { it.isFresh(now) }

    private fun waitingRows(now: Instant): List<QueueRow> =
        MatchmakingQueueTable.selectAll()
            .where { MatchmakingQueueTable.status eq MatchmakingStatus.WAITING.name }
            .map { it.toQueueRow() }
            .filter { it.expiresAt.isAfter(now) }

    private fun QueueRow.isFresh(now: Instant) = now.toEpochMilli() - updatedAt.toEpochMilli() <= settings.matchLocationMaxAgeMillis

    private fun queueRow(userId: UUID): QueueRow? =
        MatchmakingQueueTable.selectAll().where { MatchmakingQueueTable.userId eq userId }.singleOrNull()?.toQueueRow()

    private fun enqueue(userId: UUID, location: LocationUpdateRequest, now: Instant, replace: Boolean) {
        val expiresAt = now.plusMillis(GameRules.MATCH_TIMEOUT_MILLIS)
        if (replace) {
            MatchmakingQueueTable.update({ MatchmakingQueueTable.userId eq userId }) {
                it[latitude] = location.latitude
                it[longitude] = location.longitude
                it[accuracyMeters] = location.accuracyMeters
                it[locationSource] = location.source.name
                it[status] = MatchmakingStatus.WAITING.name
                it[gameId] = null
                it[joinedAt] = now.toUtcOffsetDateTime()
                it[MatchmakingQueueTable.expiresAt] = expiresAt.toUtcOffsetDateTime()
                it[updatedAt] = now.toUtcOffsetDateTime()
            }
        } else {
            MatchmakingQueueTable.insert {
                it[MatchmakingQueueTable.userId] = userId
                it[latitude] = location.latitude
                it[longitude] = location.longitude
                it[accuracyMeters] = location.accuracyMeters
                it[locationSource] = location.source.name
                it[status] = MatchmakingStatus.WAITING.name
                it[joinedAt] = now.toUtcOffsetDateTime()
                it[MatchmakingQueueTable.expiresAt] = expiresAt.toUtcOffsetDateTime()
                it[updatedAt] = now.toUtcOffsetDateTime()
            }
        }
    }

    private fun updateWaiterLocation(userId: UUID, location: LocationUpdateRequest, now: Instant) {
        MatchmakingQueueTable.update({ MatchmakingQueueTable.userId eq userId }) {
            it[latitude] = location.latitude
            it[longitude] = location.longitude
            it[accuracyMeters] = location.accuracyMeters
            it[locationSource] = location.source.name
            it[updatedAt] = now.toUtcOffsetDateTime()
        }
    }

    private fun setStatus(userIds: List<UUID>, status: MatchmakingStatus, gameId: UUID?, now: Instant) {
        MatchmakingQueueTable.update({ MatchmakingQueueTable.userId inList userIds }) {
            it[MatchmakingQueueTable.status] = status.name
            it[MatchmakingQueueTable.gameId] = gameId
            it[updatedAt] = now.toUtcOffsetDateTime()
        }
    }

    private data class QueueRow(
        val userId: UUID,
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Double,
        val source: LocationSource,
        val status: MatchmakingStatus,
        val gameId: UUID?,
        val joinedAt: Instant,
        val expiresAt: Instant,
        val updatedAt: Instant,
    ) {
        val point get() = GeoPoint(latitude, longitude)

        fun toTicket(nearby: Int, now: Instant) = MatchmakingTicketDto(
            userId = userId.toString(),
            status = status,
            joinedAtEpochMillis = joinedAt.toEpochMilli(),
            expiresAtEpochMillis = expiresAt.toEpochMilli(),
            nearbyWaitingCount = nearby,
            gameId = gameId?.toString(),
            serverTimeEpochMillis = now.toEpochMilli(),
        )
    }

    private fun ResultRow.toQueueRow() = QueueRow(
        userId = this[MatchmakingQueueTable.userId],
        latitude = this[MatchmakingQueueTable.latitude],
        longitude = this[MatchmakingQueueTable.longitude],
        accuracyMeters = this[MatchmakingQueueTable.accuracyMeters],
        source = LocationSource.valueOf(this[MatchmakingQueueTable.locationSource]),
        status = MatchmakingStatus.valueOf(this[MatchmakingQueueTable.status]),
        gameId = this[MatchmakingQueueTable.gameId],
        joinedAt = this[MatchmakingQueueTable.joinedAt].toInstant(),
        expiresAt = this[MatchmakingQueueTable.expiresAt].toInstant(),
        updatedAt = this[MatchmakingQueueTable.updatedAt].toInstant(),
    )
}
