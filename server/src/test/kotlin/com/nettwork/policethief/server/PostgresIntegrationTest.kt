package com.nettwork.policethief.server

import com.nettwork.policethief.server.auth.AuthService
import com.nettwork.policethief.server.auth.ExposedAuthTokenRepository
import com.nettwork.policethief.server.auth.ExposedUserRepository
import com.nettwork.policethief.server.config.DatabaseConfig
import com.nettwork.policethief.server.database.AppDatabase
import com.nettwork.policethief.server.location.ExposedLocationRepository
import com.nettwork.policethief.server.location.LocationService
import com.nettwork.policethief.shared.dto.JoinMatchmakingRequest
import com.nettwork.policethief.shared.dto.LocationUpdateRequest
import com.nettwork.policethief.shared.dto.QrScanRequest
import com.nettwork.policethief.shared.model.GameStatus
import com.nettwork.policethief.shared.model.MatchmakingStatus
import com.nettwork.policethief.shared.model.PlayerRole
import com.nettwork.policethief.shared.model.PlayerStatus
import com.nettwork.policethief.shared.rules.GameRules
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

    /** V2 마이그레이션, 매칭 행 잠금(FOR UPDATE), 경기 저장·복원이 실제 PostgreSQL 에서 동작하는지. */
    @Test
    fun matchmakingAndCapture_workOnRealPostgres() {
        EmbeddedPostgres.start().use { postgres ->
            val db = AppDatabase.connect(DatabaseConfig(postgres.getJdbcUrl("postgres", "postgres"), "postgres", "postgres", 4))
            db.use {
                val clock = TestClock()
                val deps = ServerDependencies.create(testConfig(), db, clock)
                runBlocking {
                    val users = (1..4).map { UUID.fromString(deps.authService.guestLogin("피지$it").user.userId) }
                    users.forEachIndexed { i, id ->
                        val point = around(20.0, i * 90.0)
                        deps.matchmaking.join(id, JoinMatchmakingRequest(LocationUpdateRequest(point.latitude, point.longitude, 5.0, clock.millis())))
                    }
                    val game = deps.games.currentGame(users[0])
                    assertEquals(MatchmakingStatus.MATCHED, deps.matchmaking.myTicket(users[0]).status)

                    clock.now = clock.now.plusMillis(GameRules.PREPARATION_MILLIS)
                    deps.games.tick()
                    val gameId = UUID.fromString(game.gameId)
                    val roles = deps.games.getGame(users[0], gameId).players.associate { UUID.fromString(it.userId) to it.role }
                    val police = roles.filterValues { it == PlayerRole.POLICE }.keys.first()
                    val thief = roles.filterValues { it == PlayerRole.THIEF }.keys.first()
                    val token = deps.games.myQr(thief, gameId).token
                    val result = deps.games.capture(police, gameId, QrScanRequest(token, "pg-1", clock.millis()))
                    assertTrue(result.accepted)

                    // 재시작 후에도 상태가 남아 있다.
                    val restarted = ServerDependencies.create(testConfig(), db, clock)
                    val reloaded = restarted.games.getGame(police, gameId)
                    assertEquals(GameStatus.PLAYING, reloaded.status)
                    assertEquals(PlayerStatus.ESCORTED, reloaded.players.first { it.userId == thief.toString() }.status)
                }
            }
        }
    }
}
