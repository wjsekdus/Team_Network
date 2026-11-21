package com.nettwork.policethief.server.game.rules

import com.nettwork.policethief.server.game.GameSession
import com.nettwork.policethief.server.game.LocationSample
import com.nettwork.policethief.server.game.Mission
import com.nettwork.policethief.server.game.Visibility
import com.nettwork.policethief.server.game.asActor
import com.nettwork.policethief.server.game.eventData
import com.nettwork.policethief.shared.geo.GeoMath
import com.nettwork.policethief.shared.model.GameEventKeys
import com.nettwork.policethief.shared.model.GameEventType
import com.nettwork.policethief.shared.model.GameStatus
import com.nettwork.policethief.shared.model.MissionStatus
import com.nettwork.policethief.shared.model.PlayerRole
import com.nettwork.policethief.shared.model.PlayerStatus
import java.time.Instant
import java.util.UUID
import kotlin.math.PI

/*
 * 도둑 미션: 제한 시간 안에 지정 구역에 도착하면 성공, 못 하면 실패(다음 구역 축소가 앞당겨짐).
 * 발생 주기·구역 크기·제한 시간·가속 정도는 팀 미확정 값이라 GameSettings(PROVISIONAL)로 분리했다.
 * 미션 정보는 도둑 팀에게만 공개한다(Visibility.THIEF).
 */

internal fun GameSession.startMission(at: Instant) {
    if (core.status != GameStatus.PLAYING || activeMission != null) return
    val deadline = at.plusMillis(settings.missionDurationMillis)
    if (deadline.isAfter(core.endsAt)) {
        // 경기 종료 전에 끝낼 수 없는 미션은 만들지 않는다.
        updateCore { copy(nextMissionAt = null) }
        return
    }
    // 마감 시점의 (축소된) 구역 안쪽, 감옥에서 일정 거리 이상 떨어진 곳에 목표를 둔다.
    val maxDistance = (radiusAt(deadline) - settings.missionRadiusMeters).coerceAtLeast(0.0)
    val minDistance = settings.missionMinDistanceFromJailMeters.coerceAtMost(maxDistance)
    val distance = minDistance + random.nextDouble() * (maxDistance - minDistance)
    val target = GeoMath.destination(core.center, distance, random.nextDouble() * 2 * PI)
    val mission = Mission(UUID.randomUUID(), target, settings.missionRadiusMeters, at, deadline)
    addMission(mission)
    updateCore { copy(nextMissionAt = null) }
    record(
        GameEventType.MISSION_STARTED,
        at,
        Visibility.THIEF,
        data = eventData(
            GameEventKeys.MISSION_ID to mission.id,
            GameEventKeys.RADIUS_METERS to mission.radiusMeters,
            GameEventKeys.AT_EPOCH_MILLIS to deadline,
        ),
    )
}

/** 활동 중인 도둑이 미션 구역 안에 들어오면 성공. 정확도가 나쁜 위치는 쓰지 않는다. */
internal fun GameSession.judgeMission(sample: LocationSample, now: Instant) {
    if (core.status != GameStatus.PLAYING) return
    val mission = activeMission ?: return
    val thief = player(sample.userId)?.takeIf { it.role == PlayerRole.THIEF && it.status == PlayerStatus.ACTIVE } ?: return
    if (sample.accuracyMeters > settings.judgementMaxAccuracyMeters) return
    if (now.isAfter(mission.deadlineAt)) return
    if (GeoMath.distanceMeters(sample.point, mission.target) > mission.radiusMeters) return

    updateMission(mission.id) { copy(status = MissionStatus.COMPLETED, completedBy = thief.userId, endedAt = now) }
    updateCore { copy(nextMissionAt = now.plusMillis(settings.missionIntervalMillis)) }
    record(GameEventType.MISSION_COMPLETED, now, Visibility.THIEF, data = eventData(GameEventKeys.MISSION_ID to mission.id, *thief.asActor()))
}

internal fun GameSession.failMission(missionId: UUID, at: Instant) {
    val mission = missions.firstOrNull { it.id == missionId && it.status == MissionStatus.ACTIVE } ?: return
    updateMission(mission.id) { copy(status = MissionStatus.FAILED, endedAt = at) }
    updateCore { copy(nextMissionAt = at.plusMillis(settings.missionIntervalMillis)) }
    record(GameEventType.MISSION_FAILED, at, Visibility.THIEF, data = eventData(GameEventKeys.MISSION_ID to mission.id))
    advanceNextShrink(at)
}
