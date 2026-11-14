package com.nettwork.policethief.data.mapper

import com.nettwork.policethief.domain.model.Game
import com.nettwork.policethief.domain.model.GameEvent
import com.nettwork.policethief.domain.model.GamePlayer
import com.nettwork.policethief.domain.model.GameResultSummary
import com.nettwork.policethief.domain.model.GameSnapshot
import com.nettwork.policethief.domain.model.MatchmakingTicket
import com.nettwork.policethief.domain.model.Mission
import com.nettwork.policethief.domain.model.PlayerLocation
import com.nettwork.policethief.domain.model.PlayerResult
import com.nettwork.policethief.domain.model.QrScanResult
import com.nettwork.policethief.domain.model.QrToken
import com.nettwork.policethief.domain.model.ServerStatus
import com.nettwork.policethief.domain.model.User
import com.nettwork.policethief.domain.model.ZoneShrink
import com.nettwork.policethief.domain.repository.DevBot
import com.nettwork.policethief.shared.dto.DevBotDto
import com.nettwork.policethief.shared.dto.GameDto
import com.nettwork.policethief.shared.dto.GameEventDto
import com.nettwork.policethief.shared.dto.GamePlayerDto
import com.nettwork.policethief.shared.dto.GameResultDto
import com.nettwork.policethief.shared.dto.GameStateDto
import com.nettwork.policethief.shared.dto.MissionDto
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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

fun MatchmakingTicketDto.toDomain() = MatchmakingTicket(
    status = status,
    joinedAtEpochMillis = joinedAtEpochMillis ?: 0,
    expiresAtEpochMillis = expiresAtEpochMillis ?: 0,
    nearbyWaitingCount = nearbyWaitingCount,
    gameId = gameId,
    serverTimeEpochMillis = serverTimeEpochMillis,
)

fun GamePlayerDto.toDomain() = GamePlayer(
    userId = userId,
    nickname = nickname,
    role = role,
    status = status,
    capturedBy = capturedBy,
    warningCount = warningCount,
    connectionState = connectionState,
    escortingUserId = escortingUserId,
    disconnectedAtEpochMillis = disconnectedAtEpochMillis,
    outOfZoneSinceEpochMillis = outOfZoneSinceEpochMillis,
)

fun MissionDto.toDomain() = Mission(
    missionId = missionId,
    status = status,
    targetLatitude = targetLatitude,
    targetLongitude = targetLongitude,
    targetRadiusMeters = targetRadiusMeters,
    startedAtEpochMillis = startedAtEpochMillis,
    deadlineAtEpochMillis = deadlineAtEpochMillis,
    completedByUserId = completedByUserId,
)

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
    version = version,
    initialRadiusMeters = initialRadiusMeters,
    startedAtEpochMillis = startedAtEpochMillis,
    nextZoneShrink = nextZoneShrink?.let { ZoneShrink(it.stage, it.scheduledAtEpochMillis, it.radiusMeters) },
    missions = missions.map { it.toDomain() },
    ringFeatureEnabled = features.ringThiefPhone,
    serverTimeEpochMillis = serverTimeEpochMillis,
)

/** data 의 JSON 값은 문자열로 바꿔 보관한다(따옴표 없는 원래 값). */
fun GameEventDto.toDomain() = GameEvent(
    eventId = eventId,
    gameId = gameId,
    type = type,
    createdAtEpochMillis = createdAtEpochMillis,
    gameVersion = gameVersion,
    data = data.mapValues { (_, value) -> (value as? JsonPrimitive)?.contentOrNull ?: value.toString() },
)

fun GameStateDto.toDomain() = GameSnapshot(
    game = game.toDomain(),
    locations = locations.map { it.toDomain() },
    recentEvents = recentEvents.map { it.toDomain() },
    lastEventId = lastEventId,
)

fun GameResultDto.toDomain() = GameResultSummary(
    gameId = gameId,
    status = status,
    result = result,
    endReason = endReason,
    startedAtEpochMillis = startedAtEpochMillis,
    endedAtEpochMillis = endedAtEpochMillis,
    players = players.map {
        PlayerResult(it.userId, it.nickname, it.role, it.finalStatus, it.capturesMade, it.rescuesMade, it.timesCaptured, it.missionsCompleted, it.speedWarnings)
    },
)

fun QrTokenDto.toDomain() = QrToken(gameId, userId, token, issuedAtEpochMillis)

fun QrScanResultDto.toDomain() = QrScanResult(
    accepted = accepted,
    targetUserId = targetUserId,
    targetStatus = targetStatus,
    rejectReason = rejectReason,
    targetNickname = targetNickname,
    gameStatus = gameStatus,
    gameResult = gameResult,
)

fun DevBotDto.toDomain() = DevBot(userId, nickname, role, status, qrToken)
