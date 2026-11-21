package com.nettwork.policethief.server.auth

import com.nettwork.policethief.shared.api.ApiPaths
import com.nettwork.policethief.shared.dto.GuestLoginRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** 인증이 필요 없는 경로: 게스트 로그인. */
fun Route.publicAuthRoutes(authService: AuthService) {
    post(ApiPaths.AUTH_GUEST) {
        val request = call.receive<GuestLoginRequest>()
        call.respond(HttpStatusCode.Created, authService.guestLogin(request.nickname))
    }
}

/** 인증이 필요한 경로: 내 정보 조회. authenticate { } 블록 안에서 등록한다. */
fun Route.userRoutes(authService: AuthService) {
    get(ApiPaths.USERS_ME) {
        call.respond(authService.getUser(call.requireUser().userId))
    }
}

/** authenticate 블록 안에서는 항상 주체가 있다. 없으면 설정 오류이므로 예외로 드러낸다. */
fun RoutingCall.requireUser(): UserPrincipal =
    principal<UserPrincipal>() ?: error("인증 플러그인이 적용되지 않은 경로에서 requireUser()를 호출했습니다.")
