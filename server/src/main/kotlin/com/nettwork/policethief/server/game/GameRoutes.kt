package com.nettwork.policethief.server.game

import com.nettwork.policethief.server.auth.requireUser
import com.nettwork.policethief.server.common.ValidationException
import com.nettwork.policethief.server.matchmaking.MatchmakingService
import com.nettwork.policethief.shared.api.ApiPaths
import com.nettwork.policethief.shared.dto.JoinMatchmakingRequest
import com.nettwork.policethief.shared.dto.QrScanRequest
import com.nettwork.policethief.shared.dto.RingThiefRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.util.UUID

/**
 * 매칭·경기·QR 관련 경로. 모두 인증 필요(authenticate 블록 안에 등록).
 * 사용자 식별은 항상 토큰(requireUser)으로 하며, 요청 본문의 사용자 ID 는 받지 않는다.
 * 라우트는 요청을 읽고 서비스에 넘기는 일만 한다. 판정은 GameEngine / MatchmakingService 가 한다.
 */
fun Route.gameRoutes(matchmaking: MatchmakingService, games: GameEngine) {
    post(ApiPaths.MATCHMAKING) {
        val request = call.receive<JoinMatchmakingRequest>()
        call.respond(HttpStatusCode.Created, matchmaking.join(call.requireUser().userId, request))
    }
    get(ApiPaths.MATCHMAKING_ME) {
        call.respond(matchmaking.myTicket(call.requireUser().userId))
    }
    delete(ApiPaths.MATCHMAKING_ME) {
        matchmaking.cancel(call.requireUser().userId)
        call.respond(HttpStatusCode.NoContent)
    }

    get(ApiPaths.GAMES_CURRENT) {
        call.respond(games.currentGame(call.requireUser().userId))
    }
    get(ApiPaths.game("{gameId}")) {
        call.respond(games.getGame(call.requireUser().userId, call.gameId()))
    }
    get(ApiPaths.gameState("{gameId}")) {
        call.respond(games.state(call.requireUser().userId, call.gameId()))
    }
    get(ApiPaths.gameVisibleLocations("{gameId}")) {
        call.respond(games.visibleLocations(call.requireUser().userId, call.gameId()))
    }
    post(ApiPaths.gameForfeit("{gameId}")) {
        call.respond(games.forfeit(call.requireUser().userId, call.gameId()))
    }
    get(ApiPaths.gameEvents("{gameId}")) {
        val raw = call.request.queryParameters["after"]
        val after = if (raw == null) 0L else raw.toLongOrNull()?.takeIf { it >= 0 }
            ?: throw ValidationException("after 는 0 이상의 숫자여야 합니다.", mapOf("after" to raw))
        call.respond(games.events(call.requireUser().userId, call.gameId(), after))
    }
    get(ApiPaths.gameResult("{gameId}")) {
        call.respond(games.result(call.requireUser().userId, call.gameId()))
    }
    get(ApiPaths.gameMyQr("{gameId}")) {
        call.respond(games.myQr(call.requireUser().userId, call.gameId()))
    }
    post(ApiPaths.gameCaptures("{gameId}")) {
        val request = call.receive<QrScanRequest>()
        call.respond(games.capture(call.requireUser().userId, call.gameId(), request))
    }
    post(ApiPaths.gameRescues("{gameId}")) {
        val request = call.receive<QrScanRequest>()
        call.respond(games.rescue(call.requireUser().userId, call.gameId(), request))
    }
    post(ApiPaths.gameRing("{gameId}")) {
        val request = call.receive<RingThiefRequest>()
        call.respond(games.ring(call.requireUser().userId, call.gameId(), request))
    }
}

fun RoutingCall.gameId(): UUID = uuidParameter("gameId")

fun RoutingCall.uuidParameter(name: String): UUID {
    val raw = parameters[name]
    return raw?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: throw ValidationException("$name 형식이 올바르지 않습니다.", mapOf(name to (raw ?: "")))
}
