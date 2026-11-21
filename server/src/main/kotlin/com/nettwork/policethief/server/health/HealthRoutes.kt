package com.nettwork.policethief.server.health

import com.nettwork.policethief.shared.api.ApiPaths
import com.nettwork.policethief.shared.dto.HealthResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.time.Clock

/** GET /api/health (인증 불필요). DB 가 응답하지 않으면 503 과 database=DOWN 을 돌려준다. */
fun Route.healthRoutes(isDatabaseHealthy: () -> Boolean, version: String, clock: Clock) {
    get(ApiPaths.HEALTH) {
        val dbUp = isDatabaseHealthy()
        call.respond(
            if (dbUp) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
            HealthResponse(
                status = if (dbUp) "UP" else "DEGRADED",
                database = if (dbUp) "UP" else "DOWN",
                version = version,
                serverTimeEpochMillis = clock.millis(),
            ),
        )
    }
}
