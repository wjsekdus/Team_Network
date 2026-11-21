package com.nettwork.policethief.domain.model

import com.nettwork.policethief.shared.model.GameEndReason
import com.nettwork.policethief.shared.model.GameEventType
import com.nettwork.policethief.shared.model.GameResult
import com.nettwork.policethief.shared.model.GameStatus
import com.nettwork.policethief.shared.model.LocationSource
import com.nettwork.policethief.shared.model.MatchmakingStatus
import com.nettwork.policethief.shared.model.MissionStatus
import com.nettwork.policethief.shared.model.PlayerConnectionState
import com.nettwork.policethief.shared.model.PlayerRole
import com.nettwork.policethief.shared.model.PlayerStatus
import com.nettwork.policethief.shared.model.ScanRejectReason

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

/** 매칭 상태. IDLE 이면 joinedAt/expiresAt 은 0. 시각은 서버 시계 기준. */
data class MatchmakingTicket(
    val status: MatchmakingStatus,
    val joinedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val nearbyWaitingCount: Int,
    val gameId: String?,
    val serverTimeEpochMillis: Long = 0,
)

/** 서버가 확정한 경기 정보. 앱은 이 값을 표시만 하고 판정하지 않는다. */
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
    /** 서버 상태 변경 순서. 더 작은 버전의 응답은 무시한다(순서 역전 방지). */
    val version: Long = 0,
    val initialRadiusMeters: Double = currentRadiusMeters,
    val startedAtEpochMillis: Long? = null,
    val nextZoneShrink: ZoneShrink? = null,
    /** 도둑에게만 내려온다(서버가 경찰에게는 빈 목록을 준다). */
    val missions: List<Mission> = emptyList(),
    val ringFeatureEnabled: Boolean = false,
    val serverTimeEpochMillis: Long = 0,
) {
    fun player(userId: String): GamePlayer? = players.firstOrNull { it.userId == userId }

    val isEnded: Boolean get() = status == GameStatus.FINISHED || status == GameStatus.CANCELLED
    val activeMission: Mission? get() = missions.firstOrNull { it.status == MissionStatus.ACTIVE }

    /** 체포 상태(호송 중 + 수감)인 도둑 수. 경찰 승리 판정과 같은 기준. */
    val capturedThiefCount: Int
        get() = players.count { it.role == PlayerRole.THIEF && (it.status == PlayerStatus.ESCORTED || it.status == PlayerStatus.JAILED) }
}

data class GamePlayer(
    val userId: String,
    val nickname: String,
    val role: PlayerRole,
    val status: PlayerStatus,
    val capturedBy: String?,
    val warningCount: Int,
    val connectionState: PlayerConnectionState,
    /** 경찰이 지금 호송 중인 도둑. null 이면 활동 중. */
    val escortingUserId: String? = null,
    val disconnectedAtEpochMillis: Long? = null,
    /** 서버가 구역 밖으로 판정한 시각(구역 안이면 null). */
    val outOfZoneSinceEpochMillis: Long? = null,
)

data class ZoneShrink(val stage: Int, val scheduledAtEpochMillis: Long, val radiusMeters: Double)

data class Mission(
    val missionId: String,
    val status: MissionStatus,
    val targetLatitude: Double,
    val targetLongitude: Double,
    val targetRadiusMeters: Double,
    val startedAtEpochMillis: Long,
    val deadlineAtEpochMillis: Long,
    val completedByUserId: String?,
)

/** 서버 이벤트. eventId = 0 이면 저장되지 않는 일시 이벤트(중복 검사 대상 아님). */
data class GameEvent(
    val eventId: Long,
    val gameId: String,
    val type: GameEventType,
    val createdAtEpochMillis: Long,
    val gameVersion: Long = 0,
    /** 이벤트 정보(GameEventKeys). 값은 문자열로 바꿔 보관한다. */
    val data: Map<String, String> = emptyMap(),
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
    val rejectReason: ScanRejectReason?,
    val targetNickname: String? = null,
    val gameStatus: GameStatus? = null,
    val gameResult: GameResult? = null,
)

/** 재접속·앱 재실행 때 한 번에 받는 경기 전체 상태(서버가 역할별로 걸러 준 위치 포함). */
data class GameSnapshot(
    val game: Game,
    val locations: List<PlayerLocation>,
    val recentEvents: List<GameEvent>,
    val lastEventId: Long,
)

/** 경기 결과 화면용. 이번 경기의 기록만 있다(누적 점수·랭킹 없음). */
data class GameResultSummary(
    val gameId: String,
    val status: GameStatus,
    val result: GameResult?,
    val endReason: GameEndReason?,
    val startedAtEpochMillis: Long?,
    val endedAtEpochMillis: Long?,
    val players: List<PlayerResult>,
)

data class PlayerResult(
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
