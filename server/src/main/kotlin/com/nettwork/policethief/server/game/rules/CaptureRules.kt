package com.nettwork.policethief.server.game.rules

import com.nettwork.policethief.server.game.GameSession
import com.nettwork.policethief.server.game.PlayerState
import com.nettwork.policethief.server.game.asActor
import com.nettwork.policethief.server.game.asTarget
import com.nettwork.policethief.server.game.eventData
import com.nettwork.policethief.shared.dto.QrScanResultDto
import com.nettwork.policethief.shared.model.GameEventType
import com.nettwork.policethief.shared.model.GameStatus
import com.nettwork.policethief.shared.model.PlayerRole
import com.nettwork.policethief.shared.model.PlayerStatus
import com.nettwork.policethief.shared.model.ScanRejectReason
import java.time.Instant
import java.util.UUID

/** 스캔한 QR 토큰을 DB 에서 찾아본 결과. 토큰의 경기·소유자는 DB 가 기준이다(QR 내용만으로 판단하지 않는다). */
sealed interface ScannedToken {
    data object Unknown : ScannedToken
    data object Revoked : ScannedToken
    data object OtherGame : ScannedToken
    data class Valid(val ownerId: UUID) : ScannedToken
}

/**
 * QR 체포(Capture). 경찰이 활동 중인 도둑의 QR 을 스캔하면 도둑은 ESCORTED, 경찰은 그 도둑을 호송한다.
 * 검사 순서가 거절 사유를 정한다: 경기 상태 → 요청자 역할 → 토큰 → 대상 상태 → 호송 제한.
 * 승패: 이 체포로 도둑 2명이 모두 체포 상태가 되면 즉시 경찰 승리(호송·수감 과정 없이 종료).
 */
fun GameSession.capture(policeId: UUID, token: ScannedToken, at: Instant): QrScanResultDto {
    if (core.status != GameStatus.PLAYING) return reject(ScanRejectReason.GAME_NOT_PLAYING)
    val police = player(policeId) ?: return reject(ScanRejectReason.NOT_ALLOWED)
    if (police.role != PlayerRole.POLICE || police.status != PlayerStatus.ACTIVE) return reject(ScanRejectReason.NOT_ALLOWED)
    val target = when (val resolved = resolveTarget(token)) {
        is TargetResolution.Rejected -> return reject(resolved.reason)
        is TargetResolution.Found -> resolved.player
    }
    if (target.userId == policeId) return reject(ScanRejectReason.SELF_SCAN, target)
    if (target.role != PlayerRole.THIEF) return reject(ScanRejectReason.NOT_ALLOWED, target)
    if (target.status != PlayerStatus.ACTIVE) return reject(ScanRejectReason.ALREADY_CAPTURED, target)
    if (police.escortingUserId != null) return reject(ScanRejectReason.ESCORT_LIMIT, target)

    updatePlayer(target.userId) { copy(status = PlayerStatus.ESCORTED, capturedBy = policeId) }
    updatePlayer(policeId) { copy(escortingUserId = target.userId) }
    escortStarts += policeId to target.userId
    record(GameEventType.THIEF_ARRESTED, at, data = eventData(*target.asTarget(), *police.asActor()))
    checkPoliceVictory(at)
    return accepted(target.userId)
}

internal sealed interface TargetResolution {
    data class Found(val player: PlayerState) : TargetResolution
    data class Rejected(val reason: ScanRejectReason) : TargetResolution
}

internal fun GameSession.resolveTarget(token: ScannedToken): TargetResolution = when (token) {
    ScannedToken.Unknown, ScannedToken.Revoked -> TargetResolution.Rejected(ScanRejectReason.INVALID_TOKEN)
    ScannedToken.OtherGame -> TargetResolution.Rejected(ScanRejectReason.OTHER_GAME)
    is ScannedToken.Valid -> player(token.ownerId)?.let { TargetResolution.Found(it) } ?: TargetResolution.Rejected(ScanRejectReason.OTHER_GAME)
}

internal fun GameSession.reject(reason: ScanRejectReason, target: PlayerState? = null) = QrScanResultDto(
    accepted = false,
    targetUserId = target?.userId?.toString(),
    targetNickname = target?.nickname,
    targetStatus = target?.status,
    rejectReason = reason,
    gameStatus = core.status,
    gameResult = core.result,
)

internal fun GameSession.accepted(targetId: UUID): QrScanResultDto {
    val target = player(targetId)!!
    return QrScanResultDto(
        accepted = true,
        targetUserId = target.userId.toString(),
        targetNickname = target.nickname,
        targetStatus = target.status,
        gameStatus = core.status,
        gameResult = core.result,
    )
}
