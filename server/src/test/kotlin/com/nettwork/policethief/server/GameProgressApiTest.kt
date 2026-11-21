package com.nettwork.policethief.server

import com.nettwork.policethief.server.game.GameSettings
import com.nettwork.policethief.shared.dto.ErrorResponse
import com.nettwork.policethief.shared.geo.GeoMath
import com.nettwork.policethief.shared.geo.GeoPoint
import com.nettwork.policethief.shared.model.GameEndReason
import com.nettwork.policethief.shared.model.GameEventType
import com.nettwork.policethief.shared.model.GameResult
import com.nettwork.policethief.shared.model.GameStatus
import com.nettwork.policethief.shared.model.LocationSource
import com.nettwork.policethief.shared.model.MissionStatus
import com.nettwork.policethief.shared.model.PlayerRole
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 구역 이탈·축소, 미션, 과속 판정. */
class GameProgressApiTest {
    private suspend fun GameTestContext.move(player: TestPlayer, point: GeoPoint, accuracy: Double = 5.0, source: LocationSource = LocationSource.GPS) {
        advance(15_000)
        assertEquals(HttpStatusCode.Created, player.locate(point, accuracy, source = source).status)
    }

    @Test
    fun zoneOut_warnsThenDisqualifiesAfterThirtySeconds() = gameServerTest {
        val g = startedGame()
        val thief = g.thieves[0]
        // ±25m 표본은 과속 판정에는 쓰지 않지만(20m 초과) 구역 판정(30m 이하)에는 쓴다.
        move(thief, around(600.0, 0.0, g.center), accuracy = 25.0)
        val game = thief.game(g.gameId)
        assertNotNull(game.player(thief.userId).outOfZoneSinceEpochMillis)
        assertTrue(thief.events(g.gameId).any { it.type == GameEventType.ZONE_OUT_WARNING })
        assertTrue(g.police[0].events(g.gameId).none { it.type == GameEventType.ZONE_OUT_WARNING }, "이탈 경고는 본인에게만")

        advanceAndTick(deps.config.game.zoneOutDisqualifyMillis)
        val ended = thief.game(g.gameId)
        assertEquals(GameStatus.CANCELLED, ended.status)
        assertEquals(GameResult.INVALID, ended.result)
        assertEquals(GameEndReason.PLAYER_DISQUALIFIED_ZONE_OUT, ended.endReason)
    }

    @Test
    fun returningToZone_clearsWarning() = gameServerTest {
        val g = startedGame()
        val thief = g.thieves[0]
        move(thief, around(600.0, 0.0, g.center), accuracy = 25.0)
        move(thief, around(100.0, 0.0, g.center), accuracy = 25.0)
        assertNull(thief.game(g.gameId).player(thief.userId).outOfZoneSinceEpochMillis)
        advanceAndTick(60_000)
        assertEquals(GameStatus.PLAYING, thief.game(g.gameId).status)
    }

    @Test
    fun poorAccuracy_doesNotTriggerZoneOut() = gameServerTest {
        val g = startedGame()
        move(g.thieves[0], around(600.0, 0.0, g.center), accuracy = 80.0)
        assertNull(g.thieves[0].game(g.gameId).player(g.thieves[0].userId).outOfZoneSinceEpochMillis)
    }

    @Test
    // 미션 실패가 축소 일정을 앞당기지 않도록 이 테스트에서는 미션이 생기지 않게 한다(첫 미션을 경기 종료 뒤로).
    fun zoneShrinks_onSchedule_withWarningFirst() = gameServerTest(game = GameSettings(missionFirstDelayMillis = 40 * GameSettings.MINUTE)) {
        val g = startedGame()
        val settings = deps.config.game
        val firstStage = settings.zoneShrinkStages.first()
        val startedAt = g.game.startedAtEpochMillis!!
        assertEquals(startedAt + firstStage.offsetMillis, g.game.nextZoneShrink!!.scheduledAtEpochMillis)

        clock.now = java.time.Instant.ofEpochMilli(startedAt + firstStage.offsetMillis - settings.zoneShrinkWarningMillis)
        tick()
        assertTrue(g.police[0].events(g.gameId).any { it.type == GameEventType.ZONE_SHRINK_WARNING })
        assertEquals(500.0, g.police[0].game(g.gameId).currentRadiusMeters)

        clock.now = java.time.Instant.ofEpochMilli(startedAt + firstStage.offsetMillis)
        tick()
        val game = g.police[0].game(g.gameId)
        assertEquals(firstStage.radiusMeters, game.currentRadiusMeters)
        assertEquals(500.0, game.initialRadiusMeters)
        assertEquals(g.game.centerLatitude, game.centerLatitude, "중심은 유지된다")
        assertTrue(g.thieves[0].events(g.gameId).any { it.type == GameEventType.ZONE_SHRUNK })
    }

    @Test
    fun mission_isVisibleOnlyToThieves_andFailureAdvancesNextShrink() = gameServerTest {
        val g = startedGame()
        val settings = deps.config.game
        val startedAt = g.game.startedAtEpochMillis!!
        clock.now = java.time.Instant.ofEpochMilli(startedAt + settings.missionFirstDelayMillis)
        tick()
        val thiefView = g.thieves[0].game(g.gameId)
        val mission = thiefView.missions.single()
        assertEquals(MissionStatus.ACTIVE, mission.status)
        assertTrue(GeoMath.distanceMeters(GeoPoint(mission.targetLatitude, mission.targetLongitude), g.center) <= thiefView.currentRadiusMeters)
        assertTrue(g.police[0].game(g.gameId).missions.isEmpty(), "경찰에게는 미션이 공개되지 않는다")
        assertTrue(g.police[0].events(g.gameId).none { it.type == GameEventType.MISSION_STARTED })

        clock.now = java.time.Instant.ofEpochMilli(mission.deadlineAtEpochMillis)
        tick()
        val after = g.thieves[0].game(g.gameId)
        assertEquals(MissionStatus.FAILED, after.missions.single().status)
        val original = startedAt + settings.zoneShrinkStages.first().offsetMillis
        val expected = maxOf(original - settings.missionFailureShrinkAdvanceMillis, mission.deadlineAtEpochMillis + settings.zoneShrinkWarningMillis)
        assertEquals(expected, after.nextZoneShrink!!.scheduledAtEpochMillis)
        assertTrue(expected < original)
    }

    @Test
    fun mission_completesWhenActiveThiefReachesTarget() = gameServerTest {
        val g = startedGame()
        clock.now = java.time.Instant.ofEpochMilli(g.game.startedAtEpochMillis!! + deps.config.game.missionFirstDelayMillis)
        tick()
        val mission = g.thieves[0].game(g.gameId).missions.single()
        move(g.thieves[1], GeoPoint(mission.targetLatitude, mission.targetLongitude), accuracy = 25.0)
        val done = g.thieves[0].game(g.gameId).missions.single()
        assertEquals(MissionStatus.COMPLETED, done.status)
        assertEquals(g.thieves[1].userId, done.completedByUserId)
        assertEquals(1, g.thieves[0].result(g.gameId).players.first { it.userId == g.thieves[1].userId }.missionsCompleted)
    }

    @Test
    fun repeatedSpeeding_givesThreeWarnings_thenDisqualifies() = gameServerTest(game = GameSettings(zoneOutDisqualifyMillis = 0)) {
        val g = startedGame()
        val runner = g.police[0]
        val a = around(100.0, 0.0, g.center)
        val b = around(100.0, 180.0, g.center)
        // 15초마다 200m(약 46km/h) 왕복
        var i = 0
        while (runner.game(g.gameId).status == GameStatus.PLAYING && i < 20) {
            move(runner, if (i % 2 == 0) a else b)
            i++
        }
        val game = runner.game(g.gameId)
        assertEquals(GameStatus.CANCELLED, game.status)
        assertEquals(GameEndReason.PLAYER_DISQUALIFIED_SPEEDING, game.endReason)
        assertEquals(3, game.player(runner.userId).warningCount)
        assertEquals(3, runner.events(g.gameId).count { it.type == GameEventType.SPEED_WARNING })
    }

    @Test
    fun singleGpsSpike_orTeleport_doesNotWarn() = gameServerTest {
        val g = startedGame()
        val p = g.police[0]
        move(p, g.center)
        move(p, around(200.0, 0.0, g.center)) // 1회 과속 후보
        move(p, around(210.0, 0.0, g.center)) // 정상 → 후보 초기화
        move(p, around(2_000.0, 90.0, g.center), accuracy = 25.0) // 정확도 낮음 → 판정 제외
        move(p, around(5_000.0, 90.0, g.center)) // 순간 이동(>150km/h) → GPS 오류로 보고 제외
        assertEquals(0, p.game(g.gameId).player(p.userId).warningCount)
    }

    @Test
    fun mockLocations_areExemptFromSpeed_onlyOnDevServer() = gameServerTest(allowMockLocations = true) {
        val g = startedGame()
        val p = g.police[0]
        repeat(10) { move(p, if (it % 2 == 0) around(100.0, 0.0, g.center) else around(100.0, 180.0, g.center), source = LocationSource.MOCK) }
        assertEquals(0, p.game(g.gameId).player(p.userId).warningCount)
        assertEquals(GameStatus.PLAYING, p.game(g.gameId).status)
    }

    @Test
    fun outOfOrderAndDuplicateSamples_doNotChangeState() = gameServerTest {
        val g = startedGame()
        val thief = g.thieves[0]
        move(thief, g.center)
        val measuredAt = clock.millis()
        // 같은 측정 시각(중복)·더 이른 측정 시각(순서 역전)의 이탈 좌표는 판정에 쓰지 않는다.
        thief.locate(around(600.0, 0.0, g.center), measuredAt = measuredAt)
        thief.locate(around(600.0, 0.0, g.center), measuredAt = measuredAt - 5_000)
        assertNull(thief.game(g.gameId).player(thief.userId).outOfZoneSinceEpochMillis)
    }
}

/** 역할별 위치 공개, 다른 경기 접근, 종료 후 차단. */
class GameSecurityApiTest {
    @Test
    fun thieves_neverReceivePoliceLocations() = gameServerTest {
        val g = startedGame()
        g.all.forEachIndexed { i, p ->
            advance(1_000)
            p.locate(around(30.0, i * 90.0, g.center))
        }
        val policeIds = g.police.map { it.userId }.toSet()
        g.thieves.forEach { thief ->
            val visible = thief.visible(g.gameId).locations.map { it.userId }.toSet()
            assertEquals(g.thieves.map { it.userId }.toSet(), visible)
            val state = thief.state(g.gameId)
            assertTrue(state.locations.none { it.userId in policeIds }, "재접속 상태 응답도 같은 규칙")
        }
        g.police.forEach { police ->
            assertEquals(g.all.map { it.userId }.toSet(), police.visible(g.gameId).locations.map { it.userId }.toSet())
        }
    }

    @Test
    fun nonParticipant_cannotReadOrChangeOtherGame() = gameServerTest {
        val g = startedGame()
        val outsider = player("외부인")
        assertEquals(HttpStatusCode.Forbidden, outsider.gameResponse(g.gameId).status)
        assertEquals(HttpStatusCode.Forbidden, outsider.visibleResponse(g.gameId).status)
        assertEquals(HttpStatusCode.Forbidden, outsider.myQrResponse(g.gameId).status)
        assertEquals(HttpStatusCode.Forbidden, outsider.captureResponse(g.gameId, "x").status)
        assertEquals(HttpStatusCode.NotFound, outsider.gameResponse(java.util.UUID.randomUUID().toString()).status)
        assertEquals(HttpStatusCode.UnprocessableEntity, outsider.gameResponse("not-a-uuid").status)
    }

    @Test
    fun gameApis_requireValidToken() = gameServerTest {
        val g = startedGame()
        assertEquals(HttpStatusCode.Unauthorized, client.get(com.nettwork.policethief.shared.api.ApiPaths.game(g.gameId)).status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get(com.nettwork.policethief.shared.api.ApiPaths.gameMyQr(g.gameId)) { bearerAuth("forged") }.status,
        )
    }

    @Test
    fun qrTokens_areNeverInGameStateOrEvents() = gameServerTest {
        val g = startedGame()
        val tokens = g.thieves.map { it.myQr(g.gameId).token }
        g.all.forEach { p ->
            val raw = client.get(com.nettwork.policethief.shared.api.ApiPaths.gameState(g.gameId)) { bearerAuth(p.token) }.body<String>()
            tokens.forEach { assertTrue(it !in raw, "상태 응답에 QR 토큰이 들어가면 안 된다") }
        }
        // 각 도둑은 자기 QR 만 받는다.
        assertEquals(g.thieves[0].userId, g.thieves[0].myQr(g.gameId).userId)
        assertTrue(tokens[0] != tokens[1])
    }

    @Test
    fun afterGameEnds_locationsAndQrAreBlocked() = gameServerTest {
        val g = startedGame()
        g.all.forEach { it.locate(g.center) }
        g.police[0].forfeit(g.gameId)
        val blocked = g.police[1].visibleResponse(g.gameId)
        assertEquals(HttpStatusCode.Conflict, blocked.status)
        assertEquals("GAME_ENDED", blocked.body<ErrorResponse>().details["reason"])
        assertTrue(g.police[1].state(g.gameId).locations.isEmpty())
        assertEquals(HttpStatusCode.Conflict, g.thieves[0].myQrResponse(g.gameId).status)
        // 결과는 계속 볼 수 있다(누적 점수·랭킹 없음).
        assertEquals(GameResult.INVALID, g.thieves[0].result(g.gameId).result)
    }

    @Test
    fun eventsAreFilteredByRole_andIncremental() = gameServerTest {
        val g = startedGame()
        val all = g.police[0].events(g.gameId)
        val lastId = all.maxOf { it.eventId }
        assertTrue(all.map { it.eventId }.zipWithNext().all { (a, b) -> a < b }, "이벤트 ID 는 증가")
        g.police[0].capture(g.gameId, g.thieves[0].myQr(g.gameId).token)
        val newer = g.thieves[1].events(g.gameId, after = lastId)
        assertEquals(listOf(GameEventType.THIEF_ARRESTED), newer.map { it.type })
        assertTrue(newer.single().gameVersion > all.maxOf { it.gameVersion })
        assertEquals(PlayerRole.THIEF, g.thieves[1].game(g.gameId).player(g.thieves[1].userId).role)
    }
}
