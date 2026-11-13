package com.nettwork.policethief.shared.dto

import com.nettwork.policethief.shared.model.GameEndReason
import com.nettwork.policethief.shared.model.GameEventType
import com.nettwork.policethief.shared.model.GameResult
import com.nettwork.policethief.shared.model.GameStatus
import com.nettwork.policethief.shared.model.MatchmakingStatus
import com.nettwork.policethief.shared.model.PlayerConnectionState
import com.nettwork.policethief.shared.model.PlayerRole
import com.nettwork.policethief.shared.model.PlayerStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * 아래 DTO 는 "계약만 정의된" API 용이다. 서버 구현은 아직 없으며(501 응답), 클라이언트는 Mock Repository 로 사용한다.
 * 구현 계획: docs/development/TODO.md
 */

// ---------------- 매칭 ----------------

/** POST /api/matchmaking 요청: 현재 위치로 대기열에 등록. */
@Serializable
data class JoinMatchmakingRequest(val location: LocationUpdateRequest)

/** 매칭 대기열 상태. GET /api/matchmaking/me, POST /api/matchmaking 응답. */
@Serializable
data class MatchmakingTicketDto(
    val userId: String,
    val status: MatchmakingStatus,
    val joinedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    /** 내 반경 500m 안에서 함께 기다리는 인원(나 포함). 다른 사람의 좌표는 절대 내려주지 않는다. */
    val nearbyWaitingCount: Int,
    /** status == MATCHED 일 때만 존재. */
    val gameId: String? = null,
)

// ---------------- 경기 ----------------

@Serializable
data class GameDto(
    val gameId: String,
    val status: GameStatus,
    val centerLatitude: Double,
    val centerLongitude: Double,
    val currentRadiusMeters: Double,
    val jailRadiusMeters: Double,
    val createdAtEpochMillis: Long,
    /** 준비시간(30초)이 끝나 경기가 시작되는 시각. */
    val startsAtEpochMillis: Long? = null,
    val endsAtEpochMillis: Long? = null,
    val endedAtEpochMillis: Long? = null,
    val result: GameResult? = null,
    val endReason: GameEndReason? = null,
    val players: List<GamePlayerDto> = emptyList(),
)

@Serializable
data class GamePlayerDto(
    val userId: String,
    val nickname: String,
    val role: PlayerRole,
    val status: PlayerStatus,
    /** 이 도둑을 체포한 경찰 userId (도둑만 해당). */
    val capturedBy: String? = null,
    val warningCount: Int = 0,
    val connectionState: PlayerConnectionState = PlayerConnectionState.CONNECTED,
)

/**
 * GET /api/games/{gameId}/locations 응답.
 * 서버가 요청자의 역할에 따라 걸러서 내려준다(경찰: 전원, 도둑: 도둑끼리).
 */
@Serializable
data class VisibleLocationsResponse(
    val gameId: String,
    val locations: List<LocationDto>,
)

@Serializable
data class GameEventDto(
    val eventId: Long,
    val gameId: String,
    val type: GameEventType,
    val data: JsonObject = JsonObject(emptyMap()),
    val createdAtEpochMillis: Long,
)

// ---------------- QR 체포 / 구출 ----------------

/**
 * GET /api/games/{gameId}/qr/me 응답. 경기 동안 고정된 토큰이며 경기가 끝나면 무효가 된다.
 * QR 이미지에는 [token] 문자열만 담는다(서버가 gameId·소유자를 DB 에서 대조하므로 위조·재사용 불가).
 */
@Serializable
data class QrTokenDto(
    val gameId: String,
    val userId: String,
    val token: String,
    val issuedAtEpochMillis: Long,
    /** null 이면 "경기 종료 시까지 유효". */
    val expiresAtEpochMillis: Long? = null,
)

/** POST /api/games/{gameId}/captures, /rescues 요청 본문. */
@Serializable
data class QrScanRequest(
    val token: String,
    /** 같은 스캔을 재전송할 때 중복 처리를 막기 위한 클라이언트 생성 ID. */
    val requestId: String,
    val scannedAtEpochMillis: Long,
)

/** 체포·구출 판정 결과. */
@Serializable
data class QrScanResultDto(
    val accepted: Boolean,
    val targetUserId: String? = null,
    val targetStatus: PlayerStatus? = null,
    /** accepted = false 일 때 사유. 예: OTHER_GAME, ALREADY_CAPTURED, ESCORT_LIMIT, NOT_ALLOWED */
    val rejectReason: String? = null,
)
