package com.nettwork.policethief.shared.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 서버 → 클라이언트 WebSocket 메시지. JSON 의 "type" 필드로 종류를 구분한다.
 *
 * 예) {"type":"welcome","userId":"...","serverTimeEpochMillis":1700000000000,"heartbeatIntervalMillis":15000}
 *
 * 새 메시지를 추가할 때는 이 sealed class 에 하위 클래스를 추가하고 @SerialName 을 반드시 지정한다.
 */
@Serializable
sealed interface ServerMessage {
    /** 인증된 연결이 성립하면 가장 먼저 보내는 메시지. */
    @Serializable
    @SerialName("welcome")
    data class Welcome(
        val userId: String,
        val serverTimeEpochMillis: Long,
        val heartbeatIntervalMillis: Long,
    ) : ServerMessage

    @Serializable
    @SerialName("pong")
    data class Pong(val clientTimeEpochMillis: Long, val serverTimeEpochMillis: Long) : ServerMessage

    /** 연결 확인용 테스트 이벤트. 클라이언트가 RequestTestEvent 를 보내면 응답한다. */
    @Serializable
    @SerialName("test_event")
    data class TestEvent(val sequence: Long, val message: String, val serverTimeEpochMillis: Long) : ServerMessage

    /** 경기 이벤트 푸시(향후 구현: 체포·구출·구역 축소·종료 등). */
    @Serializable
    @SerialName("game_event")
    data class GameEventPush(val event: GameEventDto) : ServerMessage

    @Serializable
    @SerialName("error")
    data class Error(val code: ErrorCode, val message: String) : ServerMessage
}

/** 클라이언트 → 서버 WebSocket 메시지. */
@Serializable
sealed interface ClientMessage {
    @Serializable
    @SerialName("ping")
    data class Ping(val clientTimeEpochMillis: Long) : ClientMessage

    @Serializable
    @SerialName("request_test_event")
    data class RequestTestEvent(val message: String) : ClientMessage
}
