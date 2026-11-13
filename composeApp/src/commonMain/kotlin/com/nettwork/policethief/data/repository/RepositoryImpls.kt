package com.nettwork.policethief.data.repository

import com.nettwork.policethief.core.EpochClock
import com.nettwork.policethief.data.local.SessionLocalDataSource
import com.nettwork.policethief.data.local.ServerUrlProvider
import com.nettwork.policethief.data.mapper.toDomain
import com.nettwork.policethief.data.mapper.toRequest
import com.nettwork.policethief.data.remote.PoliceThiefApi
import com.nettwork.policethief.domain.model.AppError
import com.nettwork.policethief.domain.model.AppResult
import com.nettwork.policethief.domain.model.Game
import com.nettwork.policethief.domain.model.LocationFailureReason
import com.nettwork.policethief.domain.model.MatchmakingTicket
import com.nettwork.policethief.domain.model.PlayerLocation
import com.nettwork.policethief.domain.model.QrScanResult
import com.nettwork.policethief.domain.model.QrToken
import com.nettwork.policethief.domain.model.ServerStatus
import com.nettwork.policethief.domain.model.User
import com.nettwork.policethief.domain.model.map
import com.nettwork.policethief.domain.repository.AuthRepository
import com.nettwork.policethief.domain.repository.GameRepository
import com.nettwork.policethief.domain.repository.LocationRepository
import com.nettwork.policethief.domain.repository.MatchmakingRepository
import com.nettwork.policethief.domain.repository.ServerStatusRepository
import com.nettwork.policethief.domain.repository.SessionRestoreResult
import com.nettwork.policethief.platform.LocationException
import com.nettwork.policethief.platform.LocationProvider
import com.nettwork.policethief.shared.dto.JoinMatchmakingRequest
import com.nettwork.policethief.shared.dto.QrScanRequest
import com.nettwork.policethief.shared.rules.LocationValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/** [실제 서버] 게스트 로그인·세션 복구. */
class AuthRepositoryImpl(
    private val api: PoliceThiefApi,
    private val local: SessionLocalDataSource,
) : AuthRepository {
    private val user = MutableStateFlow<User?>(null)
    override val currentUser: StateFlow<User?> = user.asStateFlow()

    override suspend fun guestLogin(nickname: String): AppResult<User> =
        when (val result = api.guestLogin(nickname)) {
            is AppResult.Failure -> result
            is AppResult.Success -> {
                val loggedIn = result.value.user.toDomain()
                local.saveSession(result.value.accessToken, loggedIn)
                user.value = loggedIn
                AppResult.Success(loggedIn)
            }
        }

    /**
     * 저장된 토큰이 있으면 GET /api/users/me 로 유효성을 확인한다.
     * - 401: 토큰 폐기 후 NoSession (다시 로그인)
     * - 네트워크/서버 오류: 저장된 프로필로 "오프라인 복구"(로그인 상태 유지). 서버 연결 시 다시 확인한다.
     */
    override suspend fun restoreSession(): AppResult<SessionRestoreResult> {
        if (local.token() == null) return AppResult.Success(SessionRestoreResult.NoSession)
        return when (val me = api.me()) {
            is AppResult.Success -> {
                val restored = me.value.toDomain()
                local.saveUser(restored)
                user.value = restored
                AppResult.Success(SessionRestoreResult.Restored(restored, offline = false))
            }
            is AppResult.Failure -> when (me.error) {
                AppError.Unauthorized -> {
                    local.clear()
                    AppResult.Success(SessionRestoreResult.NoSession)
                }
                else -> {
                    val cached = local.cachedUser() ?: return me
                    user.value = cached
                    AppResult.Success(SessionRestoreResult.Restored(cached, offline = true))
                }
            }
        }
    }

    override suspend fun refreshMe(): AppResult<User> = api.me().map { it.toDomain() }.also { result ->
        if (result is AppResult.Success) {
            local.saveUser(result.value)
            user.value = result.value
        }
    }

    // TODO(Auth): 서버 측 토큰 폐기 API(POST /api/auth/logout)는 미구현. 현재는 기기에서만 삭제한다.
    override suspend fun logout() {
        local.clear()
        user.value = null
    }
}

/** [실제 서버] Health 확인. */
class ServerStatusRepositoryImpl(
    private val api: PoliceThiefApi,
    private val urls: ServerUrlProvider,
    private val clock: EpochClock,
) : ServerStatusRepository {
    override suspend fun check(): AppResult<ServerStatus> =
        api.health().map { it.toDomain(urls.baseUrl(), clock.nowMillis()) }
}

/** [실제 GPS/테스트 좌표 + 실제 서버] 위치 수집과 전송. */
class LocationRepositoryImpl(
    private val provider: LocationProvider,
    private val api: PoliceThiefApi,
    private val clock: EpochClock,
) : LocationRepository {
    private val last = MutableStateFlow<PlayerLocation?>(null)
    override val lastLocation: StateFlow<PlayerLocation?> = last.asStateFlow()

    private val lastSent = MutableStateFlow<Long?>(null)
    override val lastSentAtEpochMillis: StateFlow<Long?> = lastSent.asStateFlow()

    override suspend fun fetchCurrentLocation(): AppResult<PlayerLocation> = try {
        val location = provider.getCurrentLocation()
        last.value = location
        AppResult.Success(location)
    } catch (e: CancellationException) {
        throw e
    } catch (e: LocationException) {
        AppResult.Failure(AppError.Location(e.message ?: "위치 오류", e.toReason()))
    }

    override suspend fun sendLocation(location: PlayerLocation): AppResult<PlayerLocation> {
        val errors = LocationValidator.validate(
            location.latitude, location.longitude, location.accuracyMeters, location.timestampEpochMillis, clock.nowMillis(),
        )
        if (errors.isNotEmpty()) {
            return AppResult.Failure(
                AppError.Location("전송할 수 없는 위치 값입니다: ${errors.joinToString { it.name }}", LocationFailureReason.INVALID_DATA),
            )
        }
        return api.sendLocation(location.toRequest()).map { it.toDomain() }.also {
            if (it is AppResult.Success) lastSent.value = it.value.timestampEpochMillis
        }
    }

    private fun LocationException.toReason() = when (this) {
        is LocationException.PermissionDenied -> LocationFailureReason.PERMISSION_DENIED
        is LocationException.ServiceDisabled -> LocationFailureReason.SERVICE_DISABLED
        is LocationException.Timeout -> LocationFailureReason.TIMEOUT
        is LocationException.Unavailable -> LocationFailureReason.UNAVAILABLE
    }
}

/**
 * [실제 서버 – 향후] 매칭 API 를 호출한다. 현재 서버는 501 이므로 AppError.NotImplemented 가 돌아온다.
 * TODO(Matchmaking): 매칭 상태 변화는 WebSocket 이벤트 또는 GET /api/matchmaking/me 폴링으로 ticket 에 반영.
 */
class RemoteMatchmakingRepository(private val api: PoliceThiefApi) : MatchmakingRepository {
    private val current = MutableStateFlow<MatchmakingTicket?>(null)
    override val ticket: StateFlow<MatchmakingTicket?> = current.asStateFlow()

    override suspend fun join(location: PlayerLocation): AppResult<MatchmakingTicket> =
        api.joinMatchmaking(JoinMatchmakingRequest(location.toRequest())).map { it.toDomain() }
            .also { if (it is AppResult.Success) current.value = it.value }

    override suspend fun cancel(): AppResult<Unit> = api.cancelMatchmaking().also {
        if (it is AppResult.Success) current.value = null
    }
}

/** [실제 서버 – 향후] 경기 API. 현재 서버는 501 이다. */
class RemoteGameRepository(
    private val api: PoliceThiefApi,
    private val clock: EpochClock,
) : GameRepository {
    override suspend fun getGame(gameId: String): AppResult<Game> = api.game(gameId).map { it.toDomain() }

    override suspend fun visibleLocations(gameId: String): AppResult<List<PlayerLocation>> =
        api.visibleLocations(gameId).map { response -> response.locations.map { it.toDomain() } }

    override suspend fun myQrToken(gameId: String): AppResult<QrToken> = api.myQr(gameId).map { it.toDomain() }

    override suspend fun capture(gameId: String, scannedToken: String): AppResult<QrScanResult> =
        api.capture(gameId, scanRequest(scannedToken)).map { it.toDomain() }

    override suspend fun rescue(gameId: String, scannedToken: String): AppResult<QrScanResult> =
        api.rescue(gameId, scanRequest(scannedToken)).map { it.toDomain() }

    override suspend fun forfeit(gameId: String): AppResult<Game> = api.forfeit(gameId).map { it.toDomain() }

    private fun scanRequest(token: String) = QrScanRequest(
        token = token,
        requestId = Random.nextLong().toULong().toString(16),
        scannedAtEpochMillis = clock.nowMillis(),
    )
}
