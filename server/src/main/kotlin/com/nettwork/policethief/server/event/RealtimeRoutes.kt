package com.nettwork.policethief.server.event

import com.nettwork.policethief.server.auth.UserPrincipal
import com.nettwork.policethief.server.game.PresenceListener
import com.nettwork.policethief.shared.api.ApiJson
import com.nettwork.policethief.shared.api.ApiPaths
import com.nettwork.policethief.shared.dto.ClientMessage
import com.nettwork.policethief.shared.dto.ErrorCode
import com.nettwork.policethief.shared.dto.ServerMessage
import com.nettwork.policethief.shared.rules.GameRules
import io.ktor.server.auth.principal
import io.ktor.server.routing.Route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.concurrent.atomic.AtomicLong

/**
 * GET /ws (WebSocket). authenticate { } 안에 등록되므로 업그레이드 요청의 Authorization 헤더로 인증한다.
 *
 * - 연결 시 Welcome(진행 중 경기 ID 포함) → 앱은 REST 로 상태를 다시 맞춘다.
 * - Ping → Pong(앱 하트비트), RequestTestEvent → TestEvent(연결 확인).
 * - 경기 이벤트·위치·매칭 알림은 NotificationService 가 ConnectionManager.sendTo 로 보낸다.
 * - 연결이 실제로 끊기면(같은 사용자의 새 연결로 대체된 경우 제외) [presence] 에 알려 3분 재접속 유예를 시작한다.
 *   끊김 감지는 Ktor ping/timeout(Plugins.kt: 15초/30초)과 TCP 종료로 이루어진다.
 */
fun Route.realtimeRoutes(connections: ConnectionManager, clock: Clock, presence: PresenceListener) {
    val log = LoggerFactory.getLogger("RealtimeRoutes")
    val testEventSequence = AtomicLong(0)

    webSocket(ApiPaths.WEBSOCKET) {
        val principal = call.principal<UserPrincipal>()
        if (principal == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
            return@webSocket
        }
        // Welcome 을 등록·재접속 처리보다 먼저 보낸다: 등록 순간부터 이벤트가 들어올 수 있어(PLAYER_RECONNECTED 등)
        // 순서를 바꾸면 "연결 후 첫 메시지는 welcome" 계약이 깨진다. 그 사이 놓친 이벤트는 앱이 welcome 뒤 REST 로 다시 맞춘다.
        send(
            Frame.Text(
                ApiJson.encodeToString(
                    ServerMessage.serializer(),
                    ServerMessage.Welcome(
                        userId = principal.userId.toString(),
                        serverTimeEpochMillis = clock.millis(),
                        heartbeatIntervalMillis = GameRules.LOCATION_UPDATE_INTERVAL_MILLIS,
                        activeGameId = presence.activeGameIdOf(principal.userId)?.toString(),
                    ),
                ),
            ),
        )
        connections.register(principal.userId, this)?.let { replaced ->
            runCatching {
                replaced.send(Frame.Text(ApiJson.encodeToString(ServerMessage.serializer(), ServerMessage.SessionReplaced("다른 연결에서 같은 계정으로 접속했습니다."))))
                replaced.close(CloseReason(CloseReason.Codes.NORMAL, "session_replaced"))
            }.onFailure { log.debug("대체된 세션 종료 실패: {}", it.message) }
        }
        try {
            presence.onUserConnected(principal.userId)
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val reply = try {
                    when (val message = ApiJson.decodeFromString(ClientMessage.serializer(), frame.readText())) {
                        is ClientMessage.Ping -> ServerMessage.Pong(message.clientTimeEpochMillis, clock.millis())
                        is ClientMessage.RequestTestEvent -> ServerMessage.TestEvent(
                            sequence = testEventSequence.incrementAndGet(),
                            message = "서버 테스트 이벤트: ${message.message.take(100)}",
                            serverTimeEpochMillis = clock.millis(),
                        )
                    }
                } catch (e: SerializationException) {
                    // 잘못된 메시지 하나 때문에 연결을 끊지 않고 오류만 알려 준다.
                    ServerMessage.Error(ErrorCode.BAD_REQUEST, "알 수 없는 메시지 형식입니다.")
                } catch (e: IllegalArgumentException) {
                    ServerMessage.Error(ErrorCode.BAD_REQUEST, "알 수 없는 메시지 형식입니다.")
                }
                send(reply)
            }
        } finally {
            if (connections.unregister(principal.userId, this)) {
                // finally 는 취소된 코루틴에서도 실행되므로 NonCancellable 로 감싸 상태 기록이 끝까지 되게 한다.
                withContext(NonCancellable) {
                    runCatching { presence.onUserDisconnected(principal.userId) }
                        .onFailure { log.error("연결 끊김 처리 실패 userId={}", principal.userId, it) }
                }
            }
        }
    }
}

private suspend fun DefaultWebSocketServerSession.send(message: ServerMessage) {
    send(Frame.Text(ApiJson.encodeToString(ServerMessage.serializer(), message)))
}
