package com.nettwork.policethief

import com.nettwork.policethief.core.ServerClock
import com.nettwork.policethief.data.repository.GameNotices
import com.nettwork.policethief.data.repository.GameSessionRepositoryImpl
import com.nettwork.policethief.data.repository.RemoteGameRepository
import com.nettwork.policethief.domain.model.AppError
import com.nettwork.policethief.domain.model.AppResult
import com.nettwork.policethief.domain.model.Game
import com.nettwork.policethief.domain.model.GameEvent
import com.nettwork.policethief.domain.model.GamePlayer
import com.nettwork.policethief.domain.model.GameResultSummary
import com.nettwork.policethief.domain.model.GameSnapshot
import com.nettwork.policethief.domain.model.PlayerLocation
import com.nettwork.policethief.domain.model.QrScanResult
import com.nettwork.policethief.domain.model.QrToken
import com.nettwork.policethief.domain.model.RealtimeConnectionState
import com.nettwork.policethief.domain.model.RealtimeEvent
import com.nettwork.policethief.domain.model.User
import com.nettwork.policethief.domain.repository.AuthRepository
import com.nettwork.policethief.domain.repository.GameNotice
import com.nettwork.policethief.domain.repository.GameRepository
import com.nettwork.policethief.domain.repository.LocationRepository
import com.nettwork.policethief.domain.repository.RealtimeRepository
import com.nettwork.policethief.domain.repository.SessionRestoreResult
import com.nettwork.policethief.platform.BackgroundLocationController
import com.nettwork.policethief.shared.api.ApiJson
import com.nettwork.policethief.shared.dto.ErrorCode
import com.nettwork.policethief.shared.dto.ErrorResponse
import com.nettwork.policethief.shared.dto.QrScanRequest
import com.nettwork.policethief.shared.dto.QrScanResultDto
import com.nettwork.policethief.shared.model.GameEventKeys
import com.nettwork.policethief.shared.model.GameEventType
import com.nettwork.policethief.shared.model.GameResult
import com.nettwork.policethief.shared.model.GameStatus
import com.nettwork.policethief.shared.model.LocationSource
import com.nettwork.policethief.shared.model.PlayerConnectionState
import com.nettwork.policethief.shared.model.PlayerRole
import com.nettwork.policethief.shared.model.PlayerStatus
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val GAME = "g-1"
private const val ME = "me"

private fun player(id: String, role: PlayerRole, status: PlayerStatus = PlayerStatus.ACTIVE) =
    GamePlayer(id, id, role, status, null, 0, PlayerConnectionState.CONNECTED)

private fun game(version: Long, status: GameStatus = GameStatus.PLAYING, players: List<GamePlayer> = listOf(player(ME, PlayerRole.POLICE), player("t1", PlayerRole.THIEF))) = Game(
    gameId = GAME, status = status, centerLatitude = 37.5665, centerLongitude = 126.978, currentRadiusMeters = 500.0, jailRadiusMeters = 10.0,
    startsAtEpochMillis = 0, endsAtEpochMillis = 1_800_000, endedAtEpochMillis = null, result = null, endReason = null, players = players, version = version,
)

private fun event(id: Long, type: GameEventType, version: Long = 0, data: Map<String, String> = emptyMap()) =
    GameEvent(id, GAME, type, TEST_NOW, version, data)

private class FakeGames : GameRepository {
    val snapshots = ArrayDeque<GameSnapshot>()
    var stateCalls = 0
    var last: GameSnapshot? = null
    override suspend fun state(gameId: String): AppResult<GameSnapshot> {
        stateCalls++
        last = snapshots.removeFirstOrNull() ?: last
        return last?.let { AppResult.Success(it) } ?: AppResult.Failure(AppError.Network("no snapshot"))
    }
    override suspend fun currentGame(): AppResult<Game?> = AppResult.Success(null)
    override suspend fun getGame(gameId: String): AppResult<Game> = AppResult.Failure(AppError.Unknown(null))
    override suspend fun visibleLocations(gameId: String): AppResult<List<PlayerLocation>> = AppResult.Success(emptyList())
    override suspend fun myQrToken(gameId: String): AppResult<QrToken> = AppResult.Failure(AppError.Unknown(null))
    override suspend fun capture(gameId: String, scannedToken: String): AppResult<QrScanResult> = AppResult.Failure(AppError.Unknown(null))
    override suspend fun rescue(gameId: String, scannedToken: String): AppResult<QrScanResult> = AppResult.Failure(AppError.Unknown(null))
    override suspend fun forfeit(gameId: String): AppResult<Game> = AppResult.Failure(AppError.Unknown(null))
    override suspend fun result(gameId: String): AppResult<GameResultSummary> = AppResult.Failure(AppError.Unknown(null))
}

private class FakeRealtime : RealtimeRepository {
    val flow = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 16)
    override val connectionState: StateFlow<RealtimeConnectionState> = MutableStateFlow(RealtimeConnectionState.Disconnected)
    override val events: Flow<RealtimeEvent> = flow
    override fun connect() = Unit
    override fun disconnect() = Unit
    override suspend fun requestTestEvent(message: String): AppResult<Unit> = AppResult.Success(Unit)
}

private class FakeLocations : LocationRepository {
    var sent = 0
    override val lastLocation = MutableStateFlow<PlayerLocation?>(null)
    override val lastSentAtEpochMillis = MutableStateFlow<Long?>(null)
    override suspend fun fetchCurrentLocation(): AppResult<PlayerLocation> = AppResult.Success(sampleLocation())
    override suspend fun sendLocation(location: PlayerLocation): AppResult<PlayerLocation> {
        sent++
        lastSentAtEpochMillis.value = location.timestampEpochMillis
        return AppResult.Success(location)
    }
}

private class FakeBackground(override val isSupported: Boolean = true) : BackgroundLocationController {
    override val isRunning = MutableStateFlow(false)
    var starts = 0
    var stops = 0
    override fun start() {
        starts++
        isRunning.value = true
    }
    override fun stop() {
        stops++
        isRunning.value = false
    }
}

private class StaticAuth : AuthRepository {
    override val currentUser: StateFlow<User?> = MutableStateFlow(User(ME, ME, 0, 0))
    override suspend fun guestLogin(nickname: String): AppResult<User> = AppResult.Failure(AppError.Unknown(null))
    override suspend fun restoreSession(): AppResult<SessionRestoreResult> = AppResult.Success(SessionRestoreResult.NoSession)
    override suspend fun refreshMe(): AppResult<User> = AppResult.Failure(AppError.Unknown(null))
    override suspend fun logout() = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
class GameSessionRepositoryTest {
    private class Setup(scope: TestScope, background: FakeBackground = FakeBackground()) {
        val games = FakeGames()
        val realtime = FakeRealtime()
        val locations = FakeLocations()
        val background = background
        val repo = GameSessionRepositoryImpl(games, locations, realtime, StaticAuth(), background, FixedClock(), scope.backgroundScope)
        val notices = mutableListOf<GameNotice>()

        init {
            scope.backgroundScope.launch { repo.notices.collect { notices += it } }
        }
    }

    @Test
    fun duplicateEvent_isNotifiedOnce_andTriggersOneRefresh() = runTest {
        val s = Setup(this)
        s.games.snapshots += GameSnapshot(game(1), emptyList(), emptyList(), 0)
        s.repo.start(GAME)
        runCurrent()
        val callsAfterStart = s.games.stateCalls

        val arrest = event(5, GameEventType.THIEF_ARRESTED, 2, mapOf(GameEventKeys.NICKNAME to "t1"))
        s.games.snapshots += GameSnapshot(game(2), emptyList(), listOf(arrest), 5)
        s.realtime.flow.emit(RealtimeEvent.Game(arrest, TEST_NOW))
        runCurrent()
        s.realtime.flow.emit(RealtimeEvent.Game(arrest, TEST_NOW))
        runCurrent()

        assertEquals(1, s.notices.count { it.type == GameEventType.THIEF_ARRESTED })
        assertEquals(callsAfterStart + 1, s.games.stateCalls, "같은 이벤트를 다시 받아도 재조회하지 않는다")
        assertEquals(2, s.repo.state.value?.snapshot?.game?.version)
    }

    @Test
    fun olderVersionSnapshot_isIgnored() = runTest {
        val s = Setup(this)
        s.games.snapshots += GameSnapshot(game(5), emptyList(), emptyList(), 0)
        s.repo.start(GAME)
        runCurrent()
        s.games.snapshots += GameSnapshot(game(3, status = GameStatus.READY), emptyList(), emptyList(), 0)
        s.repo.refresh()
        assertEquals(5, s.repo.state.value?.snapshot?.game?.version)
        assertEquals(GameStatus.PLAYING, s.repo.state.value?.snapshot?.game?.status)
    }

    @Test
    fun eventsInFirstLoad_areNotReplayedAsNotices_butLaterOnesAre() = runTest {
        val s = Setup(this)
        s.games.snapshots += GameSnapshot(game(1), emptyList(), listOf(event(1, GameEventType.GAME_STARTED)), 1)
        s.repo.start(GAME)
        runCurrent()
        assertTrue(s.notices.isEmpty(), "앱 재실행 때 지난 알림을 다시 띄우지 않는다")
        // WebSocket 이 끊겨 놓친 이벤트는 주기적 재조회로 알림이 된다.
        s.games.snapshots += GameSnapshot(game(2), emptyList(), listOf(event(1, GameEventType.GAME_STARTED), event(2, GameEventType.ZONE_SHRUNK)), 2)
        advanceTimeBy(16_000)
        runCurrent()
        assertEquals(listOf(GameEventType.ZONE_SHRUNK), s.notices.map { it.type })
    }

    @Test
    fun welcomeForSameGame_triggersResync() = runTest {
        val s = Setup(this)
        s.games.snapshots += GameSnapshot(game(1), emptyList(), emptyList(), 0)
        s.repo.start(GAME)
        runCurrent()
        val before = s.games.stateCalls
        s.realtime.flow.emit(RealtimeEvent.Welcome(ME, TEST_NOW, activeGameId = GAME))
        runCurrent()
        assertEquals(before + 1, s.games.stateCalls)
    }

    @Test
    fun liveLocation_mergesNewer_andIgnoresOlder() = runTest {
        val s = Setup(this)
        val initial = PlayerLocation("t1", 37.0, 127.0, 5.0, TEST_NOW - 10_000, LocationSource.GPS)
        s.games.snapshots += GameSnapshot(game(1), listOf(initial), emptyList(), 0)
        s.repo.start(GAME)
        runCurrent()
        s.realtime.flow.emit(RealtimeEvent.Location(GAME, initial.copy(latitude = 38.0, timestampEpochMillis = TEST_NOW - 20_000), TEST_NOW))
        runCurrent()
        assertEquals(37.0, s.repo.state.value?.snapshot?.locations?.single()?.latitude, "더 오래된 측정값은 무시")
        s.realtime.flow.emit(RealtimeEvent.Location(GAME, initial.copy(latitude = 39.0, timestampEpochMillis = TEST_NOW), TEST_NOW))
        runCurrent()
        assertEquals(39.0, s.repo.state.value?.snapshot?.locations?.single()?.latitude)
    }

    @Test
    fun locationIsSentWhileBackgroundServiceIsOff_andSharingStopsWhenGameEnds() = runTest {
        val s = Setup(this, FakeBackground(isSupported = false))
        s.games.snapshots += GameSnapshot(game(1), emptyList(), emptyList(), 0)
        s.repo.start(GAME)
        runCurrent()
        assertEquals(1, s.locations.sent)
        advanceTimeBy(15_001)
        runCurrent()
        assertEquals(2, s.locations.sent)

        s.games.snapshots += GameSnapshot(game(2, status = GameStatus.FINISHED), emptyList(), emptyList(), 0)
        s.repo.refresh()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2, s.locations.sent, "경기가 끝나면 위치를 더 보내지 않는다")
        assertTrue(s.background.stops >= 1)
    }

    @Test
    fun backgroundServiceStarts_andAppDoesNotDoubleSend() = runTest {
        val s = Setup(this)
        s.games.snapshots += GameSnapshot(game(1), emptyList(), emptyList(), 0)
        s.repo.start(GAME)
        runCurrent()
        advanceTimeBy(45_000)
        runCurrent()
        assertEquals(1, s.background.starts)
        assertEquals(0, s.locations.sent, "백그라운드 서비스가 보내는 동안 앱은 따로 보내지 않는다")
    }
}

class GameNoticeTest {
    @Test
    fun arrestOfMe_isPersonal_andOthersSeeNickname() {
        val arrest = event(1, GameEventType.THIEF_ARRESTED, data = mapOf(GameEventKeys.USER_ID to ME, GameEventKeys.NICKNAME to "나", GameEventKeys.BY_NICKNAME to "경찰1"))
        assertTrue(GameNotices.from(arrest, ME)!!.title.contains("체포되었습니다"))
        assertTrue(GameNotices.from(arrest, "other")!!.message.contains("나 체포"))
    }

    @Test
    fun locationUpdates_produceNoNotice() {
        assertNull(GameNotices.from(event(0, GameEventType.PLAYER_LOCATION_UPDATED), ME))
    }
}

class ServerClockTest {
    @Test
    fun followsServerTime_evenIfDeviceClockIsWrong() {
        val device = FixedClock(TEST_NOW)
        val clock = ServerClock(device)
        clock.update(TEST_NOW + 90_000) // 단말 시계가 90초 느림
        device.now += 1_000
        assertEquals(TEST_NOW + 91_000, clock.nowMillis())
    }
}

class RemoteGameRepositoryTest {
    @Test
    fun currentGame_404_meansNoActiveGame() = runTest {
        val t = TestApi(InMemoryTokenStorage("tok")) {
            respondJson(ErrorResponse.serializer(), ErrorResponse(ErrorCode.NOT_FOUND, "없음", mapOf("reason" to "NO_ACTIVE_GAME")), HttpStatusCode.NotFound)
        }
        val repo = RemoteGameRepository(t.api, FixedClock(), ServerClock(FixedClock()))
        assertNull((repo.currentGame() as AppResult.Success).value)
    }

    @Test
    fun scanRetryAfterNetworkFailure_reusesRequestId() = runTest {
        var fail = true
        val t = TestApi(InMemoryTokenStorage("tok")) {
            if (fail) {
                fail = false
                throw IOException("timeout")
            }
            respondJson(QrScanResultDto.serializer(), QrScanResultDto(accepted = true, gameResult = GameResult.POLICE_WIN))
        }
        val repo = RemoteGameRepository(t.api, FixedClock(), ServerClock(FixedClock()))
        assertIs<AppError.Network>((repo.capture(GAME, "QR") as AppResult.Failure).error)
        assertTrue((repo.capture(GAME, "QR") as AppResult.Success).value.accepted)
        val ids = t.requests.map { ApiJson.decodeFromString(QrScanRequest.serializer(), (it.body as TextContent).text).requestId }
        assertEquals(2, ids.size)
        assertEquals(ids[0], ids[1], "재전송은 같은 requestId 로 보내 서버가 한 번만 판정하게 한다")
        // 판정을 받은 뒤의 새 스캔은 새 requestId 를 쓴다.
        repo.capture(GAME, "QR")
        val third = ApiJson.decodeFromString(QrScanRequest.serializer(), (t.requests.last().body as TextContent).text).requestId
        assertTrue(third != ids[0])
    }
}
