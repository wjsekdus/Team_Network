package com.nettwork.policethief.server

import com.nettwork.policethief.server.config.AppConfig
import com.nettwork.policethief.server.config.DatabaseConfig
import com.nettwork.policethief.server.database.AppDatabase
import com.nettwork.policethief.shared.api.ApiJson
import com.nettwork.policethief.shared.api.ApiPaths
import com.nettwork.policethief.shared.dto.GuestLoginRequest
import com.nettwork.policethief.shared.dto.GuestLoginResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** 테스트에서 시간을 직접 움직이기 위한 시계(토큰 만료 등). */
class TestClock(var now: Instant = Instant.parse("2026-11-13T00:00:00Z")) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now
}

/** 테스트마다 새 H2(PostgreSQL 호환 모드) 메모리 DB 를 만들고 Flyway 마이그레이션을 적용한다. */
fun newH2Database(): AppDatabase = AppDatabase.connect(
    DatabaseConfig(
        jdbcUrl = "jdbc:h2:mem:test_${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        user = "sa",
        password = "",
        maxPoolSize = 4,
    ),
)

fun testConfig(allowMockLocations: Boolean = false) = AppConfig(
    host = "127.0.0.1",
    port = 0,
    database = DatabaseConfig("unused", "unused", "unused", 1),
    allowMockLocations = allowMockLocations,
    tokenTtlDays = 30,
    appVersion = "test",
)

/** 서버 모듈 전체를 띄운 상태에서 테스트 본문을 실행한다. */
fun serverTest(
    clock: TestClock = TestClock(),
    allowMockLocations: Boolean = false,
    block: suspend ApplicationTestBuilder.(HttpClient) -> Unit,
) {
    newH2Database().use { db ->
        val deps = ServerDependencies.create(testConfig(allowMockLocations), db, clock)
        testApplication {
            application { module(deps) }
            val client = createClient {
                install(ContentNegotiation) { json(ApiJson) }
                install(WebSockets)
            }
            block(client)
        }
    }
}

suspend fun HttpClient.guestLogin(nickname: String = "테스터"): GuestLoginResponse =
    post(ApiPaths.AUTH_GUEST) {
        contentType(ContentType.Application.Json)
        setBody(GuestLoginRequest(nickname))
    }.body()
