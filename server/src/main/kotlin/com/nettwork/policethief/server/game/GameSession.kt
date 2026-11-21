package com.nettwork.policethief.server.game

import com.nettwork.policethief.shared.dto.GameDto
import com.nettwork.policethief.shared.dto.GameFeaturesDto
import com.nettwork.policethief.shared.dto.GamePlayerDto
import com.nettwork.policethief.shared.dto.LocationDto
import com.nettwork.policethief.shared.dto.MissionDto
import com.nettwork.policethief.shared.dto.ZoneShrinkDto
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

/*
 * 경기 한 판의 메모리 모델(Aggregate).
 *
 * GameEngine 이 DB 에서 읽어 GameSession 을 만들고 → rules/ 의 규칙 함수가 상태를 바꾸고 → GameStore 가 저장한다.
 * 규칙 함수는 DB·네트워크를 모르는 순수 Kotlin 이라 단위 테스트로 바로 검증할 수 있다.
 * 모든 변경은 update... 함수를 거치므로 "무엇이 바뀌었는지(changed)"를 놓치지 않는다.
 */

/** 이벤트 공개 범위. USER 는 targetUserId 한 명에게만. */
enum class Visibility { ALL, POLICE, THIEF, USER }

/** 규칙이 기록한 이벤트(저장 전). */
data class RecordedEvent(
    val type: GameEventType,
    val visibility: Visibility,
    val targetUserId: UUID?,
    val data: JsonObject,
    val at: Instant,
)

/** 서버가 받은 위치 표본 한 건. measuredAt 은 단말 측정 시각, receivedAt 은 서버 수신 시각. */
data class LocationSample(
    val userId: UUID,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val measuredAt: Instant,
    val receivedAt: Instant,
    val source: LocationSource,
) {
    val point: GeoPoint get() = GeoPoint(latitude, longitude)

    fun toDto() = LocationDto(
        userId = userId.toString(),
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracyMeters,
        measuredAtEpochMillis = measuredAt.toEpochMilli(),
        receivedAtEpochMillis = receivedAt.toEpochMilli(),
        source = source,
    )
}

data class GameCore(
    val id: UUID,
    val status: GameStatus,
    val version: Long,
    val center: GeoPoint,
    val initialRadiusMeters: Double,
    val currentRadiusMeters: Double,
    val jailRadiusMeters: Double,
    val createdAt: Instant,
    val startsAt: Instant,
    val startedAt: Instant?,
    val endsAt: Instant,
    val endedAt: Instant?,
    val result: GameResult?,
    val endReason: GameEndReason?,
    val nextMissionAt: Instant?,
)

data class PlayerState(
    val userId: UUID,
    val nickname: String,
    val role: PlayerRole,
    val joinedAt: Instant,
    val status: PlayerStatus = PlayerStatus.ACTIVE,
    /** 도둑: 체포한 경찰. 수감 후에도 기록으로 남고, 구출되면 null. */
    val capturedBy: UUID? = null,
    /** 경찰: 지금 호송 중인 도둑(최대 1명). */
    val escortingUserId: UUID? = null,
    val warningCount: Int = 0,
    val connection: PlayerConnectionState = PlayerConnectionState.CONNECTED,
    val disconnectedAt: Instant? = null,
    val outOfZoneSince: Instant? = null,
    val speedCandidateCount: Int = 0,
    val lastSpeedWarningAt: Instant? = null,
    val lastRingAt: Instant? = null,
)

data class ZoneStage(
    val stageNo: Int,
    val scheduledAt: Instant,
    val radiusMeters: Double,
    val warnedAt: Instant? = null,
    val appliedAt: Instant? = null,
)

data class Mission(
    val id: UUID,
    val target: GeoPoint,
    val radiusMeters: Double,
    val startedAt: Instant,
    val deadlineAt: Instant,
    val status: MissionStatus = MissionStatus.ACTIVE,
    val completedBy: UUID? = null,
    val endedAt: Instant? = null,
)

class GameSession(
    core: GameCore,
    players: List<PlayerState>,
    stages: List<ZoneStage>,
    missions: List<Mission>,
    latestSamples: Map<UUID, LocationSample>,
    val settings: GameSettings,
    /** 개발 서버(MOCK 허용)에서 테스트 좌표 이동을 과속으로 판정하지 않기 위한 스위치(지시서 20.5). */
    val mockLocationsExemptFromSpeed: Boolean,
    val random: Random = Random.Default,
) {
    var core: GameCore = core
        private set
    private val playerMap = LinkedHashMap(players.associateBy { it.userId })
    private val stageMap = sortedMapOf<Int, ZoneStage>().apply { stages.forEach { put(it.stageNo, it) } }
    private val missionMap = LinkedHashMap(missions.associateBy { it.id })

    /** 각 참가자의 마지막(측정 시각 기준 가장 최근) 위치. 판정에 쓴다. */
    val latest: MutableMap<UUID, LocationSample> = latestSamples.toMutableMap()

    /** DB 에 저장하고 공개 범위에 따라 전달할 이벤트. */
    val events = mutableListOf<RecordedEvent>()

    /** 저장하지 않고 접속자에게만 보내는 일시 이벤트(JAIL_CHECK_PENDING 등). */
    val transientEvents = mutableListOf<RecordedEvent>()

    /** 이번 처리에서 새로 시작된 호송(경찰, 도둑) / 수감으로 끝난 호송의 도둑. GameStore 가 escorts 기록에 반영한다. */
    val escortStarts = mutableListOf<Pair<UUID, UUID>>()
    val escortJailed = mutableListOf<UUID>()
    val newMissionIds = mutableSetOf<UUID>()

    /** 불러올 때 이미 끝난 경기였는지(종료 처리 부수 효과를 한 번만 하기 위함). */
    val wasEndedAtLoad: Boolean = core.status.isEnded()

    var changed: Boolean = false
        private set

    val players: List<PlayerState> get() = playerMap.values.toList()
    val stages: List<ZoneStage> get() = stageMap.values.toList()
    val missions: List<Mission> get() = missionMap.values.toList()
    val isEnded: Boolean get() = core.status.isEnded()
    val activeMission: Mission? get() = missionMap.values.firstOrNull { it.status == MissionStatus.ACTIVE }

    fun player(userId: UUID): PlayerState? = playerMap[userId]
    fun thieves(): List<PlayerState> = playerMap.values.filter { it.role == PlayerRole.THIEF }
    fun police(): List<PlayerState> = playerMap.values.filter { it.role == PlayerRole.POLICE }

    fun updateCore(transform: GameCore.() -> GameCore) {
        val next = core.transform()
        if (next != core) {
            core = next
            changed = true
        }
    }

    fun updatePlayer(userId: UUID, transform: PlayerState.() -> PlayerState) {
        val current = playerMap.getValue(userId)
        val next = current.transform()
        if (next != current) {
            playerMap[userId] = next
            changed = true
        }
    }

    fun updateStage(stageNo: Int, transform: ZoneStage.() -> ZoneStage) {
        val current = stageMap.getValue(stageNo)
        val next = current.transform()
        if (next != current) {
            stageMap[stageNo] = next
            changed = true
        }
    }

    fun addMission(mission: Mission) {
        missionMap[mission.id] = mission
        newMissionIds += mission.id
        changed = true
    }

    fun updateMission(id: UUID, transform: Mission.() -> Mission) {
        val current = missionMap.getValue(id)
        val next = current.transform()
        if (next != current) {
            missionMap[id] = next
            changed = true
        }
    }

    fun record(type: GameEventType, at: Instant, visibility: Visibility = Visibility.ALL, target: UUID? = null, data: JsonObject = JsonObject(emptyMap())) {
        events += RecordedEvent(type, visibility, target, data, at)
    }

    fun recordTransient(type: GameEventType, at: Instant, target: UUID, data: JsonObject) {
        transientEvents += RecordedEvent(type, Visibility.USER, target, data, at)
    }

    /** 이 이벤트를 받을 수 있는 참가자. */
    fun recipientsOf(event: RecordedEvent): List<UUID> = when (event.visibility) {
        Visibility.ALL -> playerMap.keys.toList()
        Visibility.POLICE -> police().map { it.userId }
        Visibility.THIEF -> thieves().map { it.userId }
        Visibility.USER -> listOfNotNull(event.targetUserId?.takeIf { it in playerMap })
    }

    /** 요청자에게 공개되는 위치(경찰: 전원, 도둑: 도둑끼리). 서버 응답 단계에서 거르므로 앱이 숨길 필요가 없다. */
    fun visibleLocationsFor(viewer: UUID): List<LocationSample> {
        val viewerRole = player(viewer)?.role ?: return emptyList()
        return latest.values
            .filter { sample -> canSee(viewerRole, player(sample.userId)?.role ?: return@filter false) }
            .sortedBy { it.userId }
    }

    fun toDto(viewer: UUID, now: Instant): GameDto {
        val viewerRole = player(viewer)?.role
        val nextStage = if (isEnded) null else stageMap.values.firstOrNull { it.appliedAt == null }
        return GameDto(
            gameId = core.id.toString(),
            status = core.status,
            version = core.version,
            centerLatitude = core.center.latitude,
            centerLongitude = core.center.longitude,
            initialRadiusMeters = core.initialRadiusMeters,
            currentRadiusMeters = core.currentRadiusMeters,
            jailRadiusMeters = core.jailRadiusMeters,
            createdAtEpochMillis = core.createdAt.toEpochMilli(),
            startsAtEpochMillis = core.startsAt.toEpochMilli(),
            startedAtEpochMillis = core.startedAt?.toEpochMilli(),
            endsAtEpochMillis = core.endsAt.toEpochMilli(),
            endedAtEpochMillis = core.endedAt?.toEpochMilli(),
            result = core.result,
            endReason = core.endReason,
            players = playerMap.values.map { it.toDto() },
            nextZoneShrink = nextStage?.let { ZoneShrinkDto(it.stageNo, it.scheduledAt.toEpochMilli(), it.radiusMeters) },
            // 미션 위치는 도둑 팀 정보다. 경찰에게는 내려주지 않는다.
            missions = if (viewerRole == PlayerRole.THIEF) missionMap.values.map { it.toDto() } else emptyList(),
            features = GameFeaturesDto(settings.ringThiefPhoneEnabled, settings.ringCooldownMillis),
            serverTimeEpochMillis = now.toEpochMilli(),
        )
    }

    companion object {
        /** 위치 공개 정책(지시서 3.2, 9.3, 9.4): 경찰은 모두를, 도둑은 도둑만 본다. */
        fun canSee(viewerRole: PlayerRole, ownerRole: PlayerRole): Boolean =
            viewerRole == PlayerRole.POLICE || ownerRole == PlayerRole.THIEF
    }
}

fun GameStatus.isEnded(): Boolean = this == GameStatus.FINISHED || this == GameStatus.CANCELLED

fun PlayerState.toDto() = GamePlayerDto(
    userId = userId.toString(),
    nickname = nickname,
    role = role,
    status = status,
    capturedBy = capturedBy?.toString(),
    escortingUserId = escortingUserId?.toString(),
    warningCount = warningCount,
    connectionState = connection,
    disconnectedAtEpochMillis = disconnectedAt?.toEpochMilli(),
    outOfZoneSinceEpochMillis = outOfZoneSince?.toEpochMilli(),
)

fun Mission.toDto() = MissionDto(
    missionId = id.toString(),
    status = status,
    targetLatitude = target.latitude,
    targetLongitude = target.longitude,
    targetRadiusMeters = radiusMeters,
    startedAtEpochMillis = startedAt.toEpochMilli(),
    deadlineAtEpochMillis = deadlineAt.toEpochMilli(),
    completedByUserId = completedBy?.toString(),
    endedAtEpochMillis = endedAt?.toEpochMilli(),
)

/** 이벤트 data JSON 을 만든다. null 값은 넣지 않는다. */
fun eventData(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    pairs.forEach { (key, value) ->
        when (value) {
            null -> Unit
            is String -> put(key, value)
            is Number -> put(key, value)
            is Boolean -> put(key, value)
            is UUID -> put(key, value.toString())
            is Enum<*> -> put(key, value.name)
            is Instant -> put(key, value.toEpochMilli())
            else -> error("이벤트 data 에 넣을 수 없는 타입: ${value::class}")
        }
    }
}

/** 대상 참가자를 나타내는 공통 data 항목. */
fun PlayerState.asTarget(): Array<Pair<String, Any?>> = arrayOf(GameEventKeys.USER_ID to userId, GameEventKeys.NICKNAME to nickname)

fun PlayerState.asActor(): Array<Pair<String, Any?>> = arrayOf(GameEventKeys.BY_USER_ID to userId, GameEventKeys.BY_NICKNAME to nickname)
