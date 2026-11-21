package com.nettwork.policethief.shared.api

/**
 * REST / WebSocket 경로. 서버 라우팅과 클라이언트 호출이 같은 상수를 사용해 오타로 인한 계약 불일치를 막는다.
 * 문서: docs/api/API.md
 */
object ApiPaths {
    const val HEALTH = "/api/health"
    const val AUTH_GUEST = "/api/auth/guest"
    const val USERS_ME = "/api/users/me"
    const val LOCATIONS = "/api/locations"
    const val LOCATIONS_ME_LATEST = "/api/locations/me/latest"
    const val WEBSOCKET = "/ws"

    // --- 매칭 ---
    const val MATCHMAKING = "/api/matchmaking"
    const val MATCHMAKING_ME = "/api/matchmaking/me"

    // --- 경기 ---
    const val GAMES_CURRENT = "/api/games/current"

    fun game(gameId: String) = "/api/games/$gameId"
    fun gameState(gameId: String) = "/api/games/$gameId/state"
    fun gameVisibleLocations(gameId: String) = "/api/games/$gameId/locations"
    fun gameMyQr(gameId: String) = "/api/games/$gameId/qr/me"
    fun gameCaptures(gameId: String) = "/api/games/$gameId/captures"
    fun gameRescues(gameId: String) = "/api/games/$gameId/rescues"
    fun gameForfeit(gameId: String) = "/api/games/$gameId/forfeit"
    fun gameEvents(gameId: String) = "/api/games/$gameId/events"
    fun gameResult(gameId: String) = "/api/games/$gameId/result"
    fun gameRing(gameId: String) = "/api/games/$gameId/ring"

    // --- 개발자 도구 (서버 PNT_DEV_TOOLS=true 일 때만 존재, 운영에서는 404) ---
    const val DEV_STATUS = "/api/dev/status"
    const val DEV_BOTS = "/api/dev/bots"
    const val DEV_CLOCK = "/api/dev/clock"
    const val DEV_CLOCK_ADVANCE = "/api/dev/clock/advance"

    fun devBotLocation(botUserId: String) = "/api/dev/bots/$botUserId/location"
    fun devBotScan(botUserId: String) = "/api/dev/bots/$botUserId/scan"
    fun devBotPresence(botUserId: String) = "/api/dev/bots/$botUserId/presence"
    fun devGameBots(gameId: String) = "/api/dev/games/$gameId/bots"
}
