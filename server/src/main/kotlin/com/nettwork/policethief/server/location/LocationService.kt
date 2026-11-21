package com.nettwork.policethief.server.location

import com.nettwork.policethief.server.common.ForbiddenException
import com.nettwork.policethief.server.common.NotFoundException
import com.nettwork.policethief.server.common.ValidationException
import com.nettwork.policethief.server.common.epochMillisToUtc
import com.nettwork.policethief.server.common.toEpochMillis
import com.nettwork.policethief.server.common.toUtcOffsetDateTime
import com.nettwork.policethief.server.database.AppDatabase
import com.nettwork.policethief.server.database.PlayerLocationsTable
import com.nettwork.policethief.shared.dto.LocationDto
import com.nettwork.policethief.shared.dto.LocationUpdateRequest
import com.nettwork.policethief.shared.model.LocationSource
import com.nettwork.policethief.shared.rules.LocationValidator
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * 검증을 통과한 위치를 받아 저장하고, 필요하면 게임 판정에 넘기는 곳.
 * 실제 서버에서는 GameEngine 이 구현한다(경기 중이면 판정, 매칭 대기 중이면 대기열 위치 갱신).
 */
fun interface LocationIngest {
    suspend fun ingest(userId: UUID, request: LocationUpdateRequest, receivedAt: Instant): LocationDto
}

/** 위치 저장소(조회 + 경기와 무관한 단순 저장). */
interface LocationRepository {
    suspend fun insert(userId: UUID, request: LocationUpdateRequest, receivedAt: Instant): LocationDto
    suspend fun findLatest(userId: UUID): LocationDto?
}

/**
 * player_locations 에 한 행을 넣는다. 트랜잭션 안에서 호출해야 한다(GameEngine 과 Repository 가 함께 사용).
 * gameId 가 있으면 그 경기의 위치 기록으로 남는다.
 */
fun insertLocationRow(userId: UUID, gameId: UUID?, request: LocationUpdateRequest, receivedAt: Instant): LocationDto {
    PlayerLocationsTable.insert {
        it[PlayerLocationsTable.userId] = userId
        it[PlayerLocationsTable.gameId] = gameId
        it[latitude] = request.latitude
        it[longitude] = request.longitude
        it[accuracyMeters] = request.accuracyMeters
        it[locationSource] = request.source.name
        it[measuredAt] = request.measuredAtEpochMillis.epochMillisToUtc()
        it[PlayerLocationsTable.receivedAt] = receivedAt.toUtcOffsetDateTime()
    }
    return LocationDto(
        userId = userId.toString(),
        latitude = request.latitude,
        longitude = request.longitude,
        accuracyMeters = request.accuracyMeters,
        measuredAtEpochMillis = request.measuredAtEpochMillis,
        receivedAtEpochMillis = receivedAt.toEpochMilli(),
        source = request.source,
    )
}

class ExposedLocationRepository(private val db: AppDatabase) : LocationRepository {
    override suspend fun insert(userId: UUID, request: LocationUpdateRequest, receivedAt: Instant): LocationDto = db.query {
        insertLocationRow(userId, null, request, receivedAt)
    }

    override suspend fun findLatest(userId: UUID): LocationDto? = db.query {
        PlayerLocationsTable.selectAll()
            .where { PlayerLocationsTable.userId eq userId }
            .orderBy(PlayerLocationsTable.receivedAt to SortOrder.DESC, PlayerLocationsTable.id to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.let { row ->
                LocationDto(
                    userId = row[PlayerLocationsTable.userId].toString(),
                    latitude = row[PlayerLocationsTable.latitude],
                    longitude = row[PlayerLocationsTable.longitude],
                    accuracyMeters = row[PlayerLocationsTable.accuracyMeters],
                    measuredAtEpochMillis = row[PlayerLocationsTable.measuredAt].toEpochMillis(),
                    receivedAtEpochMillis = row[PlayerLocationsTable.receivedAt].toEpochMillis(),
                    source = LocationSource.valueOf(row[PlayerLocationsTable.locationSource]),
                )
            }
    }
}

/**
 * 위치 수신. 클라이언트가 보낸 좌표·시각을 그대로 믿지 않고 shared 의 LocationValidator 로 다시 검증한 뒤
 * [ingest] 로 넘긴다. 실제 서버에서 ingest 는 GameEngine 이며, 경기 중이면 과속·구역·감옥·미션을 판정한다.
 */
class LocationService(
    private val locations: LocationRepository,
    private val clock: Clock,
    private val allowMockLocations: Boolean,
    private val ingest: LocationIngest = LocationIngest { userId, request, receivedAt -> locations.insert(userId, request, receivedAt) },
) {
    suspend fun submit(userId: UUID, request: LocationUpdateRequest): LocationDto {
        val now = clock.instant()
        validate(request, now, allowMockLocations)
        return ingest.ingest(userId, request, receivedAt = now)
    }

    suspend fun latest(userId: UUID): LocationDto =
        locations.findLatest(userId) ?: throw NotFoundException("저장된 위치가 없습니다.")

    companion object {
        /** 위치 공통 검증. 매칭 요청 위치도 같은 함수를 거친다. */
        fun validate(request: LocationUpdateRequest, now: Instant, allowMockLocations: Boolean) {
            if (request.source == LocationSource.MOCK && !allowMockLocations) {
                throw ForbiddenException(
                    "테스트 좌표(MOCK)는 개발 서버에서만 허용됩니다.",
                    mapOf("source" to "MOCK_LOCATION_NOT_ALLOWED"),
                )
            }
            val errors = LocationValidator.validate(
                latitude = request.latitude,
                longitude = request.longitude,
                accuracyMeters = request.accuracyMeters,
                measuredAtEpochMillis = request.measuredAtEpochMillis,
                nowEpochMillis = now.toEpochMilli(),
            )
            if (errors.isNotEmpty()) {
                throw ValidationException("위치 데이터가 올바르지 않습니다.", errors.associate { it.name to "invalid" })
            }
        }
    }
}
