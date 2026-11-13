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

/** 위치 저장소. */
interface LocationRepository {
    suspend fun insert(userId: UUID, request: LocationUpdateRequest, receivedAt: Instant): LocationDto
    suspend fun findLatest(userId: UUID): LocationDto?
}

class ExposedLocationRepository(private val db: AppDatabase) : LocationRepository {
    override suspend fun insert(userId: UUID, request: LocationUpdateRequest, receivedAt: Instant): LocationDto = db.query {
        PlayerLocationsTable.insert {
            it[PlayerLocationsTable.userId] = userId
            it[latitude] = request.latitude
            it[longitude] = request.longitude
            it[accuracyMeters] = request.accuracyMeters
            it[locationSource] = request.source.name
            it[measuredAt] = request.measuredAtEpochMillis.epochMillisToUtc()
            it[PlayerLocationsTable.receivedAt] = receivedAt.toUtcOffsetDateTime()
        }
        LocationDto(
            userId = userId.toString(),
            latitude = request.latitude,
            longitude = request.longitude,
            accuracyMeters = request.accuracyMeters,
            measuredAtEpochMillis = request.measuredAtEpochMillis,
            receivedAtEpochMillis = receivedAt.toEpochMilli(),
            source = request.source,
        )
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
 * 위치 수신. 클라이언트가 보낸 좌표·시각을 그대로 믿지 않고 shared 의 LocationValidator 로 다시 검증한다.
 *
 * 범위: 지금은 "사용자 → 서버 저장"까지만 한다. 매칭 반경 계산, 경기별 공개 범위 필터링(경찰=전원, 도둑=도둑끼리),
 * 구역 이탈·과속 판정은 Matchmaking / Game 기능에서 이 저장소를 읽어 구현한다(docs/development/TODO.md).
 */
class LocationService(
    private val locations: LocationRepository,
    private val clock: Clock,
    private val allowMockLocations: Boolean,
) {
    suspend fun submit(userId: UUID, request: LocationUpdateRequest): LocationDto {
        if (request.source == LocationSource.MOCK && !allowMockLocations) {
            throw ForbiddenException(
                "테스트 좌표(MOCK)는 개발 서버에서만 허용됩니다.",
                mapOf("source" to "MOCK_LOCATION_NOT_ALLOWED"),
            )
        }
        val now = clock.instant()
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
        return locations.insert(userId, request, receivedAt = now)
    }

    suspend fun latest(userId: UUID): LocationDto =
        locations.findLatest(userId) ?: throw NotFoundException("저장된 위치가 없습니다.")
}
