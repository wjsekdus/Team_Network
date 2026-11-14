package com.nettwork.policethief.data.remote

import com.nettwork.policethief.data.local.ServerUrlProvider
import com.nettwork.policethief.domain.model.AppError
import com.nettwork.policethief.domain.model.AppResult
import com.nettwork.policethief.shared.api.ApiJson
import com.nettwork.policethief.shared.api.ApiPaths
import com.nettwork.policethief.shared.dto.DevBotLocationRequest
import com.nettwork.policethief.shared.dto.DevBotScanRequest
import com.nettwork.policethief.shared.dto.DevBotsResponse
import com.nettwork.policethief.shared.dto.DevClockAdvanceRequest
import com.nettwork.policethief.shared.dto.DevCreateBotsRequest
import com.nettwork.policethief.shared.dto.DevStatusDto
import com.nettwork.policethief.shared.dto.ErrorCode
import com.nettwork.policethief.shared.dto.ErrorResponse
import com.nettwork.policethief.shared.dto.GameDto
import com.nettwork.policethief.shared.dto.GameResultDto
import com.nettwork.policethief.shared.dto.GameStateDto
import com.nettwork.policethief.shared.dto.GuestLoginRequest
import com.nettwork.policethief.shared.dto.GuestLoginResponse
import com.nettwork.policethief.shared.dto.HealthResponse
import com.nettwork.policethief.shared.dto.JoinMatchmakingRequest
import com.nettwork.policethief.shared.dto.LocationDto
import com.nettwork.policethief.shared.dto.LocationUpdateRequest
import com.nettwork.policethief.shared.dto.MatchmakingTicketDto
import com.nettwork.policethief.shared.dto.QrScanRequest
import com.nettwork.policethief.shared.dto.QrScanResultDto
import com.nettwork.policethief.shared.dto.QrTokenDto
import com.nettwork.policethief.shared.dto.UserDto
import com.nettwork.policethief.shared.dto.VisibleLocationsResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlin.time.Duration.Companion.seconds

/** 공통 HttpClient 생성. 엔진(OkHttp/Darwin/MockEngine)은 밖에서 넣어 테스트에서 바꿀 수 있다. */
fun createHttpClient(engine: HttpClientEngine): HttpClient = HttpClient(engine) {
    expectSuccess = false
    install(ContentNegotiation) { json(ApiJson) }
    install(WebSockets) { pingIntervalMillis = 15.seconds.inWholeMilliseconds }
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        requestTimeoutMillis = 15_000
    }
}

/**
 * REST API 호출 모음(Remote DataSource).
 *
 * - 서버 주소는 호출할 때마다 [ServerUrlProvider] 에서 읽는다(설정 화면에서 주소를 바꾸면 즉시 반영).
 * - 모든 함수는 예외 대신 [AppResult] 를 돌려준다. HTTP 오류 본문(ErrorResponse)은 [AppError] 로 바뀐다.
 * - 인증이 필요한 요청에는 [tokenProvider] 의 토큰을 `Authorization: Bearer` 헤더로 붙인다.
 */
class PoliceThiefApi(
    @PublishedApi internal val client: HttpClient,
    @PublishedApi internal val urls: ServerUrlProvider,
    @PublishedApi internal val tokenProvider: () -> String?,
) {
    // ---- 구현된 API ----
    suspend fun health(): AppResult<HealthResponse> = send(HttpMethod.Get, ApiPaths.HEALTH, authorized = false)

    suspend fun guestLogin(nickname: String): AppResult<GuestLoginResponse> =
        send(HttpMethod.Post, ApiPaths.AUTH_GUEST, authorized = false) { jsonBody(GuestLoginRequest(nickname)) }

    suspend fun me(): AppResult<UserDto> = send(HttpMethod.Get, ApiPaths.USERS_ME)

    suspend fun sendLocation(request: LocationUpdateRequest): AppResult<LocationDto> =
        send(HttpMethod.Post, ApiPaths.LOCATIONS) { jsonBody(request) }

    // ---- 매칭·경기 ----
    suspend fun joinMatchmaking(request: JoinMatchmakingRequest): AppResult<MatchmakingTicketDto> =
        send(HttpMethod.Post, ApiPaths.MATCHMAKING) { jsonBody(request) }

    suspend fun myMatchmaking(): AppResult<MatchmakingTicketDto> = send(HttpMethod.Get, ApiPaths.MATCHMAKING_ME)

    suspend fun cancelMatchmaking(): AppResult<Unit> = send(HttpMethod.Delete, ApiPaths.MATCHMAKING_ME)

    suspend fun currentGame(): AppResult<GameDto> = send(HttpMethod.Get, ApiPaths.GAMES_CURRENT)

    suspend fun game(gameId: String): AppResult<GameDto> = send(HttpMethod.Get, ApiPaths.game(gameId))

    suspend fun gameState(gameId: String): AppResult<GameStateDto> = send(HttpMethod.Get, ApiPaths.gameState(gameId))

    suspend fun gameResult(gameId: String): AppResult<GameResultDto> = send(HttpMethod.Get, ApiPaths.gameResult(gameId))

    // ---- 개발자 도구(개발 서버에서만 존재, 운영 서버는 404) ----
    suspend fun devCreateBots(request: DevCreateBotsRequest): AppResult<DevBotsResponse> =
        send(HttpMethod.Post, ApiPaths.DEV_BOTS) { jsonBody(request) }

    suspend fun devAdvanceClock(millis: Long): AppResult<DevStatusDto> =
        send(HttpMethod.Post, ApiPaths.DEV_CLOCK_ADVANCE) { jsonBody(DevClockAdvanceRequest(millis)) }

    suspend fun devGameBots(gameId: String): AppResult<DevBotsResponse> = send(HttpMethod.Get, ApiPaths.devGameBots(gameId))

    suspend fun devBotScan(botUserId: String, token: String): AppResult<QrScanResultDto> =
        send(HttpMethod.Post, ApiPaths.devBotScan(botUserId)) { jsonBody(DevBotScanRequest(token)) }

    suspend fun devMoveBot(botUserId: String, request: DevBotLocationRequest): AppResult<Unit> =
        send(HttpMethod.Post, ApiPaths.devBotLocation(botUserId)) { jsonBody(request) }

    suspend fun visibleLocations(gameId: String): AppResult<VisibleLocationsResponse> =
        send(HttpMethod.Get, ApiPaths.gameVisibleLocations(gameId))

    suspend fun myQr(gameId: String): AppResult<QrTokenDto> = send(HttpMethod.Get, ApiPaths.gameMyQr(gameId))

    suspend fun capture(gameId: String, request: QrScanRequest): AppResult<QrScanResultDto> =
        send(HttpMethod.Post, ApiPaths.gameCaptures(gameId)) { jsonBody(request) }

    suspend fun rescue(gameId: String, request: QrScanRequest): AppResult<QrScanResultDto> =
        send(HttpMethod.Post, ApiPaths.gameRescues(gameId)) { jsonBody(request) }

    suspend fun forfeit(gameId: String): AppResult<GameDto> = send(HttpMethod.Post, ApiPaths.gameForfeit(gameId))

    // ---- 내부 공통 처리 ----
    @PublishedApi
    internal inline fun <reified B> HttpRequestBuilder.jsonBody(body: B) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    /** 요청 1건 실행 + 응답/예외를 AppResult 로 변환. URL 은 호출 시점의 서버 주소 + [path]. */
    @PublishedApi
    internal suspend inline fun <reified T> send(
        method: HttpMethod,
        path: String,
        authorized: Boolean = true,
        crossinline body: HttpRequestBuilder.() -> Unit = {},
    ): AppResult<T> {
        val token = if (authorized) tokenProvider() ?: return AppResult.Failure(AppError.Unauthorized) else null
        return try {
            val response = client.request(urls.baseUrl() + path) {
                this.method = method
                token?.let { bearerAuth(it) }
                body()
            }
            if (!response.status.isSuccess()) {
                AppResult.Failure(response.toAppError())
            } else if (T::class == Unit::class) {
                @Suppress("UNCHECKED_CAST")
                AppResult.Success(Unit as T)
            } else {
                AppResult.Success(response.body<T>())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 연결 거부, DNS 실패, 타임아웃, 잘못된 응답 형식 등
            AppResult.Failure(AppError.Network(e.message ?: e::class.simpleName))
        }
    }
}

/** HTTP 오류 응답 → AppError. 서버의 ErrorResponse 형식이 아니면 상태 코드로만 판단한다. */
@PublishedApi
internal suspend fun HttpResponse.toAppError(): AppError {
    val error = runCatching { ApiJson.decodeFromString(ErrorResponse.serializer(), bodyAsText()) }.getOrNull()
    return when {
        status.value == 401 || error?.code == ErrorCode.UNAUTHORIZED -> AppError.Unauthorized
        error?.code == ErrorCode.NOT_IMPLEMENTED -> AppError.NotImplemented(error.message.substringAfter(": "))
        error?.code == ErrorCode.VALIDATION_FAILED -> AppError.Validation(error.message, error.details)
        error != null -> AppError.Server(status.value, error.message, error.details["reason"])
        else -> AppError.Server(status.value, "서버 오류(HTTP ${status.value})")
    }
}
