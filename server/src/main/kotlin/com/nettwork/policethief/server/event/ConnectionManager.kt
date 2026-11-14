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
 * 단일 활성 세션 정책: 한 사용자는 동시에 세션 1개만 가진다. 새 연결이 오면 이전 연결은 [register] 가 돌려주고,
 * 호출한 쪽이 SessionReplaced 를 보낸 뒤 닫는다. 그래서 같은 계정이 두 기기에서 동시에 경기를 조작할 수 없고,
 * 이벤트가 두 곳으로 갈라지지 않는다.
 */
class ConnectionManager {
    private val log = LoggerFactory.getLogger(ConnectionManager::class.java)
    private val sessions = ConcurrentHashMap<UUID, WebSocketSession>()

    /** 새 세션을 등록하고, 대체된 이전 세션이 있으면 돌려준다. */
    fun register(userId: UUID, session: WebSocketSession): WebSocketSession? = sessions.put(userId, session)?.takeIf { it !== session }

    /**
     * 세션을 해제한다. 이미 새 세션으로 대체된 경우에는 아무것도 지우지 않는다.
     * @return 이 사용자에게 남은 연결이 없으면 true(= 실제 연결 끊김).
     */
    fun unregister(userId: UUID, session: WebSocketSession): Boolean {
        sessions.remove(userId, session)
        return !sessions.containsKey(userId)
    }

    fun isConnected(userId: UUID): Boolean = sessions.containsKey(userId)

    /** 사용자의 세션에 메시지를 보낸다. 실패하면 로그만 남긴다(연결 정리는 세션 쪽 finally 가 한다). */
    suspend fun sendTo(userId: UUID, message: ServerMessage) {
        val session = sessions[userId] ?: return
        val text = ApiJson.encodeToString(ServerMessage.serializer(), message)
        runCatching { session.send(Frame.Text(text)) }
            .onFailure { log.warn("WebSocket 전송 실패 userId={} : {}", userId, it.message) }
    }
}
