package com.nettwork.policethief.server.notification

import com.nettwork.policethief.server.event.ConnectionManager
import com.nettwork.policethief.shared.dto.ServerMessage
import java.util.UUID

/**
 * 한 번의 처리에서 생긴 "누구에게 무엇을 보낼지" 목록.
 * DB 트랜잭션이 성공한 뒤에만 보내기 위해 잠시 모아 둔다(실패해 롤백된 변경을 알리지 않기 위함).
 */
class Outbox {
    private val items = mutableListOf<Pair<UUID, ServerMessage>>()
    val messages: List<Pair<UUID, ServerMessage>> get() = items

    fun add(userId: UUID, message: ServerMessage) {
        items += userId to message
    }
}

/**
 * 참가자에게 실시간 메시지를 전달하는 통로(Notification 기능 영역).
 *
 * 현재 구현: 접속 중인 WebSocket 세션으로 즉시 전송. 앱이 백그라운드여도 연결이 살아 있으면 받는다(앱이 로컬 알림으로 표시).
 * 미구현: 앱 프로세스가 종료·정지된 상태용 서버 푸시(FCM/APNs). 무료지만 Firebase 프로젝트·APNs 인증서가 필요해
 *        이번 환경에서 설정·검증할 수 없었다(docs/testing/TEST_REPORT.md). 재접속 시 GET /events 로 놓친 이벤트를 받는다.
 */
interface NotificationService {
    suspend fun deliver(outbox: Outbox)
}

class WebSocketNotificationService(private val connections: ConnectionManager) : NotificationService {
    override suspend fun deliver(outbox: Outbox) {
        outbox.messages.forEach { (userId, message) -> connections.sendTo(userId, message) }
    }
}
