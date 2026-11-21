package com.nettwork.policethief.domain.model

import com.nettwork.policethief.shared.model.MatchmakingEventType

/** WebSocket 연결 상태. 홈·설정 화면의 연결 표시등이 이 값을 그린다. */
sealed interface RealtimeConnectionState {
    data object Disconnected : RealtimeConnectionState
    data object Connecting : RealtimeConnectionState
    data class Connected(val sinceEpochMillis: Long) : RealtimeConnectionState

    /** 연결이 끊겨 [delayMillis] 후 [attempt] 번째 재연결을 시도할 예정. */
    data class Reconnecting(val attempt: Int, val delayMillis: Long, val lastError: String?) : RealtimeConnectionState

    /** 재시도하지 않는 실패(인증 실패, 재시도 한도 초과). */
    data class Failed(val error: AppError) : RealtimeConnectionState
}

/** 서버에서 받은 실시간 이벤트(도메인 표현). */
sealed interface RealtimeEvent {
    val receivedAtEpochMillis: Long

    /** 연결(재연결) 성공. activeGameId 가 있으면 경기 상태를 REST 로 다시 맞춘다. */
    data class Welcome(
        val userId: String,
        override val receivedAtEpochMillis: Long,
        val activeGameId: String? = null,
        val serverTimeEpochMillis: Long = 0,
    ) : RealtimeEvent
    data class TestEvent(val sequence: Long, val message: String, override val receivedAtEpochMillis: Long) : RealtimeEvent
    data class Pong(val roundTripMillis: Long, override val receivedAtEpochMillis: Long) : RealtimeEvent
    data class Game(val event: GameEvent, override val receivedAtEpochMillis: Long) : RealtimeEvent

    /** 매칭 상태 변화(MATCHING_UPDATED / COMPLETED / TIMEOUT / CANCELLED). */
    data class Matchmaking(val type: MatchmakingEventType, val ticket: MatchmakingTicket, override val receivedAtEpochMillis: Long) : RealtimeEvent

    /** 공개가 허용된 참가자의 새 위치(서버가 역할별로 걸러서 보낸다). */
    data class Location(val gameId: String, val location: PlayerLocation, override val receivedAtEpochMillis: Long) : RealtimeEvent

    /** 같은 계정이 다른 기기에서 접속해 이 연결이 끊김(단일 활성 세션). 자동 재연결하지 않는다. */
    data class SessionReplaced(val message: String, override val receivedAtEpochMillis: Long) : RealtimeEvent
    data class ServerError(val message: String, override val receivedAtEpochMillis: Long) : RealtimeEvent
}
