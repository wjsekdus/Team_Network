package com.nettwork.policethief.server.game.rules

import com.nettwork.policethief.server.game.GameSession
import com.nettwork.policethief.server.game.GameSettings
import com.nettwork.policethief.server.game.LocationSample
import com.nettwork.policethief.server.game.asActor
import com.nettwork.policethief.server.game.asTarget
import com.nettwork.policethief.server.game.eventData
import com.nettwork.policethief.shared.geo.GeoMath
import com.nettwork.policethief.shared.geo.GeoPoint
import com.nettwork.policethief.shared.model.GameEventKeys
import com.nettwork.policethief.shared.model.GameEventType
import com.nettwork.policethief.shared.model.GameStatus
import com.nettwork.policethief.shared.model.PlayerRole
import com.nettwork.policethief.shared.model.PlayerStatus
import java.time.Instant
import java.util.UUID

/** 감옥 판정 결과. */
enum class JailVerdict {
    /** 두 사람 모두 감옥 10m 안, 위치가 정확하고 최신 → 수감 확정. */
    CONFIRMED,

    /** 감옥 안으로 보이지만 정확도·최신성이 부족해 보류(GPS 를 다시 잡도록 안내). */
    UNRELIABLE,

    /** 한 사람 이상이 감옥 밖이거나 위치가 없음. */
    OUTSIDE,
}

/**
 * 자동 수감 판정(순수 함수). 감옥 반경(10m)은 줄이거나 늘리지 않고 그대로 쓰고,
 * 대신 "믿을 수 있는 표본"(정확도 상한·최신성, GAME_CONFIG.md)일 때만 확정한다.
 */
object JailJudge {
    fun evaluate(police: LocationSample?, thief: LocationSample?, jail: GeoPoint, jailRadiusMeters: Double, settings: GameSettings, now: Instant): JailVerdict {
        if (police == null || thief == null) return JailVerdict.OUTSIDE
        val bothInside = listOf(police, thief).all { GeoMath.distanceMeters(it.point, jail) <= jailRadiusMeters }
        if (!bothInside) return JailVerdict.OUTSIDE
        val reliable = listOf(police, thief).all {
            it.accuracyMeters <= settings.jailMaxAccuracyMeters && now.toEpochMilli() - it.measuredAt.toEpochMilli() <= settings.jailSampleMaxAgeMillis
        }
        return if (reliable) JailVerdict.CONFIRMED else JailVerdict.UNRELIABLE
    }
}

/**
 * [movedUserId] 의 위치가 갱신될 때 호출. 관련된 호송(경찰 ↔ 도둑) 한 쌍을 판정한다.
 * 수감 처리는 상태가 ESCORTED 일 때만 일어나므로 같은 위치가 여러 번 와도 한 번만 확정된다.
 */
internal fun GameSession.judgeJail(movedUserId: UUID, now: Instant) {
    if (core.status != GameStatus.PLAYING) return
    val moved = player(movedUserId) ?: return
    val (policeId, thiefId) = when {
        moved.role == PlayerRole.POLICE -> movedUserId to (moved.escortingUserId ?: return)
        moved.status == PlayerStatus.ESCORTED -> (moved.capturedBy ?: return) to movedUserId
        else -> return
    }
    val police = player(policeId) ?: return
    val thief = player(thiefId)?.takeIf { it.status == PlayerStatus.ESCORTED && it.capturedBy == policeId } ?: return

    when (JailJudge.evaluate(latest[policeId], latest[thiefId], core.center, core.jailRadiusMeters, settings, now)) {
        JailVerdict.OUTSIDE -> Unit
        JailVerdict.UNRELIABLE -> {
            val data = eventData(*thief.asTarget(), GameEventKeys.ACCURACY_METERS to settings.jailMaxAccuracyMeters)
            recordTransient(GameEventType.JAIL_CHECK_PENDING, now, policeId, data)
            recordTransient(GameEventType.JAIL_CHECK_PENDING, now, thiefId, data)
        }
        JailVerdict.CONFIRMED -> {
            updatePlayer(thiefId) { copy(status = PlayerStatus.JAILED) }
            updatePlayer(policeId) { copy(escortingUserId = null) }
            escortJailed += thiefId
            record(GameEventType.THIEF_JAILED, now, data = eventData(*thief.asTarget(), *police.asActor()))
        }
    }
}
