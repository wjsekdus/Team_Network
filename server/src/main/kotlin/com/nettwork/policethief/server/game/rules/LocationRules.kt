package com.nettwork.policethief.server.game.rules

import com.nettwork.policethief.server.game.GameSession
import com.nettwork.policethief.server.game.LocationSample
import java.time.Instant

/**
 * 경기 참가자의 새 위치를 반영하고 위치 기반 판정을 차례로 실행한다: 과속 → 구역 이탈 → 감옥 → 미션.
 *
 * 순서 역전·중복 수신 대응: 이미 받은 것보다 측정 시각이 같거나 이른 표본은 판정에 쓰지 않는다
 * (DB 에는 기록으로 저장하되 게임 상태는 바꾸지 않는다).
 *
 * @return 이 표본을 판정에 사용했으면 true(= 다른 참가자에게 새 위치로 알릴 가치가 있음).
 */
fun GameSession.applyLocation(sample: LocationSample, now: Instant): Boolean {
    if (player(sample.userId) == null || isEnded) return false
    val previous = latest[sample.userId]
    if (previous != null && !sample.measuredAt.isAfter(previous.measuredAt)) return false
    latest[sample.userId] = sample

    judgeSpeed(previous, sample, now)
    if (isEnded) return true
    judgeZone(sample, now)
    judgeJail(sample.userId, now)
    judgeMission(sample, now)
    return true
}
