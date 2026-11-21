package com.nettwork.policethief.shared.dto

import com.nettwork.policethief.shared.model.MatchmakingEventType
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
    /**
     * 인증된 연결이 성립하면 가장 먼저 보내는 메시지.
     * activeGameId 가 있으면 앱은 GET /api/games/{id}/state 로 상태를 다시 맞춘다(재접속 동기화).
     */
    @Serializable
    @SerialName("welcome")
    data class Welcome(
        val userId: String,
        val serverTimeEpochMillis: Long,
        val heartbeatIntervalMillis: Long,
        val activeGameId: String? = null,
    ) : ServerMessage

    /** 매칭 상태 변화(MATCHING_UPDATED / COMPLETED / TIMEOUT / CANCELLED). */
    @Serializable
    @SerialName("matchmaking_update")
    data class MatchmakingUpdate(val event: MatchmakingEventType, val ticket: MatchmakingTicketDto) : ServerMessage

    /**
     * 같은 사용자가 새로 접속해 이 연결이 대체됨(단일 활성 세션 정책). 이 메시지 직후 서버가 연결을 닫는다.
     * 앱은 자동 재연결을 멈추고 사용자에게 알린다.
     */
    @Serializable
    @SerialName("session_replaced")
    data class SessionReplaced(val message: String) : ServerMessage

    @Serializable
    @SerialName("pong")
    data class Pong(val clientTimeEpochMillis: Long, val serverTimeEpochMillis: Long) : ServerMessage

    /** 연결 확인용 테스트 이벤트. 클라이언트가 RequestTestEvent 를 보내면 응답한다. */
    @Serializable
    @SerialName("test_event")
    data class TestEvent(val sequence: Long, val message: String, val serverTimeEpochMillis: Long) : ServerMessage

    /**
     * PLAYER_LOCATION_UPDATED: 공개가 허용된 참가자의 새 위치(경찰 → 모든 위치, 도둑 → 도둑 위치만).
     * 저장되지 않는 일시 메시지이므로 놓쳤다면 GET /api/games/{id}/locations 로 다시 받는다.
     */
    @Serializable
    @SerialName("player_location")
    data class PlayerLocation(val gameId: String, val location: LocationDto) : ServerMessage

    /** 경기 이벤트 푸시(체포·구출·구역 축소·종료 등). 받는 사람의 역할에 맞게 서버가 걸러 보낸다. */
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
