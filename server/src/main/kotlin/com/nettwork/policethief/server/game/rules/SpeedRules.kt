package com.nettwork.policethief.server.game.rules

import com.nettwork.policethief.server.game.GameSession
import com.nettwork.policethief.server.game.GameSettings
import com.nettwork.policethief.server.game.LocationSample
import com.nettwork.policethief.server.game.asTarget
import com.nettwork.policethief.server.game.eventData
import com.nettwork.policethief.shared.geo.GeoMath
import com.nettwork.policethief.shared.model.GameEndReason
import com.nettwork.policethief.shared.model.GameEventKeys
import com.nettwork.policethief.shared.model.GameEventType
import com.nettwork.policethief.shared.model.GameStatus
import com.nettwork.policethief.shared.model.LocationSource
import com.nettwork.policethief.shared.model.PlayerStatus
import com.nettwork.policethief.shared.rules.GameRules
import java.time.Instant

/** 연속된 두 위치 표본으로 본 이동 판정. */
sealed interface SpeedVerdict {
    /** 판정에 쓸 수 없는 표본(간격이 너무 짧거나 정확도가 나쁨). 기존 누적 상태를 유지한다. */
    data object Unusable : SpeedVerdict

    data object Normal : SpeedVerdict

    /** 순간 이동처럼 보이는 비정상 표본(GPS 오류로 본다). */
    data object Glitch : SpeedVerdict

    data class Over(val speedKmh: Double) : SpeedVerdict
}

/**
 * 과속 판정(순수 함수).
 * GPS 오차로 속도가 부풀려지지 않도록 두 지점 거리에서 두 표본의 정확도 합을 뺀 "보수적 거리"로 속도를 계산한다.
 * 예) 15초에 120m 이동, 정확도 각 5m → (120-10)/15 = 7.3m/s = 26.4km/h → 기준 초과 후보.
 */
object SpeedJudge {
    fun evaluate(previous: LocationSample, current: LocationSample, settings: GameSettings): SpeedVerdict {
        val intervalMillis = current.measuredAt.toEpochMilli() - previous.measuredAt.toEpochMilli()
        if (intervalMillis < settings.speedMinSampleIntervalMillis) return SpeedVerdict.Unusable
        if (previous.accuracyMeters > settings.speedMaxAccuracyMeters || current.accuracyMeters > settings.speedMaxAccuracyMeters) {
            return SpeedVerdict.Unusable
        }
        val seconds = intervalMillis / 1000.0
        val distance = GeoMath.distanceMeters(previous.point, current.point)
        if (distance / seconds * 3.6 > settings.teleportSpeedKmh) return SpeedVerdict.Glitch
        val conservative = (distance - previous.accuracyMeters - current.accuracyMeters).coerceAtLeast(0.0)
        val speedKmh = conservative / seconds * 3.6
        return if (speedKmh > GameRules.SPEED_LIMIT_KMH) SpeedVerdict.Over(speedKmh) else SpeedVerdict.Normal
    }
}

/**
 * 과속 경고 누적. 기준 초과가 [GameSettings.speedConsecutiveSamplesRequired] 번 연속되어야 경고 1회,
 * 경고 사이에는 쿨다운을 둔다. 경고 3회(확정 규칙)면 실격 → 무효 경기.
 */
internal fun GameSession.judgeSpeed(previous: LocationSample?, current: LocationSample, now: Instant) {
    if (core.status != GameStatus.PLAYING || previous == null) return
    if (mockLocationsExemptFromSpeed && (current.source == LocationSource.MOCK || previous.source == LocationSource.MOCK)) return
    val player = player(current.userId)?.takeIf { it.status != PlayerStatus.ELIMINATED } ?: return

    when (val verdict = SpeedJudge.evaluate(previous, current, settings)) {
        SpeedVerdict.Unusable -> Unit
        SpeedVerdict.Normal, SpeedVerdict.Glitch -> updatePlayer(player.userId) { copy(speedCandidateCount = 0) }
        is SpeedVerdict.Over -> {
            val count = (player.speedCandidateCount + 1).coerceAtMost(settings.speedConsecutiveSamplesRequired)
            val cooledDown = player.lastSpeedWarningAt?.let { now.toEpochMilli() - it.toEpochMilli() >= settings.speedWarningCooldownMillis } ?: true
            if (count < settings.speedConsecutiveSamplesRequired || !cooledDown) {
                updatePlayer(player.userId) { copy(speedCandidateCount = count) }
                return
            }
            val warnings = player.warningCount + 1
            updatePlayer(player.userId) { copy(speedCandidateCount = 0, warningCount = warnings, lastSpeedWarningAt = now) }
            record(
                GameEventType.SPEED_WARNING,
                now,
                data = eventData(*player.asTarget(), GameEventKeys.WARNING_COUNT to warnings, GameEventKeys.SPEED_KMH to verdict.speedKmh),
            )
            if (warnings >= GameRules.SPEED_WARNINGS_TO_DISQUALIFY) {
                disqualify(player.userId, GameEndReason.PLAYER_DISQUALIFIED_SPEEDING, now)
            }
        }
    }
}
