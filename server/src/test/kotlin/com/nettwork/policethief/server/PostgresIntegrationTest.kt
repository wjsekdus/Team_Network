package com.nettwork.policethief.server

import com.nettwork.policethief.server.auth.AuthService
import com.nettwork.policethief.server.auth.ExposedAuthTokenRepository
import com.nettwork.policethief.server.auth.ExposedUserRepository
import com.nettwork.policethief.server.config.DatabaseConfig
import com.nettwork.policethief.server.database.AppDatabase
import com.nettwork.policethief.server.location.ExposedLocationRepository
import com.nettwork.policethief.server.location.LocationService
import com.nettwork.policethief.shared.dto.LocationUpdateRequest
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 실제 PostgreSQL 바이너리(zonky embedded-postgres)를 내려받아 띄운 뒤
 * Flyway 마이그레이션과 Exposed 쿼리가 PostgreSQL 에서도 동작하는지 확인한다.
 * (H2 호환 모드만으로는 PostgreSQL 전용 문법 오류를 놓칠 수 있기 때문)
 */
class PostgresIntegrationTest {
    @Test
    fun migrationsAndRepositories_workOnRealPostgres() {
        EmbeddedPostgres.start().use { postgres ->
            val db = AppDatabase.connect(
                DatabaseConfig(
                    jdbcUrl = postgres.getJdbcUrl("postgres", "postgres"),
                    user = "postgres",
                    password = "postgres",
                    maxPoolSize = 2,
                ),
            )
            db.use {
                assertTrue(db.isHealthy())
                val clock = TestClock()
                val auth = AuthService(ExposedUserRepository(db), ExposedAuthTokenRepository(db), clock, Duration.ofDays(30))
                val locations = LocationService(ExposedLocationRepository(db), clock, allowMockLocations = false)

                runBlocking {
                    val login = auth.guestLogin("포스트그레스")
                    val principal = assertNotNull(auth.authenticate(login.accessToken))
                    assertEquals(UUID.fromString(login.user.userId), principal.userId)

                    locations.submit(principal.userId, LocationUpdateRequest(37.5, 127.0, 5.0, clock.millis()))
                    assertEquals(37.5, locations.latest(principal.userId).latitude)
                }
            }
        }
    }
}
