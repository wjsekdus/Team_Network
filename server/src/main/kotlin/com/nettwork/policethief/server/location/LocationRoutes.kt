package com.nettwork.policethief.server.location

import com.nettwork.policethief.server.auth.requireUser
import com.nettwork.policethief.shared.api.ApiPaths
import com.nettwork.policethief.shared.dto.LocationUpdateRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** 인증 필요. userId 는 요청 본문이 아니라 토큰에서 꺼내므로 다른 사람 위치를 대신 보낼 수 없다. */
fun Route.locationRoutes(locationService: LocationService) {
    post(ApiPaths.LOCATIONS) {
        val request = call.receive<LocationUpdateRequest>()
        call.respond(HttpStatusCode.Created, locationService.submit(call.requireUser().userId, request))
    }
    get(ApiPaths.LOCATIONS_ME_LATEST) {
        call.respond(locationService.latest(call.requireUser().userId))
    }
}
