package com.nettwork.policethief.server.game.rules

import com.nettwork.policethief.server.game.GameSession
import com.nettwork.policethief.server.game.Visibility
import com.nettwork.policethief.server.game.asActor
import com.nettwork.policethief.server.game.asTarget
import com.nettwork.policethief.server.game.eventData
import com.nettwork.policethief.shared.dto.RingThiefResultDto
import com.nettwork.policethief.shared.model.GameEndReason
import com.nettwork.policethief.shared.model.GameEventKeys
import com.nettwork.policethief.shared.model.GameEventType
import com.nettwork.policethief.shared.model.GameResult
import com.nettwork.policethief.shared.model.GameStatus
import com.nettwork.policethief.shared.model.MissionStatus
import com.nettwork.policethief.shared.model.PlayerConnectionState
import com.nettwork.policethief.shared.model.PlayerRole
import com.nettwork.policethief.shared.model.PlayerStatus
import java.time.Instant
import java.util.UUID

/*
 * 경기 생명주기: 시작, 종료(승패·무효), 기권, 연결 끊김/재접속, 실격, 폰 울리기.
 */

/** 준비시간(30초)이 끝나면 PLAYING 으로. 시작 시각은 서버가 정한 startsAt 그대로 쓴다. */
internal fun GameSession.startGame(at: Instant) {
    if (core.status != GameStatus.READY) return
    updateCore {
        copy(status = GameStatus.PLAYING, startedAt = startsAt, nextMissionAt = startsAt.plusMillis(settings.missionFirstDelayMillis))
    }
    record(GameEventType.GAME_STARTED, at, data = eventData(GameEventKeys.AT_EPOCH_MILLIS to core.startsAt))
}

/**
 * 경기를 끝낸다. 이미 끝났으면 아무것도 하지 않는다 → 결과는 한 번만 확정된다.
 * INVALID 는 CANCELLED(무효), 나머지는 FINISHED(정상 승패).
 */
fun GameSession.finish(result: GameResult, reason: GameEndReason, at: Instant) {
    if (isEnded) return
    val endStatus = if (result == GameResult.INVALID) GameStatus.CANCELLED else GameStatus.FINISHED
    updateCore { copy(status = endStatus, result = result, endReason = reason, endedAt = at, nextMissionAt = null) }
    // 경기가 끝나면 진행 중 호송·미션도 끝난다(도둑의 최종 상태 ESCORTED 는 결과 기록으로 남긴다).
    police().filter { it.escortingUserId != null }.forEach { updatePlayer(it.userId) { copy(escortingUserId = null) } }
    missions.filter { it.status == MissionStatus.ACTIVE }.forEach { updateMission(it.id) { copy(status = MissionStatus.CANCELLED, endedAt = at) } }
    record(
        if (endStatus == GameStatus.FINISHED) GameEventType.GAME_FINISHED else GameEventType.GAME_INVALIDATED,
        at,
        data = eventData(GameEventKeys.RESULT to result, GameEventKeys.REASON to reason, GameEventKeys.AT_EPOCH_MILLIS to at),
    )
}

/** 경찰 승리 조건: 도둑 2명 모두 체포 상태(호송 중 ESCORTED 포함). 감옥 도착 여부와 무관하다. */
internal fun GameSession.checkPoliceVictory(at: Instant) {
    val thieves = thieves()
    if (thieves.isNotEmpty() && thieves.all { it.status == PlayerStatus.ESCORTED || it.status == PlayerStatus.JAILED }) {
        finish(GameResult.POLICE_WIN, GameEndReason.ALL_THIEVES_CAPTURED, at)
    }
}

/** 명시적 기권: 즉시 무효 경기. 이미 끝난 경기면 변화 없음. */
fun GameSession.forfeit(userId: UUID, at: Instant) {
    val player = player(userId) ?: return
    if (isEnded) return
    updatePlayer(userId) { copy(connection = PlayerConnectionState.LEFT) }
    record(GameEventType.PLAYER_FORFEITED, at, data = eventData(*player.asTarget()))
    finish(GameResult.INVALID, GameEndReason.PLAYER_FORFEITED, at)
}

/** 일시적 연결 끊김: 기권이 아니다. 3분 유예가 시작된다. */
fun GameSession.markDisconnected(userId: UUID, at: Instant) {
    val player = player(userId) ?: return
    if (isEnded || player.connection != PlayerConnectionState.CONNECTED) return
    updatePlayer(userId) { copy(connection = PlayerConnectionState.RECONNECTING, disconnectedAt = at) }
    record(
        GameEventType.PLAYER_DISCONNECTED,
        at,
        data = eventData(*player.asTarget(), GameEventKeys.AT_EPOCH_MILLIS to at.plusMillis(com.nettwork.policethief.shared.rules.GameRules.RECONNECT_GRACE_MILLIS)),
    )
}

fun GameSession.markReconnected(userId: UUID, at: Instant) {
    val player = player(userId) ?: return
    if (isEnded || player.connection != PlayerConnectionState.RECONNECTING) return
    updatePlayer(userId) { copy(connection = PlayerConnectionState.CONNECTED, disconnectedAt = null) }
    record(GameEventType.PLAYER_RECONNECTED, at, data = eventData(*player.asTarget()))
}

/** 유예시간(3분) 안에 돌아오지 않음 → 이탈 확정 → 무효. */
internal fun GameSession.leaveByDisconnectTimeout(userId: UUID, at: Instant) {
    val player = player(userId) ?: return
    updatePlayer(userId) { copy(connection = PlayerConnectionState.LEFT) }
    record(GameEventType.PLAYER_ELIMINATED, at, data = eventData(*player.asTarget(), GameEventKeys.REASON to GameEndReason.PLAYER_DISCONNECT_TIMEOUT))
    finish(GameResult.INVALID, GameEndReason.PLAYER_DISCONNECT_TIMEOUT, at)
}

/** 실격(과속 3회, 구역 이탈 초과) → 무효. */
internal fun GameSession.disqualify(userId: UUID, reason: GameEndReason, at: Instant) {
    val player = player(userId) ?: return
    if (isEnded) return
    updatePlayer(userId) { copy(status = PlayerStatus.ELIMINATED, escortingUserId = null) }
    record(GameEventType.PLAYER_ELIMINATED, at, data = eventData(*player.asTarget(), GameEventKeys.REASON to reason))
    finish(GameResult.INVALID, reason, at)
}

/**
 * 경찰의 "도둑 폰 울리기"(Feature Flag). 활동 중인 도둑 한 명에게만 알림을 보낸다.
 * 쿨다운은 경찰별로 서버가 관리한다.
 */
fun GameSession.ringThiefPhone(policeId: UUID, targetId: UUID, at: Instant): RingThiefResultDto {
    if (!settings.ringThiefPhoneEnabled) return RingThiefResultDto(false, "FEATURE_DISABLED")
    if (core.status != GameStatus.PLAYING) return RingThiefResultDto(false, "GAME_NOT_PLAYING")
    val police = player(policeId)?.takeIf { it.role == PlayerRole.POLICE && it.status == PlayerStatus.ACTIVE }
        ?: return RingThiefResultDto(false, "NOT_ALLOWED")
    val target = player(targetId)?.takeIf { it.role == PlayerRole.THIEF && it.status == PlayerStatus.ACTIVE }
        ?: return RingThiefResultDto(false, "TARGET_NOT_ACTIVE_THIEF")
    val nextAvailable = police.lastRingAt?.plusMillis(settings.ringCooldownMillis)
    if (nextAvailable != null && at.isBefore(nextAvailable)) {
        return RingThiefResultDto(false, "COOLDOWN", nextAvailable.toEpochMilli())
    }
    updatePlayer(policeId) { copy(lastRingAt = at) }
    record(GameEventType.THIEF_PHONE_RING, at, Visibility.USER, target.userId, eventData(*target.asTarget(), *police.asActor()))
    return RingThiefResultDto(true, nextAvailableAtEpochMillis = at.plusMillis(settings.ringCooldownMillis).toEpochMilli())
}
