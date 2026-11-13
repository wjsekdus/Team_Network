package com.nettwork.policethief.data.mapper

import com.nettwork.policethief.domain.model.Game
import com.nettwork.policethief.domain.model.GameEvent
import com.nettwork.policethief.domain.model.GamePlayer
import com.nettwork.policethief.domain.model.MatchmakingTicket
import com.nettwork.policethief.domain.model.PlayerLocation
import com.nettwork.policethief.domain.model.QrScanResult
import com.nettwork.policethief.domain.model.QrToken
import com.nettwork.policethief.domain.model.ServerStatus
import com.nettwork.policethief.domain.model.User
import com.nettwork.policethief.shared.dto.GameDto
import com.nettwork.policethief.shared.dto.GameEventDto
import com.nettwork.policethief.shared.dto.GamePlayerDto
import com.nettwork.policethief.shared.dto.HealthResponse
import com.nettwork.policethief.shared.dto.LocationDto
import com.nettwork.policethief.shared.dto.LocationUpdateRequest
import com.nettwork.policethief.shared.dto.MatchmakingTicketDto
import com.nettwork.policethief.shared.dto.QrScanResultDto
import com.nettwork.policethief.shared.dto.QrTokenDto
import com.nettwork.policethief.shared.dto.UserDto

/*
 * DTO(서버 JSON) ↔ Domain Entity 변환. 변환 규칙이 한 파일에 모여 있어 계약 변경 시 이 파일만 확인하면 된다.
 */

fun UserDto.toDomain() = User(userId, nickname, createdAtEpochMillis, lastActiveAtEpochMillis)

fun PlayerLocation.toRequest() = LocationUpdateRequest(
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = accuracyMeters,
    measuredAtEpochMillis = timestampEpochMillis,
    source = source,
)

fun LocationDto.toDomain() = PlayerLocation(
    userId = userId,
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = accuracyMeters,
    timestampEpochMillis = measuredAtEpochMillis,
    source = source,
)

fun HealthResponse.toDomain(baseUrl: String, checkedAt: Long) = ServerStatus(
    baseUrl = baseUrl,
    isServerUp = status == "UP",
    isDatabaseUp = database == "UP",
    version = version,
    serverTimeEpochMillis = serverTimeEpochMillis,
    checkedAtEpochMillis = checkedAt,
)

fun MatchmakingTicketDto.toDomain() = MatchmakingTicket(status, joinedAtEpochMillis, expiresAtEpochMillis, nearbyWaitingCount, gameId)

fun GamePlayerDto.toDomain() = GamePlayer(userId, nickname, role, status, capturedBy, warningCount, connectionState)

fun GameDto.toDomain() = Game(
    gameId = gameId,
    status = status,
    centerLatitude = centerLatitude,
    centerLongitude = centerLongitude,
    currentRadiusMeters = currentRadiusMeters,
    jailRadiusMeters = jailRadiusMeters,
    startsAtEpochMillis = startsAtEpochMillis,
    endsAtEpochMillis = endsAtEpochMillis,
    endedAtEpochMillis = endedAtEpochMillis,
    result = result,
    endReason = endReason,
    players = players.map { it.toDomain() },
)

fun GameEventDto.toDomain() = GameEvent(eventId, gameId, type, createdAtEpochMillis)

fun QrTokenDto.toDomain() = QrToken(gameId, userId, token, issuedAtEpochMillis)

fun QrScanResultDto.toDomain() = QrScanResult(accepted, targetUserId, targetStatus, rejectReason)
