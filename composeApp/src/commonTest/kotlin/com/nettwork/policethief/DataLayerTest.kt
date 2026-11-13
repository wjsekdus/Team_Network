package com.nettwork.policethief

import com.nettwork.policethief.data.mapper.toDomain
import com.nettwork.policethief.data.mapper.toRequest
import com.nettwork.policethief.data.remote.WebSocketRealtimeRepository
import com.nettwork.policethief.data.repository.AuthRepositoryImpl
import com.nettwork.policethief.data.repository.LocationRepositoryImpl
import com.nettwork.policethief.domain.model.AppError
import com.nettwork.policethief.domain.model.AppResult
import com.nettwork.policethief.domain.model.LocationFailureReason
import com.nettwork.policethief.domain.model.User
import com.nettwork.policethief.domain.repository.SessionRestoreResult
import com.nettwork.policethief.platform.LocationException
import com.nettwork.policethief.shared.dto.ErrorCode
import com.nettwork.policethief.shared.dto.ErrorResponse
import com.nettwork.policethief.shared.dto.GameDto
import com.nettwork.policethief.shared.dto.GamePlayerDto
import com.nettwork.policethief.shared.dto.GuestLoginResponse
import com.nettwork.policethief.shared.dto.LocationDto
import com.nettwork.policethief.shared.dto.UserDto
import com.nettwork.policethief.shared.model.GameStatus
import com.nettwork.policethief.shared.model.LocationSource
import com.nettwork.policethief.shared.model.PlayerRole
import com.nettwork.policethief.shared.model.PlayerStatus
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.io.IOException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val userDto = UserDto("u-1", "철수", createdAtEpochMillis = 10, lastActiveAtEpochMillis = 20)

class DtoMapperTest {
    @Test
    fun userDto_mapsToDomain() {
        assertEquals(User("u-1", "철수", 10, 20), userDto.toDomain())
    }

    @Test
    fun location_roundTripsThroughRequestAndDto() {
        val location = sampleLocation().copy(source = LocationSource.MOCK)
        val request = location.toRequest()
        assertEquals(location.timestampEpochMillis, request.measuredAtEpochMillis)
        assertEquals(LocationSource.MOCK, request.source)
        val dto = LocationDto("u-1", request.latitude, request.longitude, request.accuracyMeters, request.measuredAtEpochMillis, TEST_NOW, request.source)
        assertEquals(location.copy(userId = "u-1"), dto.toDomain())
    }

    @Test
    fun gameDto_mapsPlayersAndStatus() {
        val dto = GameDto(
            gameId = "g", status = GameStatus.PLAYING, centerLatitude = 1.0, centerLongitude = 2.0,
            currentRadiusMeters = 500.0, jailRadiusMeters = 10.0, createdAtEpochMillis = 0,
            players = listOf(GamePlayerDto("p", "경찰", PlayerRole.POLICE, PlayerStatus.ACTIVE)),
        )
        val game = dto.toDomain()
        assertEquals(GameStatus.PLAYING, game.status)
        assertEquals(PlayerRole.POLICE, game.player("p")?.role)
    }
}

class PoliceThiefApiTest {
    @Test
    fun guestLogin_parsesResponse_andDoesNotSendToken() = runTest {
        val t = TestApi { respondJson(GuestLoginResponse.serializer(), GuestLoginResponse("tok", user = userDto), HttpStatusCode.Created) }
        val result = t.api.guestLogin("철수")
        assertIs<AppResult.Success<GuestLoginResponse>>(result)
        assertEquals("tok", result.value.accessToken)
        assertNull(t.requests.single().headers[HttpHeaders.Authorization])
        assertEquals("http://test.local/api/auth/guest", t.requests.single().url.toString())
    }

    @Test
    fun authorizedCall_attachesBearerToken() = runTest {
        val t = TestApi(InMemoryTokenStorage("abc")) { respondJson(UserDto.serializer(), userDto) }
        t.api.me()
        assertEquals("Bearer abc", t.requests.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun authorizedCall_withoutToken_failsWithoutNetwork() = runTest {
        val t = TestApi { error("호출되면 안 됨") }
        assertEquals(AppError.Unauthorized, (t.api.me() as AppResult.Failure).error)
        assertTrue(t.requests.isEmpty())
    }

    @Test
    fun errorResponses_mapToAppErrors() = runTest {
        var next: Pair<HttpStatusCode, ErrorResponse> = HttpStatusCode.Unauthorized to ErrorResponse(ErrorCode.UNAUTHORIZED, "x")
        val t = TestApi(InMemoryTokenStorage("abc")) { respondJson(ErrorResponse.serializer(), next.second, next.first) }

        assertEquals(AppError.Unauthorized, (t.api.me() as AppResult.Failure).error)

        next = HttpStatusCode.UnprocessableEntity to ErrorResponse(ErrorCode.VALIDATION_FAILED, "닉네임", mapOf("nickname" to "TOO_SHORT"))
        val validation = (t.api.me() as AppResult.Failure).error
        assertIs<AppError.Validation>(validation)
        assertEquals("TOO_SHORT", validation.fields["nickname"])

        next = HttpStatusCode.NotImplemented to ErrorResponse(ErrorCode.NOT_IMPLEMENTED, "아직 구현되지 않은 기능입니다: game.current")
        assertIs<AppError.NotImplemented>((t.api.game("g") as AppResult.Failure).error)
    }

    @Test
    fun nonJsonServerError_mapsToServerError() = runTest {
        val t = TestApi { respondRaw("<html>bad gateway</html>", HttpStatusCode.BadGateway) }
        val error = (t.api.health() as AppResult.Failure).error
        assertIs<AppError.Server>(error)
        assertEquals(502, error.httpStatus)
    }

    @Test
    fun networkFailure_mapsToNetworkError() = runTest {
        val t = TestApi { throw IOException("connection refused") }
        assertIs<AppError.Network>((t.api.health() as AppResult.Failure).error)
    }

    @Test
    fun serverUrlOverride_isUsed_inDevBuild() = runTest {
        val t = TestApi { respondRaw("{}", HttpStatusCode.OK) }
        t.devSettings.update { it.copy(serverUrlOverride = "https://abc.trycloudflare.com/") }
        t.api.health()
        assertEquals("https://abc.trycloudflare.com/api/health", t.requests.single().url.toString())
        assertEquals("wss://abc.trycloudflare.com/ws", t.urls.webSocketUrl("/ws"))
    }
}

class AuthRepositoryTest {
    @Test
    fun guestLogin_persistsTokenAndUser() = runTest {
        val t = TestApi { respondJson(GuestLoginResponse.serializer(), GuestLoginResponse("tok", user = userDto), HttpStatusCode.Created) }
        val repo = AuthRepositoryImpl(t.api, t.session)
        repo.guestLogin("철수")
        assertEquals("tok", t.tokenStorage.token)
        assertEquals("철수", t.session.cachedUser()?.nickname)
        assertEquals("u-1", repo.currentUser.value?.userId)
    }

    @Test
    fun restore_withoutToken_isNoSession() = runTest {
        val t = TestApi { error("호출되면 안 됨") }
        assertEquals(SessionRestoreResult.NoSession, (AuthRepositoryImpl(t.api, t.session).restoreSession() as AppResult.Success).value)
    }

    @Test
    fun restore_withValidToken_restoresUser() = runTest {
        val t = TestApi(InMemoryTokenStorage("tok")) { respondJson(UserDto.serializer(), userDto) }
        val restored = (AuthRepositoryImpl(t.api, t.session).restoreSession() as AppResult.Success).value
        assertEquals(SessionRestoreResult.Restored(userDto.toDomain(), offline = false), restored)
    }

    @Test
    fun restore_withRejectedToken_clearsSession() = runTest {
        val t = TestApi(InMemoryTokenStorage("expired")) {
            respondJson(ErrorResponse.serializer(), ErrorResponse(ErrorCode.UNAUTHORIZED, "x"), HttpStatusCode.Unauthorized)
        }
        val result = (AuthRepositoryImpl(t.api, t.session).restoreSession() as AppResult.Success).value
        assertEquals(SessionRestoreResult.NoSession, result)
        assertNull(t.tokenStorage.token)
    }

    @Test
    fun restore_offline_usesCachedProfile() = runTest {
        val t = TestApi(InMemoryTokenStorage("tok")) { throw IOException("offline") }
        t.session.saveUser(userDto.toDomain())
        val result = (AuthRepositoryImpl(t.api, t.session).restoreSession() as AppResult.Success).value
        assertEquals(SessionRestoreResult.Restored(userDto.toDomain(), offline = true), result)
        assertEquals("tok", t.tokenStorage.token)
    }
}

class LocationRepositoryTest {
    @Test
    fun providerFailure_mapsToReason_andKeepsLastLocation() = runTest {
        val provider = FakeLocationProvider()
        val t = TestApi { error("호출되면 안 됨") }
        val repo = LocationRepositoryImpl(provider, t.api, FixedClock())
        repo.fetchCurrentLocation()
        provider.next = { throw LocationException.PermissionDenied() }
        val failure = (repo.fetchCurrentLocation() as AppResult.Failure).error
        assertIs<AppError.Location>(failure)
        assertEquals(LocationFailureReason.PERMISSION_DENIED, failure.reason)
        assertEquals(sampleLocation(), repo.lastLocation.value)
    }

    @Test
    fun invalidLocation_isNotSentToServer() = runTest {
        val t = TestApi(InMemoryTokenStorage("tok")) { error("호출되면 안 됨") }
        val repo = LocationRepositoryImpl(FakeLocationProvider(), t.api, FixedClock())
        val result = repo.sendLocation(sampleLocation(timestamp = TEST_NOW - 3_600_000))
        assertEquals(LocationFailureReason.INVALID_DATA, ((result as AppResult.Failure).error as AppError.Location).reason)
        assertTrue(t.requests.isEmpty())
    }

    @Test
    fun validLocation_isSent_andRecordsSendTime() = runTest {
        val t = TestApi(InMemoryTokenStorage("tok")) {
            respondJson(LocationDto.serializer(), LocationDto("u-1", 37.5665, 126.978, 6.0, TEST_NOW - 1_000, TEST_NOW, LocationSource.GPS), HttpStatusCode.Created)
        }
        val repo = LocationRepositoryImpl(FakeLocationProvider(), t.api, FixedClock())
        assertIs<AppResult.Success<*>>(repo.sendLocation(sampleLocation()))
        assertEquals(TEST_NOW - 1_000, repo.lastSentAtEpochMillis.value)
    }
}

class RealtimeBackoffTest {
    @Test
    fun backoff_growsExponentially_andIsCapped() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), (1..7).map(WebSocketRealtimeRepository::backoffMillis))
    }
}
