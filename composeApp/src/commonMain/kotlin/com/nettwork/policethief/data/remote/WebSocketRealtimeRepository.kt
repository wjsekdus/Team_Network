package com.nettwork.policethief.data.remote

import com.nettwork.policethief.core.EpochClock
import com.nettwork.policethief.data.local.ServerUrlProvider
import com.nettwork.policethief.data.mapper.toDomain
import com.nettwork.policethief.domain.model.AppError
import com.nettwork.policethief.domain.model.AppResult
import com.nettwork.policethief.domain.model.RealtimeConnectionState
import com.nettwork.policethief.domain.model.RealtimeEvent
import com.nettwork.policethief.domain.repository.RealtimeRepository
import com.nettwork.policethief.shared.api.ApiJson
import com.nettwork.policethief.shared.api.ApiPaths
import com.nettwork.policethief.shared.dto.ClientMessage
import com.nettwork.policethief.shared.dto.ServerMessage
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException

/**
 * WebSocket 실시간 연결.
 *
 * - connect() 를 호출하면 앱 범위 코루틴에서 연결을 유지한다. 끊기면 지수 백오프(1s, 2s, 4s … 최대 30s)로 재연결한다.
 * - 인증 실패(핸드셰이크 401)는 재시도해도 소용없으므로 Failed(Unauthorized) 로 멈춘다.
 * - disconnect() 는 재연결 루프까지 완전히 중단한다.
 */
class WebSocketRealtimeRepository(
    private val client: HttpClient,
    private val urls: ServerUrlProvider,
    private val tokenProvider: () -> String?,
    private val clock: EpochClock,
    private val scope: CoroutineScope,
) : RealtimeRepository {
    private val state = MutableStateFlow<RealtimeConnectionState>(RealtimeConnectionState.Disconnected)
    override val connectionState: StateFlow<RealtimeConnectionState> = state.asStateFlow()

    private val eventFlow = MutableSharedFlow<RealtimeEvent>(replay = 1, extraBufferCapacity = 32)
    override val events: Flow<RealtimeEvent> = eventFlow.asSharedFlow()

    private var connectionJob: Job? = null
    private var session: DefaultClientWebSocketSession? = null

    override fun connect() {
        if (connectionJob?.isActive == true) return
        connectionJob = scope.launch { runConnectionLoop() }
    }

    override fun disconnect() {
        connectionJob?.cancel()
        connectionJob = null
        session = null
        state.value = RealtimeConnectionState.Disconnected
    }

    override suspend fun requestTestEvent(message: String): AppResult<Unit> = sendMessage(ClientMessage.RequestTestEvent(message))

    /** 왕복 지연 측정용 Ping. 응답은 events 로 RealtimeEvent.Pong 이 온다. */
    suspend fun ping(): AppResult<Unit> = sendMessage(ClientMessage.Ping(clock.nowMillis()))

    private suspend fun sendMessage(message: ClientMessage): AppResult<Unit> {
        val current = session ?: return AppResult.Failure(AppError.Network("WebSocket 이 연결되어 있지 않습니다."))
        return try {
            current.send(Frame.Text(ApiJson.encodeToString(ClientMessage.serializer(), message)))
            AppResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.Failure(AppError.Network(e.message))
        }
    }

    private suspend fun runConnectionLoop() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            val token = tokenProvider()
            if (token == null) {
                state.value = RealtimeConnectionState.Failed(AppError.Unauthorized)
                return
            }
            if (attempt == 0) state.value = RealtimeConnectionState.Connecting

            val failureReason: String = try {
                client.webSocket(urlString = urls.webSocketUrl(ApiPaths.WEBSOCKET), request = { bearerAuth(token) }) {
                    session = this
                    attempt = 0
                    state.value = RealtimeConnectionState.Connected(clock.nowMillis())
                    for (frame in incoming) {
                        if (frame is Frame.Text) handleServerText(frame.readText())
                    }
                }
                "서버가 연결을 닫았습니다."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Ktor 는 업그레이드 실패 시 "expected status code 101 but was 401" 형태의 예외를 던진다.
                if (e.message?.contains("401") == true) {
                    state.value = RealtimeConnectionState.Failed(AppError.Unauthorized)
                    return
                }
                e.message ?: e::class.simpleName ?: "연결 실패"
            } finally {
                session = null
            }

            attempt++
            if (attempt > MAX_RECONNECT_ATTEMPTS) {
                state.value = RealtimeConnectionState.Failed(AppError.Network(failureReason))
                return
            }
            val waitMillis = backoffMillis(attempt)
            state.value = RealtimeConnectionState.Reconnecting(attempt, waitMillis, failureReason)
            delay(waitMillis)
        }
    }

    private suspend fun handleServerText(text: String) {
        val now = clock.nowMillis()
        val event = try {
            when (val message = ApiJson.decodeFromString(ServerMessage.serializer(), text)) {
                is ServerMessage.Welcome -> RealtimeEvent.Welcome(message.userId, now)
                is ServerMessage.TestEvent -> RealtimeEvent.TestEvent(message.sequence, message.message, now)
                is ServerMessage.Pong -> RealtimeEvent.Pong(now - message.clientTimeEpochMillis, now)
                is ServerMessage.GameEventPush -> RealtimeEvent.Game(message.event.toDomain(), now)
                is ServerMessage.Error -> RealtimeEvent.ServerError(message.message, now)
            }
        } catch (e: SerializationException) {
            RealtimeEvent.ServerError("해석할 수 없는 서버 메시지: ${e.message}", now)
        } catch (e: IllegalArgumentException) {
            RealtimeEvent.ServerError("해석할 수 없는 서버 메시지: ${e.message}", now)
        }
        eventFlow.emit(event)
    }

    companion object {
        const val MAX_RECONNECT_ATTEMPTS = 8
        private const val MAX_BACKOFF_MILLIS = 30_000L

        fun backoffMillis(attempt: Int): Long = (1_000L shl (attempt - 1).coerceIn(0, 5)).coerceAtMost(MAX_BACKOFF_MILLIS)
    }
}
