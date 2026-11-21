package com.nettwork.policethief.server.game.rules

import com.nettwork.policethief.server.game.GameSession
import com.nettwork.policethief.shared.model.GameEndReason
import com.nettwork.policethief.shared.model.GameResult
import com.nettwork.policethief.shared.model.GameStatus
import com.nettwork.policethief.shared.model.PlayerConnectionState
import com.nettwork.policethief.shared.model.PlayerStatus
import com.nettwork.policethief.shared.rules.GameRules
import java.time.Instant
import java.util.UUID

/*
 * 시간 기반 판정(준비 종료, 30분 종료, 구역 축소, 미션, 재접속 유예, 구역 이탈 실격).
 *
 * 설계 의도: 서버가 잠시 멈췄다가 재시작되거나 개발자 도구로 시간을 건너뛰어도 결과가 같도록,
 * "지금까지 지난 예정 시각"을 이른 순서대로 하나씩 적용한다. 같은 시각이면 [DeadlineKind.priority] 순서.
 * 예) 25분에 이탈 유예가 끝나고 30분에 경기가 끝나는 상황을 31분에 한 번에 처리해도 → 25분의 무효가 먼저 확정된다.
 */

/** 같은 시각에 겹칠 때의 처리 순서(작을수록 먼저). 정상 승패(TIME_OVER)가 같은 시각의 무효 판정보다 우선한다. */
enum class DeadlineKind(val priority: Int) {
    START(0),
    ZONE_WARNING(1),
    ZONE_SHRINK(2),
    MISSION_DEADLINE(3),
    MISSION_START(4),
    TIME_OVER(5),
    DISCONNECT_TIMEOUT(6),
    ZONE_OUT_TIMEOUT(7),
}

data class Deadline(val at: Instant, val kind: DeadlineKind, val stageNo: Int? = null, val userId: UUID? = null, val missionId: UUID? = null)

/** 아직 처리하지 않은 예정 판정 목록. */
fun GameSession.pendingDeadlines(): List<Deadline> = buildList {
    when (core.status) {
        GameStatus.READY -> add(Deadline(core.startsAt, DeadlineKind.START))
        GameStatus.PLAYING -> {
            add(Deadline(core.endsAt, DeadlineKind.TIME_OVER))
            stages.filter { it.appliedAt == null }.forEach { stage ->
                if (stage.warnedAt == null) {
                    add(Deadline(stage.scheduledAt.minusMillis(settings.zoneShrinkWarningMillis), DeadlineKind.ZONE_WARNING, stageNo = stage.stageNo))
                }
                add(Deadline(stage.scheduledAt, DeadlineKind.ZONE_SHRINK, stageNo = stage.stageNo))
            }
            val mission = activeMission
            if (mission != null) {
                add(Deadline(mission.deadlineAt, DeadlineKind.MISSION_DEADLINE, missionId = mission.id))
            } else {
                core.nextMissionAt?.let { add(Deadline(it, DeadlineKind.MISSION_START)) }
            }
            if (settings.zoneOutDisqualifyMillis > 0) {
                players.filter { it.outOfZoneSince != null && it.status != PlayerStatus.ELIMINATED }.forEach {
                    add(Deadline(it.outOfZoneSince!!.plusMillis(settings.zoneOutDisqualifyMillis), DeadlineKind.ZONE_OUT_TIMEOUT, userId = it.userId))
                }
            }
        }
        else -> return@buildList
    }
    // 준비 중에 끊긴 사람에게도 같은 유예를 적용한다(지시서 8.3).
    players.filter { it.connection == PlayerConnectionState.RECONNECTING && it.disconnectedAt != null }.forEach {
        add(Deadline(it.disconnectedAt!!.plusMillis(GameRules.RECONNECT_GRACE_MILLIS), DeadlineKind.DISCONNECT_TIMEOUT, userId = it.userId))
    }
}

/** [now] 까지 지난 예정 판정을 시간 순서대로 모두 적용한다. 모든 요청 처리 전에 먼저 호출된다. */
fun GameSession.advanceTo(now: Instant) {
    var guard = 0
    while (!isEnded) {
        val next = pendingDeadlines()
            .filter { !it.at.isAfter(now) }
            .minWithOrNull(compareBy<Deadline>({ it.at }, { it.kind.priority }))
            ?: return
        apply(next)
        check(++guard < MAX_DEADLINES_PER_ADVANCE) { "예정 판정이 끝나지 않습니다(설정값 확인 필요): ${core.id}" }
    }
}

private fun GameSession.apply(deadline: Deadline) {
    val at = deadline.at
    when (deadline.kind) {
        DeadlineKind.START -> startGame(at)
        DeadlineKind.ZONE_WARNING -> warnZoneShrink(deadline.stageNo!!, at)
        DeadlineKind.ZONE_SHRINK -> shrinkZone(deadline.stageNo!!, at)
        DeadlineKind.MISSION_DEADLINE -> failMission(deadline.missionId!!, at)
        DeadlineKind.MISSION_START -> startMission(at)
        DeadlineKind.TIME_OVER -> finish(GameResult.THIEF_WIN, GameEndReason.TIME_OVER_THIEVES_SURVIVED, at)
        DeadlineKind.DISCONNECT_TIMEOUT -> leaveByDisconnectTimeout(deadline.userId!!, at)
        DeadlineKind.ZONE_OUT_TIMEOUT -> disqualify(deadline.userId!!, GameEndReason.PLAYER_DISQUALIFIED_ZONE_OUT, at)
    }
}

private const val MAX_DEADLINES_PER_ADVANCE = 500
