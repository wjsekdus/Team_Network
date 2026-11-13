package com.nettwork.policethief.shared.api

/**
 * REST / WebSocket 경로. 서버 라우팅과 클라이언트 호출이 같은 상수를 사용해 오타로 인한 계약 불일치를 막는다.
 * 문서: docs/api/API.md
 */
object ApiPaths {
    // --- 구현됨 ---
    const val HEALTH = "/api/health"
    const val AUTH_GUEST = "/api/auth/guest"
    const val USERS_ME = "/api/users/me"
    const val LOCATIONS = "/api/locations"
    const val LOCATIONS_ME_LATEST = "/api/locations/me/latest"
    const val WEBSOCKET = "/ws"

    // --- 계약만 정의됨 (서버는 501 NOT_IMPLEMENTED 응답) ---
    const val MATCHMAKING = "/api/matchmaking"
    const val MATCHMAKING_ME = "/api/matchmaking/me"
    const val GAMES_CURRENT = "/api/games/current"

    fun game(gameId: String) = "/api/games/$gameId"
    fun gameVisibleLocations(gameId: String) = "/api/games/$gameId/locations"
    fun gameMyQr(gameId: String) = "/api/games/$gameId/qr/me"
    fun gameCaptures(gameId: String) = "/api/games/$gameId/captures"
    fun gameRescues(gameId: String) = "/api/games/$gameId/rescues"
    fun gameForfeit(gameId: String) = "/api/games/$gameId/forfeit"
    fun gameEvents(gameId: String) = "/api/games/$gameId/events"
}
