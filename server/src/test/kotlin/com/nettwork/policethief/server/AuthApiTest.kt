package com.nettwork.policethief.server

import com.nettwork.policethief.shared.api.ApiPaths
import com.nettwork.policethief.shared.dto.ErrorCode
import com.nettwork.policethief.shared.dto.ErrorResponse
import com.nettwork.policethief.shared.dto.GuestLoginRequest
import com.nettwork.policethief.shared.dto.HealthResponse
import com.nettwork.policethief.shared.dto.UserDto
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HealthApiTest {
    @Test
    fun health_returnsUp_withDatabaseStatus() = serverTest { client ->
        val response = client.get(ApiPaths.HEALTH)
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<HealthResponse>()
        assertEquals("UP", body.status)
        assertEquals("UP", body.database)
    }

    @Test
    fun unknownPath_returnsJsonNotFound() = serverTest { client ->
        val response = client.get("/api/does-not-exist")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(ErrorCode.NOT_FOUND, response.body<ErrorResponse>().code)
    }
}

class AuthApiTest {
    @Test
    fun guestLogin_createsUserAndToken() = serverTest { client ->
        val response = client.post(ApiPaths.AUTH_GUEST) {
            contentType(ContentType.Application.Json)
            setBody(GuestLoginRequest("  경찰1호  "))
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val login = response.body<com.nettwork.policethief.shared.dto.GuestLoginResponse>()
        assertEquals("경찰1호", login.user.nickname)
        assertTrue(login.accessToken.length >= 40, "토큰은 충분히 길어야 한다")
    }

    @Test
    fun sameNickname_getsDifferentAccounts() = serverTest { client ->
        val first = client.guestLogin("동명이인")
        val second = client.guestLogin("동명이인")
        assertNotEquals(first.user.userId, second.user.userId)
        assertNotEquals(first.accessToken, second.accessToken)
    }

    @Test
    fun usersMe_returnsOwnProfile_forValidToken() = serverTest { client ->
        val login = client.guestLogin("도둑")
        val response = client.get(ApiPaths.USERS_ME) { bearerAuth(login.accessToken) }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(login.user.userId, response.body<UserDto>().userId)
    }

    @Test
    fun usersMe_withoutToken_isUnauthorized() = serverTest { client ->
        val response = client.get(ApiPaths.USERS_ME)
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(ErrorCode.UNAUTHORIZED, response.body<ErrorResponse>().code)
    }

    @Test
    fun usersMe_withForgedToken_isUnauthorized() = serverTest { client ->
        client.guestLogin()
        val response = client.get(ApiPaths.USERS_ME) { bearerAuth("forged-token-value") }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun expiredToken_isRejected() {
        val clock = TestClock()
        serverTest(clock = clock) { client ->
            val login = client.guestLogin()
            clock.now = clock.now.plus(Duration.ofDays(31))
            assertEquals(HttpStatusCode.Unauthorized, client.get(ApiPaths.USERS_ME) { bearerAuth(login.accessToken) }.status)
        }
    }

    @Test
    fun invalidNickname_returnsValidationError() = serverTest { client ->
        val response = client.post(ApiPaths.AUTH_GUEST) {
            contentType(ContentType.Application.Json)
            setBody(GuestLoginRequest("<script>"))
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        val error = response.body<ErrorResponse>()
        assertEquals(ErrorCode.VALIDATION_FAILED, error.code)
        assertEquals("INVALID_CHARACTERS", error.details["nickname"])
    }

    @Test
    fun malformedJson_returnsBadRequest() = serverTest { client ->
        val response = client.post(ApiPaths.AUTH_GUEST) {
            contentType(ContentType.Application.Json)
            setBody("""{"nick":""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(ErrorCode.BAD_REQUEST, response.body<ErrorResponse>().code)
    }
}
