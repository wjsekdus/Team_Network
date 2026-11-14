package com.nettwork.policethief.domain.repository

import com.nettwork.policethief.domain.model.AppResult
import com.nettwork.policethief.domain.model.Game
import com.nettwork.policethief.domain.model.GameResultSummary
import com.nettwork.policethief.domain.model.GameSnapshot
import com.nettwork.policethief.domain.model.MatchmakingTicket
import com.nettwork.policethief.domain.model.PlayerLocation
import com.nettwork.policethief.domain.model.QrScanResult
import com.nettwork.policethief.domain.model.QrToken
import com.nettwork.policethief.domain.model.RealtimeConnectionState
import com.nettwork.policethief.domain.model.RealtimeEvent
import com.nettwork.policethief.domain.model.ServerStatus
import com.nettwork.policethief.domain.model.User
import com.nettwork.policethief.shared.model.GameEndReason
import com.nettwork.policethief.shared.model.GameResult
import com.nettwork.policethief.shared.model.PlayerRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/*
 * Repository 인터페이스(Domain 계층).
 * 구현체는 data/repository(실제 서버) 또는 data/mock(Mock) 에 있고, di/AppModules.kt 에서 어느 쪽을 쓸지 정한다.
 */

/** 세션 복구 결과. */
sealed interface SessionRestoreResult {
    data object NoSession : SessionRestoreResult

    /** offline = true 면 서버 확인 없이 저장된 사용자 정보로 복구(네트워크 오류). */
    data class Restored(val user: User, val offline: Boolean) : SessionRestoreResult
}

/** [실제 서버 연동] 게스트 로그인과 세션 관리. */
interface AuthRepository {
    val currentUser: StateFlow<User?>
    suspend fun guestLogin(nickname: String): AppResult<User>
    suspend fun restoreSession(): AppResult<SessionRestoreResult>
    suspend fun refreshMe(): AppResult<User>
    suspend fun logout()
}

/** [실제 서버 연동] GET /api/health. */
interface ServerStatusRepository {
    suspend fun check(): AppResult<ServerStatus>
}

/** [실제 서버 연동] 위치 수집(플랫폼) + 서버 전송. 마지막 위치와 측정 시각을 보관한다. */
interface LocationRepository {
    val lastLocation: StateFlow<PlayerLocation?>
    val lastSentAtEpochMillis: StateFlow<Long?>
    suspend fun fetchCurrentLocation(): AppResult<PlayerLocation>
    suspend fun sendLocation(location: PlayerLocation): AppResult<PlayerLocation>
}

/** [실제 서버 연동] WebSocket. */
interface RealtimeRepository {
    val connectionState: StateFlow<RealtimeConnectionState>
    val events: Flow<RealtimeEvent>
    fun connect()
    fun disconnect()
    suspend fun requestTestEvent(message: String): AppResult<Unit>
}

/** [실제 서버] 4인 매칭. 상태 변화는 WebSocket(matchmaking_update)과 [refresh] 재조회로 [ticket] 에 반영된다. */
interface MatchmakingRepository {
    val ticket: StateFlow<MatchmakingTicket?>
    suspend fun join(location: PlayerLocation): AppResult<MatchmakingTicket>

    /** GET /api/matchmaking/me. WebSocket 메시지를 놓쳤을 때를 대비한 재조회. */
    suspend fun refresh(): AppResult<MatchmakingTicket>
    suspend fun cancel(): AppResult<Unit>
}

/** [실제 서버] 경기 진행 정보와 QR 체포·구출. 모든 판정은 서버가 한다. */
interface GameRepository {
    /** 진행 중인 경기(앱 재실행 복구용). 없으면 Success(null). */
    suspend fun currentGame(): AppResult<Game?>
    suspend fun getGame(gameId: String): AppResult<Game>

    /** 경기 + 공개 허용 위치 + 최근 이벤트를 한 번에(재접속 동기화). */
    suspend fun state(gameId: String): AppResult<GameSnapshot>

    /** 서버가 내 역할에 맞게 걸러 준 위치 목록(경찰: 전원, 도둑: 도둑끼리). */
    suspend fun visibleLocations(gameId: String): AppResult<List<PlayerLocation>>
    suspend fun myQrToken(gameId: String): AppResult<QrToken>
    suspend fun capture(gameId: String, scannedToken: String): AppResult<QrScanResult>
    suspend fun rescue(gameId: String, scannedToken: String): AppResult<QrScanResult>
    suspend fun forfeit(gameId: String): AppResult<Game>
    suspend fun result(gameId: String): AppResult<GameResultSummary>
}

/** 경기 동기화 상태. 화면은 이 값만 보고 그린다. */
data class GameSessionState(
    val gameId: String,
    val snapshot: GameSnapshot? = null,
    /** 마지막으로 서버 상태를 받아 온 시각(단말 시계). */
    val syncedAtEpochMillis: Long? = null,
    /** 다른 참가자 위치를 마지막으로 받은 시각(단말 시계). 30초 넘게 없으면 "지연" 표시. */
    val locationsReceivedAtEpochMillis: Long? = null,
    val syncError: String? = null,
    val lastSentAtEpochMillis: Long? = null,
    val sendError: String? = null,
    val myLocation: PlayerLocation? = null,
    val myLocationError: String? = null,
)

/** 화면 알림(배너·진동·시스템 알림)으로 보여 줄 중요 이벤트. 같은 eventId 는 한 번만 나온다. */
data class GameNotice(val eventId: Long, val type: com.nettwork.policethief.shared.model.GameEventType, val title: String, val message: String, val important: Boolean)

/**
 * [실제 서버] 경기 하나를 서버와 계속 맞추는 저장소(앱 범위).
 * - WebSocket 이벤트(경기 이벤트·위치)를 반영하고, 이벤트 ID·경기 버전으로 중복·순서 역전을 무시한다.
 * - WebSocket 재연결·이벤트 누락에 대비해 주기적으로 GET /state 를 다시 받는다.
 * - 경기 중에는 내 위치를 약 15초마다 서버로 보낸다(백그라운드 서비스가 켜져 있으면 서비스가 보낸다).
 */
interface GameSessionRepository {
    val state: StateFlow<GameSessionState?>
    val notices: kotlinx.coroutines.flow.Flow<GameNotice>

    /** 같은 경기면 이미 진행 중인 동기화를 유지한다. */
    fun start(gameId: String)
    fun stop()
    suspend fun refresh()
}

/** [개발 서버 전용] 봇 참가자·서버 시간 이동(서버 PNT_DEV_TOOLS=true). */
interface DevToolsRepository {
    suspend fun createBots(count: Int, near: PlayerLocation?): AppResult<Int>
    suspend fun advanceClock(millis: Long): AppResult<Long>
    suspend fun gameBots(gameId: String): AppResult<List<DevBot>>
    suspend fun botScan(botUserId: String, token: String): AppResult<QrScanResult>
    suspend fun moveBot(botUserId: String, latitude: Double, longitude: Double): AppResult<Unit>
}

data class DevBot(val userId: String, val nickname: String, val role: PlayerRole?, val status: com.nettwork.policethief.shared.model.PlayerStatus?, val qrToken: String?)

/**
 * 개발 모드에서만 쓰는 Mock 경기 조작기. 서버가 없을 때 체포·수감·종료 흐름을 화면에서 확인하기 위한 도구이며
 * 실제 서버 구현에는 존재하지 않는다(실제로는 서버가 판정해 이벤트로 알려 준다).
 */
interface MockGameController {
    fun setMockRole(role: PlayerRole?)
    fun mockRole(): PlayerRole?
    fun simulateJailArrival(gameId: String)
    fun simulateFinish(gameId: String, result: GameResult, reason: GameEndReason)

    /** Mock 도둑/동료의 QR 토큰(스캔 시뮬레이션용). */
    fun sampleTokens(gameId: String): Map<String, String>
}
