package com.nettwork.policethief.domain.repository

import com.nettwork.policethief.domain.model.AppResult
import com.nettwork.policethief.domain.model.Game
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

/** [Mock / 향후 서버] 4인 매칭. */
interface MatchmakingRepository {
    val ticket: StateFlow<MatchmakingTicket?>
    suspend fun join(location: PlayerLocation): AppResult<MatchmakingTicket>
    suspend fun cancel(): AppResult<Unit>
}

/** [Mock / 향후 서버] 경기 진행 정보와 QR 체포·구출. 모든 판정은 서버가 한다. */
interface GameRepository {
    suspend fun getGame(gameId: String): AppResult<Game>

    /** 서버가 내 역할에 맞게 걸러 준 위치 목록(경찰: 전원, 도둑: 도둑끼리). */
    suspend fun visibleLocations(gameId: String): AppResult<List<PlayerLocation>>
    suspend fun myQrToken(gameId: String): AppResult<QrToken>
    suspend fun capture(gameId: String, scannedToken: String): AppResult<QrScanResult>
    suspend fun rescue(gameId: String, scannedToken: String): AppResult<QrScanResult>
    suspend fun forfeit(gameId: String): AppResult<Game>
}

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
