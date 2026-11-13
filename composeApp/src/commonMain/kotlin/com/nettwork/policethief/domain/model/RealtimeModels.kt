package com.nettwork.policethief.domain.model

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

    data class Welcome(val userId: String, override val receivedAtEpochMillis: Long) : RealtimeEvent
    data class TestEvent(val sequence: Long, val message: String, override val receivedAtEpochMillis: Long) : RealtimeEvent
    data class Pong(val roundTripMillis: Long, override val receivedAtEpochMillis: Long) : RealtimeEvent
    data class Game(val event: GameEvent, override val receivedAtEpochMillis: Long) : RealtimeEvent
    data class ServerError(val message: String, override val receivedAtEpochMillis: Long) : RealtimeEvent
}
