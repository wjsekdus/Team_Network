package com.nettwork.policethief.server

import com.nettwork.policethief.shared.api.ApiPaths
import com.nettwork.policethief.shared.dto.ErrorCode
import com.nettwork.policethief.shared.dto.ErrorResponse
import com.nettwork.policethief.shared.dto.LocationDto
import com.nettwork.policethief.shared.dto.LocationUpdateRequest
import com.nettwork.policethief.shared.model.LocationSource
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocationApiTest {
    private val clock = TestClock()
    private fun request(
        latitude: Double = 37.5665,
        longitude: Double = 126.9780,
        measuredAt: Long = clock.millis() - 1_000,
        source: LocationSource = LocationSource.GPS,
    ) = LocationUpdateRequest(latitude, longitude, accuracyMeters = 8.0, measuredAtEpochMillis = measuredAt, source = source)

    private suspend fun HttpClient.send(token: String?, body: LocationUpdateRequest): HttpResponse =
        post(ApiPaths.LOCATIONS) {
            token?.let { bearerAuth(it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    @Test
    fun validLocation_isStored_andReturnedAsLatest() = serverTest(clock) { client ->
        val token = client.guestLogin().accessToken
        val response = client.send(token, request())
        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals(clock.millis(), response.body<LocationDto>().receivedAtEpochMillis)

        client.send(token, request(latitude = 37.5700))
        val latest = client.get(ApiPaths.LOCATIONS_ME_LATEST) { bearerAuth(token) }.body<LocationDto>()
        assertEquals(37.5700, latest.latitude)
        assertEquals(LocationSource.GPS, latest.source)
    }

    @Test
    fun location_requiresAuthentication() = serverTest(clock) { client ->
        assertEquals(HttpStatusCode.Unauthorized, client.send(null, request()).status)
    }

    @Test
    fun invalidCoordinates_areRejected_withReasons() = serverTest(clock) { client ->
        val token = client.guestLogin().accessToken
        val response = client.send(token, request(latitude = 120.0, measuredAt = clock.millis() + 3_600_000))
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        val error = response.body<ErrorResponse>()
        assertEquals(ErrorCode.VALIDATION_FAILED, error.code)
        assertTrue("LATITUDE_OUT_OF_RANGE" in error.details)
        assertTrue("MEASURED_IN_FUTURE" in error.details)
    }

    @Test
    fun mockLocation_isForbidden_whenServerDisallowsIt() = serverTest(clock, allowMockLocations = false) { client ->
        val token = client.guestLogin().accessToken
        val response = client.send(token, request(source = LocationSource.MOCK))
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("MOCK_LOCATION_NOT_ALLOWED", response.body<ErrorResponse>().details["source"])
    }

    @Test
    fun mockLocation_isStoredSeparately_whenAllowed() = serverTest(clock, allowMockLocations = true) { client ->
        val token = client.guestLogin().accessToken
        assertEquals(HttpStatusCode.Created, client.send(token, request(source = LocationSource.MOCK)).status)
        val latest = client.get(ApiPaths.LOCATIONS_ME_LATEST) { bearerAuth(token) }.body<LocationDto>()
        assertEquals(LocationSource.MOCK, latest.source)
    }

    @Test
    fun latest_withoutAnyLocation_isNotFound() = serverTest(clock) { client ->
        val token = client.guestLogin().accessToken
        assertEquals(HttpStatusCode.NotFound, client.get(ApiPaths.LOCATIONS_ME_LATEST) { bearerAuth(token) }.status)
    }

    @Test
    fun usersCannotReadEachOthersLatestLocation() = serverTest(clock) { client ->
        val alice = client.guestLogin("앨리스").accessToken
        val bob = client.guestLogin("밥돌이").accessToken
        client.send(alice, request())
        // 밥은 위치를 보낸 적이 없으므로 앨리스의 위치가 아니라 404 를 받아야 한다.
        assertEquals(HttpStatusCode.NotFound, client.get(ApiPaths.LOCATIONS_ME_LATEST) { bearerAuth(bob) }.status)
    }
}
