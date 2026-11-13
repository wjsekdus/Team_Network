package com.nettwork.policethief.server.game

import com.nettwork.policethief.server.auth.requireUser
import com.nettwork.policethief.server.common.ValidationException
import com.nettwork.policethief.shared.api.ApiPaths
import com.nettwork.policethief.shared.dto.JoinMatchmakingRequest
import com.nettwork.policethief.shared.dto.QrScanRequest
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
 * 현재 서비스 구현이 NotImplemented 이므로 501 응답을 돌려준다.
 */
fun Route.gameRoutes(
    matchmaking: MatchmakingService,
    games: GameService,
    qrTokens: QrTokenService,
    captures: CaptureService,
    rescues: RescueService,
) {
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
    get(ApiPaths.gameVisibleLocations("{gameId}")) {
        call.respond(games.visibleLocations(call.requireUser().userId, call.gameId()))
    }
    post(ApiPaths.gameForfeit("{gameId}")) {
        call.respond(games.forfeit(call.requireUser().userId, call.gameId()))
    }
    get(ApiPaths.gameEvents("{gameId}")) {
        val after = call.request.queryParameters["after"]?.toLongOrNull() ?: 0L
        call.respond(games.events(call.requireUser().userId, call.gameId(), after))
    }
    get(ApiPaths.gameMyQr("{gameId}")) {
        call.respond(qrTokens.myToken(call.requireUser().userId, call.gameId()))
    }
    post(ApiPaths.gameCaptures("{gameId}")) {
        val request = call.receive<QrScanRequest>()
        call.respond(captures.capture(call.requireUser().userId, call.gameId(), request))
    }
    post(ApiPaths.gameRescues("{gameId}")) {
        val request = call.receive<QrScanRequest>()
        call.respond(rescues.rescue(call.requireUser().userId, call.gameId(), request))
    }
}

private fun RoutingCall.gameId(): UUID {
    val raw = parameters["gameId"]
    return raw?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: throw ValidationException("gameId 형식이 올바르지 않습니다.", mapOf("gameId" to (raw ?: "")))
}
