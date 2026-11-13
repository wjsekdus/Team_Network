package com.nettwork.policethief.server.event

import com.nettwork.policethief.shared.api.ApiJson
import com.nettwork.policethief.shared.dto.ServerMessage
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 현재 연결된 WebSocket 세션을 사용자별로 관리한다.
 *
 * 한 사용자가 여러 기기/재연결로 세션을 동시에 가질 수 있으므로 Set 으로 보관한다.
 * 향후 Game 기능은 이 객체(NotificationService 경유)로 체포·구출·구역 축소 이벤트를 푸시한다.
 */
class ConnectionManager {
    private val log = LoggerFactory.getLogger(ConnectionManager::class.java)
    private val sessions = ConcurrentHashMap<UUID, MutableSet<WebSocketSession>>()

    fun register(userId: UUID, session: WebSocketSession) {
        sessions.computeIfAbsent(userId) { ConcurrentHashMap.newKeySet() }.add(session)
    }

    fun unregister(userId: UUID, session: WebSocketSession) {
        sessions.computeIfPresent(userId) { _, set -> set.apply { remove(session) }.takeIf { it.isNotEmpty() } }
    }

    fun isConnected(userId: UUID): Boolean = sessions[userId]?.isNotEmpty() == true

    /** 사용자의 모든 세션에 메시지를 보낸다. 전송 실패한 세션은 로그만 남기고 나머지는 계속 보낸다. */
    suspend fun sendTo(userId: UUID, message: ServerMessage) {
        val text = ApiJson.encodeToString(ServerMessage.serializer(), message)
        sessions[userId].orEmpty().forEach { session ->
            runCatching { session.send(Frame.Text(text)) }
                .onFailure { log.warn("WebSocket 전송 실패 userId={} : {}", userId, it.message) }
        }
    }
}
