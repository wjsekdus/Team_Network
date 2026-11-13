package com.nettwork.policethief.server.event

import com.nettwork.policethief.server.auth.UserPrincipal
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
import kotlinx.serialization.SerializationException
import java.time.Clock
import java.util.concurrent.atomic.AtomicLong

/**
 * GET /ws (WebSocket). authenticate { } 안에 등록되므로 업그레이드 요청의 Authorization 헤더로 인증한다.
 *
 * 현재 지원: 연결 시 Welcome, Ping → Pong, RequestTestEvent → TestEvent.
 * 향후: 경기 이벤트 푸시(GameEventPush)는 NotificationService 를 통해 ConnectionManager.sendTo 로 보낸다.
 */
fun Route.realtimeRoutes(connections: ConnectionManager, clock: Clock) {
    val testEventSequence = AtomicLong(0)

    webSocket(ApiPaths.WEBSOCKET) {
        val principal = call.principal<UserPrincipal>()
        if (principal == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
            return@webSocket
        }
        connections.register(principal.userId, this)
        try {
            send(
                ServerMessage.Welcome(
                    userId = principal.userId.toString(),
                    serverTimeEpochMillis = clock.millis(),
                    heartbeatIntervalMillis = GameRules.LOCATION_UPDATE_INTERVAL_MILLIS,
                ),
            )
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
            connections.unregister(principal.userId, this)
        }
    }
}

private suspend fun DefaultWebSocketServerSession.send(message: ServerMessage) {
    send(Frame.Text(ApiJson.encodeToString(ServerMessage.serializer(), message)))
}
