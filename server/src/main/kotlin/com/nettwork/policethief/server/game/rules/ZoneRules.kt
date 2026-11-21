package com.nettwork.policethief.server.game.rules

import com.nettwork.policethief.server.game.GameSession
import com.nettwork.policethief.server.game.LocationSample
import com.nettwork.policethief.server.game.Visibility
import com.nettwork.policethief.server.game.asTarget
import com.nettwork.policethief.server.game.eventData
import com.nettwork.policethief.shared.geo.GeoMath
import com.nettwork.policethief.shared.model.GameEventKeys
import com.nettwork.policethief.shared.model.GameEventType
import com.nettwork.policethief.shared.model.GameStatus
import com.nettwork.policethief.shared.model.PlayerStatus
import java.time.Instant

/*
 * 게임 구역: 이탈 경고와 단계적 축소. 중심은 항상 초기 중심(=감옥)이며 반경만 줄어든다.
 */

/**
 * 구역 이탈 판정. GPS 오차로 억울하게 이탈 판정되지 않도록 "정확도만큼 안쪽으로 봐 준" 거리로 판단한다.
 * 정확도가 상한보다 나쁘면 판정하지 않는다(이전 상태 유지).
 */
internal fun GameSession.judgeZone(sample: LocationSample, now: Instant) {
    if (core.status != GameStatus.PLAYING) return
    val player = player(sample.userId)?.takeIf { it.status != PlayerStatus.ELIMINATED } ?: return
    if (sample.accuracyMeters > settings.judgementMaxAccuracyMeters) return
    val distance = GeoMath.distanceMeters(sample.point, core.center)
    val outside = distance - sample.accuracyMeters > core.currentRadiusMeters
    when {
        outside && player.outOfZoneSince == null -> {
            updatePlayer(player.userId) { copy(outOfZoneSince = now) }
            val deadline = if (settings.zoneOutDisqualifyMillis > 0) now.plusMillis(settings.zoneOutDisqualifyMillis) else null
            record(
                GameEventType.ZONE_OUT_WARNING,
                now,
                Visibility.USER,
                player.userId,
                eventData(
                    *player.asTarget(),
                    GameEventKeys.DISTANCE_METERS to distance,
                    GameEventKeys.RADIUS_METERS to core.currentRadiusMeters,
                    GameEventKeys.AT_EPOCH_MILLIS to deadline,
                ),
            )
        }
        !outside && player.outOfZoneSince != null -> {
            updatePlayer(player.userId) { copy(outOfZoneSince = null) }
            record(GameEventType.ZONE_RETURNED, now, Visibility.USER, player.userId, eventData(*player.asTarget()))
        }
    }
}

internal fun GameSession.warnZoneShrink(stageNo: Int, at: Instant) {
    val stage = stages.first { it.stageNo == stageNo }
    updateStage(stageNo) { copy(warnedAt = at) }
    record(
        GameEventType.ZONE_SHRINK_WARNING,
        at,
        data = eventData(
            GameEventKeys.STAGE to stageNo,
            GameEventKeys.RADIUS_METERS to stage.radiusMeters,
            GameEventKeys.AT_EPOCH_MILLIS to stage.scheduledAt,
        ),
    )
}

/** 축소 적용 후 모든 참가자의 마지막 위치로 이탈 여부를 다시 판정한다. */
internal fun GameSession.shrinkZone(stageNo: Int, at: Instant) {
    val stage = stages.first { it.stageNo == stageNo }
    updateStage(stageNo) { copy(appliedAt = at, warnedAt = warnedAt ?: at) }
    updateCore { copy(currentRadiusMeters = stage.radiusMeters) }
    record(GameEventType.ZONE_SHRUNK, at, data = eventData(GameEventKeys.STAGE to stageNo, GameEventKeys.RADIUS_METERS to stage.radiusMeters))
    latest.values.toList().forEach { judgeZone(it, at) }
}

/**
 * 미션 실패로 다음 축소를 앞당긴다. 다만 예고 없이 바로 줄어들지 않도록 최소 [GameSettings.zoneShrinkWarningMillis] 뒤로 잡고,
 * 일정이 바뀌었으므로 예고를 다시 보낸다(warnedAt 초기화).
 */
internal fun GameSession.advanceNextShrink(at: Instant) {
    val next = stages.firstOrNull { it.appliedAt == null } ?: return
    val earliest = at.plusMillis(settings.zoneShrinkWarningMillis)
    val advanced = next.scheduledAt.minusMillis(settings.missionFailureShrinkAdvanceMillis).let { if (it.isBefore(earliest)) earliest else it }
    if (advanced.isBefore(next.scheduledAt)) {
        updateStage(next.stageNo) { copy(scheduledAt = advanced, warnedAt = null) }
    }
}

/** 특정 시각에 적용되어 있을 반경(그 시각까지 예정된 축소 반영). 미션 지점이 곧 구역 밖이 되지 않게 할 때 쓴다. */
internal fun GameSession.radiusAt(time: Instant): Double =
    stages.filter { it.appliedAt == null && !it.scheduledAt.isAfter(time) }.minOfOrNull { it.radiusMeters }
        ?.coerceAtMost(core.currentRadiusMeters)
        ?: core.currentRadiusMeters
