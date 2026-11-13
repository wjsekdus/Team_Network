package com.nettwork.policethief.domain.model

import com.nettwork.policethief.shared.model.GameEndReason
import com.nettwork.policethief.shared.model.GameEventType
import com.nettwork.policethief.shared.model.GameResult
import com.nettwork.policethief.shared.model.GameStatus
import com.nettwork.policethief.shared.model.LocationSource
import com.nettwork.policethief.shared.model.MatchmakingStatus
import com.nettwork.policethief.shared.model.PlayerConnectionState
import com.nettwork.policethief.shared.model.PlayerRole
import com.nettwork.policethief.shared.model.PlayerStatus

/*
 * Domain Entity: 앱 내부에서 쓰는 순수 데이터.
 * - DTO(shared.dto)와 분리되어 있어 서버 JSON 형식이 바뀌어도 Mapper 만 고치면 된다.
 * - 상태 enum 은 shared.model 의 어휘를 그대로 쓴다(서버와 같은 의미를 보장하기 위함).
 * - 시각은 UTC epoch milliseconds.
 */

data class User(
    val userId: String,
    val nickname: String,
    val createdAtEpochMillis: Long,
    val lastActiveAtEpochMillis: Long,
)

/** 위치 1건. userId 가 null 이면 "아직 서버에 보내지 않은 내 위치". */
data class PlayerLocation(
    val userId: String?,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val timestampEpochMillis: Long,
    val source: LocationSource,
)

data class ServerStatus(
    val baseUrl: String,
    val isServerUp: Boolean,
    val isDatabaseUp: Boolean,
    val version: String,
    val serverTimeEpochMillis: Long,
    val checkedAtEpochMillis: Long,
)

data class MatchmakingTicket(
    val status: MatchmakingStatus,
    val joinedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val nearbyWaitingCount: Int,
    val gameId: String?,
)

data class Game(
    val gameId: String,
    val status: GameStatus,
    val centerLatitude: Double,
    val centerLongitude: Double,
    val currentRadiusMeters: Double,
    val jailRadiusMeters: Double,
    val startsAtEpochMillis: Long?,
    val endsAtEpochMillis: Long?,
    val endedAtEpochMillis: Long?,
    val result: GameResult?,
    val endReason: GameEndReason?,
    val players: List<GamePlayer>,
) {
    fun player(userId: String): GamePlayer? = players.firstOrNull { it.userId == userId }
}

data class GamePlayer(
    val userId: String,
    val nickname: String,
    val role: PlayerRole,
    val status: PlayerStatus,
    val capturedBy: String?,
    val warningCount: Int,
    val connectionState: PlayerConnectionState,
)

data class GameEvent(
    val eventId: Long,
    val gameId: String,
    val type: GameEventType,
    val createdAtEpochMillis: Long,
)

data class QrToken(
    val gameId: String,
    val userId: String,
    val token: String,
    val issuedAtEpochMillis: Long,
)

data class QrScanResult(
    val accepted: Boolean,
    val targetUserId: String?,
    val targetStatus: PlayerStatus?,
    val rejectReason: String?,
)
