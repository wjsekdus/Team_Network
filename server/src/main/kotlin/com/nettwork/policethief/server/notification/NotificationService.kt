package com.nettwork.policethief.server.notification

import com.nettwork.policethief.server.event.ConnectionManager
import com.nettwork.policethief.shared.dto.GameEventDto
import com.nettwork.policethief.shared.dto.ServerMessage
import java.util.UUID

/**
 * 경기 이벤트를 참가자에게 전달하는 통로.
 *
 * 현재 구현은 "접속 중인 WebSocket 세션으로 즉시 전송"만 한다.
 * TODO(Notification): 앱이 백그라운드/종료 상태일 때를 위한 푸시 알림(FCM/APNs)은 미구현. docs/development/TODO.md 참고.
 */
interface NotificationService {
    suspend fun notifyGameEvent(recipients: Collection<UUID>, event: GameEventDto)
}

class WebSocketNotificationService(private val connections: ConnectionManager) : NotificationService {
    override suspend fun notifyGameEvent(recipients: Collection<UUID>, event: GameEventDto) {
        recipients.forEach { connections.sendTo(it, ServerMessage.GameEventPush(event)) }
    }
}
