package com.nettwork.policethief.data.mock

import com.nettwork.policethief.core.EpochClock
import com.nettwork.policethief.domain.model.AppError
import com.nettwork.policethief.domain.model.AppResult
import com.nettwork.policethief.domain.model.Game
import com.nettwork.policethief.domain.model.GamePlayer
import com.nettwork.policethief.domain.model.MatchmakingTicket
import com.nettwork.policethief.domain.model.PlayerLocation
import com.nettwork.policethief.domain.model.QrScanResult
import com.nettwork.policethief.domain.model.QrToken
import com.nettwork.policethief.domain.repository.AuthRepository
import com.nettwork.policethief.domain.repository.GameRepository
import com.nettwork.policethief.domain.repository.MatchmakingRepository
import com.nettwork.policethief.domain.repository.MockGameController
import com.nettwork.policethief.shared.model.GameEndReason
import com.nettwork.policethief.shared.model.GameResult
import com.nettwork.policethief.shared.model.GameStatus
import com.nettwork.policethief.shared.model.LocationSource
import com.nettwork.policethief.shared.model.MatchmakingStatus
import com.nettwork.policethief.shared.model.PlayerConnectionState
import com.nettwork.policethief.shared.model.PlayerRole
import com.nettwork.policethief.shared.model.PlayerStatus
import com.nettwork.policethief.shared.rules.GameRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ⚠️ Mock 전용. 서버의 매칭·경기 기능이 구현되기 전까지 화면 흐름을 확인하기 위한 "가짜 서버".
 *
 * - 실제 판정은 서버가 해야 하며, 이 클래스의 규칙 처리는 화면 확인용 단순 흉내이다.
 * - 개발 빌드 + 설정의 "Mock 게임 데이터" 가 켜져 있을 때만 DI 에서 선택된다(di/AppModules.kt).
 * - 다른 참가자 3명은 가상의 인물이며, 위치는 내 위치 주변에 고정 배치한다.
 */
class MockGameBackend(
    private val scope: CoroutineScope,
    private val clock: EpochClock,
    private val auth: AuthRepository,
    private val matchStepDelayMillis: Long = 1_500,
) : MatchmakingRepository, GameRepository, MockGameController {

    private val ticketState = MutableStateFlow<MatchmakingTicket?>(null)
    override val ticket: StateFlow<MatchmakingTicket?> = ticketState.asStateFlow()

    private val games = mutableMapOf<String, Game>()
    private var gameCounter = 0
    private var matchJob: Job? = null
    private var forcedRole: PlayerRole? = null

    private val myId: String get() = auth.currentUser.value?.userId ?: LOCAL_USER_ID
    private val myNickname: String get() = auth.currentUser.value?.nickname ?: "나"

    // ---------------- MatchmakingRepository ----------------

    override suspend fun join(location: PlayerLocation): AppResult<MatchmakingTicket> {
        matchJob?.cancel()
        val now = clock.nowMillis()
        val waiting = MatchmakingTicket(MatchmakingStatus.WAITING, now, now + GameRules.MATCH_TIMEOUT_MILLIS, 1, null)
        ticketState.value = waiting
        matchJob = scope.launch {
            for (count in 2..GameRules.PLAYERS_PER_GAME) {
                delay(matchStepDelayMillis)
                ticketState.value = ticketState.value?.copy(nearbyWaitingCount = count)
            }
            delay(matchStepDelayMillis / 2)
            val game = createGame(location)
            ticketState.value = ticketState.value?.copy(status = MatchmakingStatus.MATCHED, gameId = game.gameId)
        }
        return AppResult.Success(waiting)
    }

    override suspend fun cancel(): AppResult<Unit> {
        matchJob?.cancel()
        ticketState.value = ticketState.value?.copy(status = MatchmakingStatus.CANCELLED)
        return AppResult.Success(Unit)
    }

    // ---------------- GameRepository ----------------

    override suspend fun getGame(gameId: String): AppResult<Game> = withGame(gameId) { AppResult.Success(refreshStatus(it)) }

    /**
     * 서버 규칙을 흉내 낸 공개 범위: 준비 단계(READY)에는 4명 최초 위치 전원 공개(S-07),
     * 경기 중에는 경찰은 전원, 도둑은 도둑끼리만. 내 위치는 화면이 직접 GPS 로 표시한다.
     */
    override suspend fun visibleLocations(gameId: String): AppResult<List<PlayerLocation>> = withGame(gameId) { stored ->
        val game = refreshStatus(stored)
        val myRole = game.player(myId)?.role
        val visible = game.players
            .filter { it.userId != myId }
            .filter { game.status == GameStatus.READY || myRole == PlayerRole.POLICE || it.role == PlayerRole.THIEF }
            .mapIndexed { index, player -> mockLocationOf(game, player, index) }
        AppResult.Success(visible)
    }

    override suspend fun myQrToken(gameId: String): AppResult<QrToken> = withGame(gameId) { game ->
        AppResult.Success(QrToken(game.gameId, myId, tokenOf(game.gameId, myId), game.startsAtEpochMillis ?: 0))
    }

    override suspend fun capture(gameId: String, scannedToken: String): AppResult<QrScanResult> = withGame(gameId) { game ->
        val me = game.player(myId)
        val target = targetOf(game, scannedToken)
        val reject = when {
            me?.role != PlayerRole.POLICE -> "NOT_ALLOWED"
            !scannedToken.startsWith(tokenPrefix(game.gameId)) -> if (scannedToken.startsWith(TOKEN_PREFIX)) "OTHER_GAME" else "INVALID_TOKEN"
            target == null || target.role != PlayerRole.THIEF -> "INVALID_TOKEN"
            target.status != PlayerStatus.ACTIVE -> "ALREADY_CAPTURED"
            game.players.count { it.capturedBy == myId && it.status == PlayerStatus.ESCORTED } >= GameRules.MAX_ESCORTS_PER_POLICE -> "ESCORT_LIMIT"
            else -> null
        }
        if (reject != null) return@withGame AppResult.Success(QrScanResult(false, target?.userId, target?.status, reject))

        var updated = game.updatePlayer(target!!.userId) { it.copy(status = PlayerStatus.ESCORTED, capturedBy = myId) }
        // 승패 우선 규칙: 두 도둑의 체포가 모두 확정되면 수감 여부와 관계없이 경찰 승리.
        if (updated.players.filter { it.role == PlayerRole.THIEF }.none { it.status == PlayerStatus.ACTIVE }) {
            updated = updated.finish(GameResult.POLICE_WIN, GameEndReason.ALL_THIEVES_CAPTURED)
        }
        games[gameId] = updated
        AppResult.Success(QrScanResult(true, target.userId, PlayerStatus.ESCORTED, null))
    }

    override suspend fun rescue(gameId: String, scannedToken: String): AppResult<QrScanResult> = withGame(gameId) { game ->
        val me = game.player(myId)
        val target = targetOf(game, scannedToken)
        val reject = when {
            me?.role != PlayerRole.THIEF || me.status != PlayerStatus.ACTIVE -> "NOT_ALLOWED"
            !scannedToken.startsWith(tokenPrefix(game.gameId)) -> if (scannedToken.startsWith(TOKEN_PREFIX)) "OTHER_GAME" else "INVALID_TOKEN"
            target == null || target.role != PlayerRole.THIEF -> "INVALID_TOKEN"
            target.status == PlayerStatus.ESCORTED -> "TARGET_ESCORTED"
            target.status != PlayerStatus.JAILED -> "TARGET_NOT_JAILED"
            else -> null
        }
        if (reject != null) return@withGame AppResult.Success(QrScanResult(false, target?.userId, target?.status, reject))
        games[gameId] = game.updatePlayer(target!!.userId) { it.copy(status = PlayerStatus.ACTIVE, capturedBy = null) }
        AppResult.Success(QrScanResult(true, target.userId, PlayerStatus.ACTIVE, null))
    }

    override suspend fun forfeit(gameId: String): AppResult<Game> = withGame(gameId) { game ->
        val cancelled = game.updatePlayer(myId) { it.copy(status = PlayerStatus.ELIMINATED, connectionState = PlayerConnectionState.LEFT) }
            .copy(status = GameStatus.CANCELLED, result = GameResult.INVALID, endReason = GameEndReason.PLAYER_FORFEITED, endedAtEpochMillis = clock.nowMillis())
        games[gameId] = cancelled
        AppResult.Success(cancelled)
    }

    // ---------------- MockGameController ----------------

    override fun setMockRole(role: PlayerRole?) {
        forcedRole = role
    }

    override fun mockRole(): PlayerRole? = forcedRole

    override fun simulateJailArrival(gameId: String) {
        val game = games[gameId] ?: return
        val myRole = game.player(myId)?.role
        games[gameId] = if (myRole == PlayerRole.POLICE) {
            game.players.filter { it.capturedBy == myId && it.status == PlayerStatus.ESCORTED }
                .fold(game) { acc, p -> acc.updatePlayer(p.userId) { it.copy(status = PlayerStatus.JAILED) } }
        } else {
            // 도둑 시점: 동료 도둑이 다른 경찰에게 잡혀 감옥에 수감된 상황을 만든다(구출 스캔 확인용).
            game.players.firstOrNull { it.role == PlayerRole.THIEF && it.userId != myId }
                ?.let { mate -> game.updatePlayer(mate.userId) { it.copy(status = PlayerStatus.JAILED, capturedBy = POLICE_2) } } ?: game
        }
    }

    override fun simulateFinish(gameId: String, result: GameResult, reason: GameEndReason) {
        games[gameId]?.let { games[gameId] = it.finish(result, reason) }
    }

    override fun sampleTokens(gameId: String): Map<String, String> {
        val game = games[gameId] ?: return emptyMap()
        return game.players.filter { it.userId != myId && it.role == PlayerRole.THIEF }
            .associate { it.nickname to tokenOf(gameId, it.userId) } +
            ("다른 경기의 QR" to tokenOf("mock-game-other", "someone")) +
            ("위조된 QR" to "FORGED-1234")
    }

    // ---------------- 내부 ----------------

    private fun createGame(location: PlayerLocation): Game {
        gameCounter++
        val gameId = "mock-game-$gameCounter"
        val now = clock.nowMillis()
        val myRole = forcedRole ?: if (kotlin.random.Random.nextBoolean()) PlayerRole.POLICE else PlayerRole.THIEF
        val others = if (myRole == PlayerRole.POLICE) {
            listOf(POLICE_2 to PlayerRole.POLICE, THIEF_1 to PlayerRole.THIEF, THIEF_2 to PlayerRole.THIEF)
        } else {
            listOf(POLICE_1 to PlayerRole.POLICE, POLICE_2 to PlayerRole.POLICE, THIEF_2 to PlayerRole.THIEF)
        }
        val players = listOf(player(myId, myNickname, myRole)) + others.map { (id, role) -> player(id, NICKNAMES.getValue(id), role) }
        val startsAt = now + GameRules.PREPARATION_MILLIS
        val game = Game(
            gameId = gameId,
            status = GameStatus.READY,
            centerLatitude = location.latitude,
            centerLongitude = location.longitude,
            currentRadiusMeters = GameRules.INITIAL_ZONE_RADIUS_METERS,
            jailRadiusMeters = GameRules.JAIL_RADIUS_METERS,
            startsAtEpochMillis = startsAt,
            endsAtEpochMillis = startsAt + GameRules.GAME_DURATION_MILLIS,
            endedAtEpochMillis = null,
            result = null,
            endReason = null,
            players = players,
        )
        games[gameId] = game
        return game
    }

    private fun player(id: String, nickname: String, role: PlayerRole) =
        GamePlayer(id, nickname, role, PlayerStatus.ACTIVE, null, 0, PlayerConnectionState.CONNECTED)

    private fun refreshStatus(game: Game): Game {
        if (game.status != GameStatus.READY && game.status != GameStatus.PLAYING) return game
        val now = clock.nowMillis()
        val refreshed = when {
            game.endsAtEpochMillis != null && now >= game.endsAtEpochMillis ->
                game.finish(GameResult.THIEF_WIN, GameEndReason.TIME_OVER_THIEVES_SURVIVED)
            game.startsAtEpochMillis != null && now >= game.startsAtEpochMillis -> game.copy(status = GameStatus.PLAYING)
            else -> game
        }
        games[game.gameId] = refreshed
        return refreshed
    }

    private fun Game.finish(result: GameResult, reason: GameEndReason) = copy(
        status = if (result == GameResult.INVALID) GameStatus.CANCELLED else GameStatus.FINISHED,
        result = result,
        endReason = reason,
        endedAtEpochMillis = clock.nowMillis(),
    )

    private fun Game.updatePlayer(userId: String, transform: (GamePlayer) -> GamePlayer) =
        copy(players = players.map { if (it.userId == userId) transform(it) else it })

    private fun mockLocationOf(game: Game, player: GamePlayer, index: Int): PlayerLocation {
        val offsets = listOf(0.0011 to 0.0009, -0.0012 to 0.0014, 0.0007 to -0.0016, -0.0005 to -0.0008)
        val (dLat, dLon) = offsets[index % offsets.size]
        return PlayerLocation(player.userId, game.centerLatitude + dLat, game.centerLongitude + dLon, 8.0, clock.nowMillis(), LocationSource.MOCK)
    }

    private fun targetOf(game: Game, token: String): GamePlayer? =
        game.players.firstOrNull { tokenOf(game.gameId, it.userId) == token }

    private inline fun <T> withGame(gameId: String, block: (Game) -> AppResult<T>): AppResult<T> {
        val game = games[gameId] ?: return AppResult.Failure(AppError.Server(404, "Mock 경기를 찾을 수 없습니다: $gameId"))
        return block(game)
    }

    private fun tokenPrefix(gameId: String) = "$TOKEN_PREFIX$gameId:"
    private fun tokenOf(gameId: String, userId: String) = tokenPrefix(gameId) + userId

    companion object {
        const val LOCAL_USER_ID = "local-user"
        private const val TOKEN_PREFIX = "MOCKQR:"
        private const val POLICE_1 = "mock-police-1"
        private const val POLICE_2 = "mock-police-2"
        private const val THIEF_1 = "mock-thief-1"
        private const val THIEF_2 = "mock-thief-2"
        private val NICKNAMES = mapOf(POLICE_1 to "김경찰", POLICE_2 to "박경찰", THIEF_1 to "이도둑", THIEF_2 to "최도둑")
    }
}
