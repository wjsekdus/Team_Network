package com.nettwork.policethief

import com.nettwork.policethief.data.mock.MockGameBackend
import com.nettwork.policethief.domain.model.AppError
import com.nettwork.policethief.domain.model.AppResult
import com.nettwork.policethief.domain.model.Game
import com.nettwork.policethief.domain.model.User
import com.nettwork.policethief.domain.repository.AuthRepository
import com.nettwork.policethief.domain.repository.SessionRestoreResult
import com.nettwork.policethief.domain.usecase.GuestLoginUseCase
import com.nettwork.policethief.platform.AppPermission
import com.nettwork.policethief.platform.PermissionState
import com.nettwork.policethief.presentation.login.LoginEvent
import com.nettwork.policethief.presentation.login.LoginViewModel
import com.nettwork.policethief.shared.model.GameResult
import com.nettwork.policethief.shared.model.GameStatus
import com.nettwork.policethief.shared.model.MatchmakingStatus
import com.nettwork.policethief.shared.model.PlayerRole
import com.nettwork.policethief.shared.model.PlayerStatus
import com.nettwork.policethief.shared.rules.GameRules
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 로그인 결과를 테스트가 직접 조절하는 가짜 AuthRepository. */
private class FakeAuthRepository : AuthRepository {
    val user = MutableStateFlow<User?>(null)
    var nextLogin: CompletableDeferred<AppResult<User>> = CompletableDeferred()
    var lastNickname: String? = null
    override val currentUser: StateFlow<User?> = user
    override suspend fun guestLogin(nickname: String): AppResult<User> {
        lastNickname = nickname
        return nextLogin.await().also { if (it is AppResult.Success) user.value = it.value }
    }
    override suspend fun restoreSession(): AppResult<SessionRestoreResult> = AppResult.Success(SessionRestoreResult.NoSession)
    override suspend fun refreshMe(): AppResult<User> = AppResult.Failure(AppError.Unauthorized)
    override suspend fun logout() {
        user.value = null
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val auth = FakeAuthRepository()
    private val permissions = FakePermissionController()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = LoginViewModel(GuestLoginUseCase(auth), permissions)

    @Test
    fun blankNickname_cannotSubmit() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onNicknameChange("   ")
        assertFalse(vm.uiState.value.canSubmit)
    }

    @Test
    fun invalidNickname_showsFieldError_withoutCallingServer() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onNicknameChange("a")
        vm.submit()
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.fieldError)
        assertFalse(vm.uiState.value.isSubmitting)
        assertNull(auth.lastNickname)
    }

    @Test
    fun submit_goesThroughSubmitting_thenEmitsLoggedIn() = runTest(dispatcher) {
        permissions.states[AppPermission.LOCATION] = PermissionState.DENIED
        val vm = viewModel()
        vm.onNicknameChange("  경찰왕 ")
        vm.submit()
        runCurrent()
        assertTrue(vm.uiState.value.isSubmitting, "서버 응답 전에는 제출 중 상태")
        vm.submit() // 중복 제출은 무시되어야 한다
        auth.nextLogin.complete(AppResult.Success(User("u", "경찰왕", 0, 0)))
        advanceUntilIdle()
        assertEquals("경찰왕", auth.lastNickname)
        assertFalse(vm.uiState.value.isSubmitting)
        assertEquals(LoginEvent.LoggedIn(needsPermission = true), vm.events.first())
    }

    @Test
    fun serverFailure_keepsNickname_andShowsError() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onNicknameChange("도둑왕")
        vm.submit()
        auth.nextLogin.complete(AppResult.Failure(AppError.Network("refused")))
        advanceUntilIdle()
        assertEquals("도둑왕", vm.uiState.value.nickname)
        assertNotNull(vm.uiState.value.submitError)
        assertTrue(vm.uiState.value.canSubmit, "실패 후 다시 시도할 수 있어야 한다")
    }
}

/** Mock 게임 백엔드가 확정된 게임 규칙을 화면 확인용으로 올바르게 흉내 내는지 확인한다. */
@OptIn(ExperimentalCoroutinesApi::class)
class MockGameBackendTest {
    private val clock = FixedClock()
    private val auth = FakeAuthRepository().apply { user.value = User("me", "나", 0, 0) }

    private suspend fun TestScope.matchedGame(role: PlayerRole): Pair<MockGameBackend, Game> {
        val backend = MockGameBackend(this, clock, auth, matchStepDelayMillis = 100)
        backend.setMockRole(role)
        backend.join(sampleLocation())
        advanceTimeBy(1_000)
        runCurrent()
        val ticket = backend.ticket.value!!
        assertEquals(MatchmakingStatus.MATCHED, ticket.status)
        assertEquals(GameRules.PLAYERS_PER_GAME, ticket.nearbyWaitingCount)
        clock.now += GameRules.PREPARATION_MILLIS // 준비시간 종료 → PLAYING
        val game = (backend.getGame(ticket.gameId!!) as AppResult.Success).value
        return backend to game
    }

    @Test
    fun matchedGame_hasTwoPoliceTwoThieves_andStartsAfterPreparation() = runTest {
        val (_, game) = matchedGame(PlayerRole.POLICE)
        assertEquals(2, game.players.count { it.role == PlayerRole.POLICE })
        assertEquals(2, game.players.count { it.role == PlayerRole.THIEF })
        assertEquals(GameStatus.PLAYING, game.status)
        assertEquals(GameRules.JAIL_RADIUS_METERS, game.jailRadiusMeters)
    }

    @Test
    fun thief_seesOnlyThieves_whilePlaying() = runTest {
        val (backend, game) = matchedGame(PlayerRole.THIEF)
        val visible = (backend.visibleLocations(game.gameId) as AppResult.Success).value
        assertTrue(visible.isNotEmpty())
        assertTrue(visible.all { game.player(it.userId!!)?.role == PlayerRole.THIEF })
    }

    @Test
    fun police_canEscortOnlyOneThief_andWinsWhenAllCaptured() = runTest {
        val (backend, game) = matchedGame(PlayerRole.POLICE)
        val tokens = backend.sampleTokens(game.gameId)
        val thiefTokens = game.players.filter { it.role == PlayerRole.THIEF }.map { tokens.getValue(it.nickname) }

        val first = (backend.capture(game.gameId, thiefTokens[0]) as AppResult.Success).value
        assertTrue(first.accepted)
        assertEquals(PlayerStatus.ESCORTED, first.targetStatus)

        val second = (backend.capture(game.gameId, thiefTokens[1]) as AppResult.Success).value
        assertEquals("ESCORT_LIMIT", second.rejectReason)

        backend.simulateJailArrival(game.gameId)
        val third = (backend.capture(game.gameId, thiefTokens[1]) as AppResult.Success).value
        assertTrue(third.accepted)
        val finished = (backend.getGame(game.gameId) as AppResult.Success).value
        assertEquals(GameResult.POLICE_WIN, finished.result)
    }

    @Test
    fun qrFromOtherGame_isRejected() = runTest {
        val (backend, game) = matchedGame(PlayerRole.POLICE)
        val result = (backend.capture(game.gameId, backend.sampleTokens(game.gameId).getValue("다른 경기의 QR")) as AppResult.Success).value
        assertEquals("OTHER_GAME", result.rejectReason)
    }

    @Test
    fun thief_canRescueJailedTeammate() = runTest {
        val (backend, game) = matchedGame(PlayerRole.THIEF)
        val mate = game.players.first { it.role == PlayerRole.THIEF && it.userId != "me" }
        backend.simulateJailArrival(game.gameId)
        val token = "MOCKQR:${game.gameId}:${mate.userId}"
        val result = (backend.rescue(game.gameId, token) as AppResult.Success).value
        assertTrue(result.accepted)
        assertEquals(PlayerStatus.ACTIVE, result.targetStatus)
    }

    @Test
    fun forfeit_cancelsGame_asInvalid() = runTest {
        val (backend, game) = matchedGame(PlayerRole.POLICE)
        val cancelled = (backend.forfeit(game.gameId) as AppResult.Success).value
        assertEquals(GameStatus.CANCELLED, cancelled.status)
        assertEquals(GameResult.INVALID, cancelled.result)
    }

    @Test
    fun unknownGame_returnsFailure() = runTest {
        val backend = MockGameBackend(this, clock, auth)
        assertIs<AppResult.Failure>(backend.getGame("nope"))
    }
}
