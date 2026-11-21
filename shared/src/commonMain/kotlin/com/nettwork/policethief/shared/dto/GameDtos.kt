package com.nettwork.policethief.shared.dto

import com.nettwork.policethief.shared.model.GameEndReason
import com.nettwork.policethief.shared.model.GameEventType
import com.nettwork.policethief.shared.model.GameResult
import com.nettwork.policethief.shared.model.GameStatus
import com.nettwork.policethief.shared.model.MatchmakingStatus
import com.nettwork.policethief.shared.model.MissionStatus
import com.nettwork.policethief.shared.model.PlayerConnectionState
import com.nettwork.policethief.shared.model.PlayerRole
import com.nettwork.policethief.shared.model.PlayerStatus
import com.nettwork.policethief.shared.model.ScanRejectReason
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * 매칭·경기·QR API 의 요청/응답. 모든 판정은 서버가 하고, 앱은 이 DTO 를 표시만 한다.
 * 시각은 UTC epoch millis. 화면의 남은 시간은 serverTimeEpochMillis 로 맞춘 "서버 시계" 기준으로 계산한다.
 * 문서: docs/api/API.md, docs/development/GAME_LOGIC.md
 */

// ---------------- 매칭 ----------------

/** POST /api/matchmaking 요청: 현재 위치로 대기열에 등록. */
@Serializable
data class JoinMatchmakingRequest(val location: LocationUpdateRequest)

/** 매칭 상태. GET /api/matchmaking/me, POST /api/matchmaking 응답, WebSocket matchmaking_update. */
@Serializable
data class MatchmakingTicketDto(
    val userId: String,
    val status: MatchmakingStatus,
    /** IDLE 이면 null. */
    val joinedAtEpochMillis: Long? = null,
    val expiresAtEpochMillis: Long? = null,
    /** 내 반경 500m 안에서 함께 기다리는 인원(나 포함). 다른 사람의 좌표는 절대 내려주지 않는다. */
    val nearbyWaitingCount: Int = 0,
    /** status == MATCHED 일 때만 존재. */
    val gameId: String? = null,
    val serverTimeEpochMillis: Long,
)

// ---------------- 경기 ----------------

@Serializable
data class GameDto(
    val gameId: String,
    val status: GameStatus,
    /** 서버가 상태를 바꿀 때마다 1씩 증가. 앱은 더 작은 버전의 응답을 무시해 순서 역전을 막는다. */
    val version: Long,
    val centerLatitude: Double,
    val centerLongitude: Double,
    val initialRadiusMeters: Double,
    val currentRadiusMeters: Double,
    val jailRadiusMeters: Double,
    val createdAtEpochMillis: Long,
    /** 준비시간(30초)이 끝나 경기가 시작되는(된) 시각. 서버가 확정한다. */
    val startsAtEpochMillis: Long,
    val startedAtEpochMillis: Long? = null,
    /** 경기 종료 예정 시각(시작 + 30분). */
    val endsAtEpochMillis: Long,
    val endedAtEpochMillis: Long? = null,
    val result: GameResult? = null,
    val endReason: GameEndReason? = null,
    val players: List<GamePlayerDto> = emptyList(),
    /** 아직 적용되지 않은 다음 구역 축소. 없으면 null. */
    val nextZoneShrink: ZoneShrinkDto? = null,
    /** 요청자에게 공개되는 미션(도둑에게만 내려간다). */
    val missions: List<MissionDto> = emptyList(),
    val features: GameFeaturesDto = GameFeaturesDto(),
    val serverTimeEpochMillis: Long,
)

@Serializable
data class GamePlayerDto(
    val userId: String,
    val nickname: String,
    val role: PlayerRole,
    val status: PlayerStatus,
    /** 이 도둑을 체포(호송)한 경찰 userId (도둑만 해당, 수감 후에도 기록 유지, 구출되면 null). */
    val capturedBy: String? = null,
    /** 이 경찰이 지금 호송 중인 도둑 userId (경찰만 해당). null 이면 활동 중. */
    val escortingUserId: String? = null,
    val warningCount: Int = 0,
    val connectionState: PlayerConnectionState = PlayerConnectionState.CONNECTED,
    val disconnectedAtEpochMillis: Long? = null,
    /** 구역 밖으로 판정되기 시작한 시각(구역 안이면 null). */
    val outOfZoneSinceEpochMillis: Long? = null,
)

@Serializable
data class ZoneShrinkDto(
    val stage: Int,
    val scheduledAtEpochMillis: Long,
    val radiusMeters: Double,
)

@Serializable
data class MissionDto(
    val missionId: String,
    val status: MissionStatus,
    val targetLatitude: Double,
    val targetLongitude: Double,
    val targetRadiusMeters: Double,
    val startedAtEpochMillis: Long,
    val deadlineAtEpochMillis: Long,
    val completedByUserId: String? = null,
    val endedAtEpochMillis: Long? = null,
)

/** 서버 설정으로 켜고 끄는 선택 기능. */
@Serializable
data class GameFeaturesDto(
    val ringThiefPhone: Boolean = false,
    val ringCooldownMillis: Long = 0,
)

/**
 * GET /api/games/{gameId}/locations 응답.
 * 서버가 요청자의 역할에 따라 걸러서 내려준다(경찰: 전원, 도둑: 도둑끼리). 요청자 본인 위치도 포함된다.
 */
@Serializable
data class VisibleLocationsResponse(
    val gameId: String,
    val locations: List<LocationDto>,
    val serverTimeEpochMillis: Long,
)

/**
 * WebSocket·REST 공통 이벤트 형식.
 * - eventId: 저장된 이벤트의 고유 번호(증가). 0 이면 저장하지 않는 일시 이벤트.
 * - gameVersion: 이 이벤트가 반영된 뒤의 경기 버전.
 * - data: 이벤트별 정보([com.nettwork.policethief.shared.model.GameEventKeys]).
 */
@Serializable
data class GameEventDto(
    val eventId: Long,
    val gameId: String,
    val type: GameEventType,
    val gameVersion: Long = 0,
    val data: JsonObject = JsonObject(emptyMap()),
    val createdAtEpochMillis: Long,
)

/**
 * GET /api/games/{gameId}/state: 재접속·앱 재실행 시 한 번에 받는 전체 상태.
 * game + 공개 허용 위치 + 최근 이벤트. WebSocket 으로 받은 내용과 같은 규칙으로 걸러진다.
 */
@Serializable
data class GameStateDto(
    val game: GameDto,
    val locations: List<LocationDto>,
    val recentEvents: List<GameEventDto>,
    val lastEventId: Long,
)

/** GET /api/games/{gameId}/result: 이번 경기의 결과와 플레이어별 주요 활동(누적 점수·랭킹 없음). */
@Serializable
data class GameResultDto(
    val gameId: String,
    val status: GameStatus,
    val result: GameResult? = null,
    val endReason: GameEndReason? = null,
    val startedAtEpochMillis: Long? = null,
    val endedAtEpochMillis: Long? = null,
    val players: List<PlayerResultDto>,
)

@Serializable
data class PlayerResultDto(
    val userId: String,
    val nickname: String,
    val role: PlayerRole,
    val finalStatus: PlayerStatus,
    val capturesMade: Int,
    val rescuesMade: Int,
    val timesCaptured: Int,
    val missionsCompleted: Int,
    val speedWarnings: Int,
)

/** POST /api/games/{gameId}/ring 요청(Feature Flag). */
@Serializable
data class RingThiefRequest(val targetUserId: String)

@Serializable
data class RingThiefResultDto(
    val accepted: Boolean,
    val rejectReason: String? = null,
    val nextAvailableAtEpochMillis: Long? = null,
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

/** 체포·구출 판정 결과. 같은 requestId 로 다시 보내면 처음 판정 결과를 그대로 돌려준다. */
@Serializable
data class QrScanResultDto(
    val accepted: Boolean,
    val targetUserId: String? = null,
    val targetNickname: String? = null,
    val targetStatus: PlayerStatus? = null,
    /** accepted = false 일 때 사유. */
    val rejectReason: ScanRejectReason? = null,
    /** 판정 직후 경기 상태(마지막 도둑 체포 시 FINISHED). */
    val gameStatus: GameStatus? = null,
    val gameResult: GameResult? = null,
)
