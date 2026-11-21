package com.nettwork.policethief.server.plugins

import com.nettwork.policethief.server.auth.AuthService
import com.nettwork.policethief.server.common.ApiException
import com.nettwork.policethief.shared.api.ApiJson
import com.nettwork.policethief.shared.dto.ErrorCode
import com.nettwork.policethief.shared.dto.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import kotlin.time.Duration.Companion.seconds

/** Ktor 인증 설정 이름. 라우트에서 authenticate(AUTH_BEARER) { ... } 로 사용한다. */
const val AUTH_BEARER = "bearer"

fun Application.configureSerialization() {
    install(ContentNegotiation) { json(ApiJson) }
}

fun Application.configureMonitoring() {
    install(DefaultHeaders)
    install(CallLogging) {
        // 토큰이 로그에 남지 않도록 헤더는 기록하지 않고 메서드·경로·상태만 기록한다(기본 형식).
        filter { it.request.path().startsWith("/api") || it.request.path() == "/ws" }
    }
}

fun Application.configureSecurity(authService: AuthService) {
    install(Authentication) {
        bearer(AUTH_BEARER) {
            realm = "police-and-thief"
            authenticate { credential -> authService.authenticate(credential.token) }
        }
    }
}

fun Application.configureWebSockets() {
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 30.seconds
        maxFrameSize = 64 * 1024L
        masking = false
    }
}

/** 모든 실패를 ErrorResponse JSON 형식으로 통일한다. 내부 예외 메시지는 클라이언트에 노출하지 않는다. */
fun Application.configureErrorHandling() {
    install(StatusPages) {
        exception<ApiException> { call, e ->
            call.respond(e.status, ErrorResponse(e.code, e.message, e.details))
        }
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(ErrorCode.BAD_REQUEST, "요청 형식이 올바르지 않습니다."))
        }
        exception<Throwable> { call, e ->
            call.application.environment.log.error("처리되지 않은 서버 오류: ${call.request.path()}", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(ErrorCode.INTERNAL_ERROR, "서버 내부 오류가 발생했습니다."))
        }
        status(HttpStatusCode.Unauthorized) { call, status ->
            call.respond(status, ErrorResponse(ErrorCode.UNAUTHORIZED, "인증이 필요하거나 토큰이 유효하지 않습니다."))
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(status, ErrorResponse(ErrorCode.NOT_FOUND, "요청한 경로를 찾을 수 없습니다."))
        }
    }
}
