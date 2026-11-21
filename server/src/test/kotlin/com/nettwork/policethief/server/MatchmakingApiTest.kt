package com.nettwork.policethief.server

import com.nettwork.policethief.server.matchmaking.MatchFinder
import com.nettwork.policethief.server.matchmaking.Waiter
import com.nettwork.policethief.shared.dto.ErrorResponse
import com.nettwork.policethief.shared.dto.GameDto
import com.nettwork.policethief.shared.dto.MatchmakingTicketDto
import com.nettwork.policethief.shared.geo.GeoMath
import com.nettwork.policethief.shared.model.GameStatus
import com.nettwork.policethief.shared.model.MatchmakingStatus
import com.nettwork.policethief.shared.model.PlayerRole
import com.nettwork.policethief.shared.rules.GameRules
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatchmakingApiTest {
    @Test
    fun fourNearbyPlayers_areMatched_withTwoPoliceAndTwoThieves() = gameServerTest {
        val players = players(4)
        players.take(3).forEachIndexed { i, p -> assertEquals(HttpStatusCode.Created, p.join(around(30.0, i * 120.0)).status) }
        val last = players[3].join(BASE).body<MatchmakingTicketDto>()
        assertEquals(MatchmakingStatus.MATCHED, last.status)
        val gameId = assertNotNull(last.gameId)

        val game = players[0].currentGameResponse().body<GameDto>()
        assertEquals(gameId, game.gameId)
        assertEquals(GameStatus.READY, game.status)
        assertEquals(GameRules.POLICE_COUNT, game.players.count { it.role == PlayerRole.POLICE })
        assertEquals(GameRules.THIEF_COUNT, game.players.count { it.role == PlayerRole.THIEF })
        assertEquals(GameRules.PREPARATION_MILLIS, game.startsAtEpochMillis - game.createdAtEpochMillis)
        assertEquals(GameRules.GAME_DURATION_MILLIS, game.endsAtEpochMillis - game.startsAtEpochMillis)
        assertEquals(GameRules.INITIAL_ZONE_RADIUS_METERS, game.initialRadiusMeters)
        assertEquals(GameRules.JAIL_RADIUS_METERS, game.jailRadiusMeters)
        // 모든 참가자가 같은 경기, 같은 역할 배정을 본다(역할은 한 번만 배정).
        players.forEach { assertEquals(game.players.map { it.role }, it.game(gameId).players.map { p -> p.role }) }
    }

    @Test
    fun threePlayers_keepWaiting_andSeeNearbyCount() = gameServerTest {
        val players = players(3)
        players.forEachIndexed { i, p -> p.join(around(10.0, i * 120.0)) }
        val ticket = players[0].ticket()
        assertEquals(MatchmakingStatus.WAITING, ticket.status)
        assertEquals(3, ticket.nearbyWaitingCount)
        assertEquals(GameRules.MATCH_TIMEOUT_MILLIS, ticket.expiresAtEpochMillis!! - ticket.joinedAtEpochMillis!!)
        assertEquals(HttpStatusCode.NotFound, players[0].currentGameResponse().status)
    }

    @Test
    fun waiting_expiresAfterTenMinutes() = gameServerTest {
        val player = player("혼자")
        player.join(BASE)
        advanceAndTick(GameRules.MATCH_TIMEOUT_MILLIS - 1)
        assertEquals(MatchmakingStatus.WAITING, player.ticket().status)
        advanceAndTick(1)
        assertEquals(MatchmakingStatus.EXPIRED, player.ticket().status)
    }

    @Test
    fun duplicateJoin_keepsOriginalTicket() = gameServerTest {
        val player = player("중복")
        val first = player.join(BASE).body<MatchmakingTicketDto>()
        advance(5_000)
        val second = player.join(around(5.0, 0.0)).body<MatchmakingTicketDto>()
        assertEquals(first.joinedAtEpochMillis, second.joinedAtEpochMillis)
        assertEquals(first.expiresAtEpochMillis, second.expiresAtEpochMillis)
        assertEquals(1, second.nearbyWaitingCount)
    }

    @Test
    fun cancel_isIdempotent_andCancelledPlayerIsNotMatched() = gameServerTest {
        val players = players(4)
        players.take(3).forEach { it.join(BASE) }
        assertEquals(HttpStatusCode.NoContent, players[0].cancel().status)
        assertEquals(HttpStatusCode.NoContent, players[0].cancel().status)
        assertEquals(MatchmakingStatus.CANCELLED, players[0].ticket().status)

        val ticket = players[3].join(BASE).body<MatchmakingTicketDto>()
        assertEquals(MatchmakingStatus.WAITING, ticket.status, "취소한 사람은 매칭 후보가 아니다")
        assertEquals(3, ticket.nearbyWaitingCount)
    }

    @Test
    fun cancelAfterMatched_isRejected_andJoinWhileInGameIsRejected() = gameServerTest {
        val players = players(4)
        players.forEach { it.join(BASE) }
        val cancel = players[0].cancel()
        assertEquals(HttpStatusCode.Conflict, cancel.status)
        assertEquals("ALREADY_MATCHED", cancel.body<ErrorResponse>().details["reason"])
        val join = players[0].join(BASE)
        assertEquals(HttpStatusCode.Conflict, join.status)
        assertEquals("ALREADY_IN_GAME", join.body<ErrorResponse>().details["reason"])
    }

    @Test
    fun farPlayer_isNotMatched() = gameServerTest {
        val players = players(4)
        players.take(3).forEach { it.join(BASE) }
        val far = players[3].join(around(700.0, 90.0)).body<MatchmakingTicketDto>()
        assertEquals(MatchmakingStatus.WAITING, far.status)
        assertEquals(1, far.nearbyWaitingCount)
    }

    @Test
    fun lowAccuracyOrOldLocation_isRejected() = gameServerTest {
        val player = player("부정확")
        assertEquals(HttpStatusCode.UnprocessableEntity, player.join(BASE, accuracy = 300.0).status)
        assertEquals(MatchmakingStatus.IDLE, player.ticket().status)
    }

    @Test
    fun staleWaiter_isSkipped_untilLocationRefreshes() = gameServerTest {
        val players = players(4)
        players.take(3).forEach { it.join(BASE) }
        advance(deps.config.game.matchLocationMaxAgeMillis + 1_000)
        // 앞의 3명은 위치가 오래되어(앱 종료 등) 후보에서 빠진다.
        assertEquals(MatchmakingStatus.WAITING, players[3].join(BASE).body<MatchmakingTicketDto>().status)
        // 대기자가 새 위치를 보내면 다시 후보가 되어 매칭된다.
        players.take(3).forEach { assertEquals(HttpStatusCode.Created, it.locate(BASE).status) }
        assertEquals(MatchmakingStatus.MATCHED, players[3].ticket().status)
    }

    @Test
    fun concurrentJoins_putEveryUserInExactlyOneGame() = gameServerTest {
        val players = players(8)
        coroutineScope { players.map { p -> async { p.join(around(15.0, players.indexOf(p) * 45.0)) } }.awaitAll() }
        val gameIds = players.map { it.currentGameResponse().body<GameDto>().gameId }
        assertEquals(2, gameIds.toSet().size)
        gameIds.groupingBy { it }.eachCount().values.forEach { assertEquals(4, it) }
        gameIds.toSet().forEach { id ->
            val game = players.first { p -> p.ticket().gameId == id }.game(id)
            assertEquals(4, game.players.map { it.userId }.toSet().size)
        }
    }
}

class MatchFinderTest {
    private val t0 = Instant.parse("2026-11-13T00:00:00Z")
    private fun waiter(meters: Double, bearing: Double, order: Long = 0) = Waiter(UUID.randomUUID(), around(meters, bearing), t0.plusMillis(order))

    @Test
    fun pairwiseDistance_boundaryAt500m() {
        // 일직선 위 0m, 499.9m: 두 끝 사이가 기준. 500m 바로 안쪽은 매칭, 바로 바깥은 불가.
        val inside = listOf(waiter(0.0, 0.0), waiter(249.0, 90.0), waiter(249.0, 270.0), waiter(100.0, 0.0))
        assertNotNull(MatchFinder.findGroup(inside))
        val outside = listOf(waiter(0.0, 0.0), waiter(250.5, 90.0), waiter(250.5, 270.0), waiter(100.0, 0.0))
        assertTrue(GeoMath.distanceMeters(outside[1].point, outside[2].point) > 500.0)
        assertNull(MatchFinder.findGroup(outside))
    }

    @Test
    fun searchesOtherCombinations_whenFirstAnchorFails() {
        // 가장 오래 기다린 사람은 혼자 멀리 있다 → 나머지 4명끼리 매칭된다.
        val far = waiter(2_000.0, 0.0, order = 0)
        val near = (1..4).map { waiter(20.0, it * 90.0, order = it.toLong()) }
        val group = assertNotNull(MatchFinder.findGroup(listOf(far) + near))
        assertEquals(near.map { it.userId }.toSet(), group.map { it.userId }.toSet())
        assertTrue(MatchFinder.isValidGroup(group))
    }

    @Test
    fun findGroups_neverReusesAPlayer() {
        val waiters = (0 until 9).map { waiter(30.0, it * 40.0, order = it.toLong()) }
        val groups = MatchFinder.findGroups(waiters + waiters.first())
        assertEquals(2, groups.size)
        val all = groups.flatten().map { it.userId }
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun oldestWaiters_areMatchedFirst() {
        val waiters = (0 until 5).map { waiter(10.0, it * 72.0, order = it.toLong()) }
        val group = assertNotNull(MatchFinder.findGroup(waiters.shuffled()))
        assertEquals(waiters.take(4).map { it.userId }.toSet(), group.map { it.userId }.toSet())
    }
}
