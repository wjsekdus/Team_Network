package com.nettwork.policethief.server.game.rules

import com.nettwork.policethief.server.game.GameSession
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

/**
 * QR 구출(Rescue). 미체포(ACTIVE) 도둑이 수감된(JAILED) 동료의 QR 을 스캔하면 동료는 ACTIVE 로 돌아온다.
 * 호송 중(ESCORTED)인 도둑은 구출할 수 없다. 체포된 도둑(ESCORTED/JAILED)은 구출자가 될 수 없다.
 */
fun GameSession.rescue(rescuerId: UUID, token: ScannedToken, at: Instant): QrScanResultDto {
    if (core.status != GameStatus.PLAYING) return reject(ScanRejectReason.GAME_NOT_PLAYING)
    val rescuer = player(rescuerId) ?: return reject(ScanRejectReason.NOT_ALLOWED)
    if (rescuer.role != PlayerRole.THIEF) return reject(ScanRejectReason.NOT_ALLOWED)
    if (rescuer.status != PlayerStatus.ACTIVE) return reject(ScanRejectReason.RESCUER_NOT_ACTIVE)
    val target = when (val resolved = resolveTarget(token)) {
        is TargetResolution.Rejected -> return reject(resolved.reason)
        is TargetResolution.Found -> resolved.player
    }
    if (target.userId == rescuerId) return reject(ScanRejectReason.SELF_SCAN, target)
    if (target.role != PlayerRole.THIEF) return reject(ScanRejectReason.NOT_ALLOWED, target)
    when (target.status) {
        PlayerStatus.JAILED -> Unit
        PlayerStatus.ESCORTED -> return reject(ScanRejectReason.TARGET_ESCORTED, target)
        PlayerStatus.ACTIVE, PlayerStatus.ELIMINATED -> return reject(ScanRejectReason.TARGET_NOT_JAILED, target)
    }

    updatePlayer(target.userId) { copy(status = PlayerStatus.ACTIVE, capturedBy = null) }
    record(GameEventType.THIEF_RESCUED, at, data = eventData(*target.asTarget(), *rescuer.asActor()))
    return accepted(target.userId)
}
