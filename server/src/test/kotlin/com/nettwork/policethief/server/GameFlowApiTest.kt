package com.nettwork.policethief.server

import com.nettwork.policethief.shared.dto.GameDto
import com.nettwork.policethief.shared.geo.GeoPoint
import com.nettwork.policethief.shared.model.GameEndReason
import com.nettwork.policethief.shared.model.GameEventType
import com.nettwork.policethief.shared.model.GameResult
import com.nettwork.policethief.shared.model.GameStatus
import com.nettwork.policethief.shared.model.PlayerStatus
import com.nettwork.policethief.shared.model.ScanRejectReason
import com.nettwork.policethief.shared.rules.GameRules
import io.ktor.client.call.body
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 준비 → 시작 → 체포 → 호송 → 수감 → 구출 → 승패/무효까지 서버 판정을 REST 로 검증한다. */
class GameFlowApiTest {
    /** 위치 판정은 측정 시각이 앞선 표본을 무시하므로, 보낼 때마다 시간을 조금 진행한다. */
    private suspend fun GameTestContext.move(player: TestPlayer, point: GeoPoint, accuracy: Double = 5.0) {
        advance(15_000)
        player.locate(point, accuracy)
    }

    @Test
    fun preparation_lastsThirtySeconds_andActionsAreRejectedBeforeStart() = gameServerTest {
        val players = players(4)
        players.forEach { it.join(BASE) }
        val game = players[0].currentGameResponse().body<GameDto>()
        val thief = players.first { p -> game.player(p.userId).role == com.nettwork.policethief.shared.model.PlayerRole.THIEF }
        val police = players.first { p -> game.player(p.userId).role == com.nettwork.policethief.shared.model.PlayerRole.POLICE }
        val qr = thief.myQr(game.gameId).token

        advanceAndTick(GameRules.PREPARATION_MILLIS - 1)
        assertEquals(GameStatus.READY, police.game(game.gameId).status)
        assertEquals(ScanRejectReason.GAME_NOT_PLAYING, police.capture(game.gameId, qr).rejectReason)

        advanceAndTick(1)
        val started = police.game(game.gameId)
        assertEquals(GameStatus.PLAYING, started.status)
        assertEquals(started.startsAtEpochMillis, started.startedAtEpochMillis)
        assertEquals(1, police.events(game.gameId).count { it.type == GameEventType.GAME_STARTED }, "시작은 한 번만 기록된다")
    }

    @Test
    fun fullGame_captureEscortJailRescue_thenPoliceWin() = gameServerTest {
        val g = startedGame()
        val (p1, p2) = g.police
        val (t1, t2) = g.thieves
        val t1Qr = t1.myQr(g.gameId).token
        val t2Qr = t2.myQr(g.gameId).token

        // 체포 → 호송
        val arrest = p1.capture(g.gameId, t1Qr)
        assertTrue(arrest.accepted)
        assertEquals(PlayerStatus.ESCORTED, arrest.targetStatus)
        var game = p1.game(g.gameId)
        assertEquals(t1.userId, game.player(p1.userId).escortingUserId)
        assertEquals(p1.userId, game.player(t1.userId).capturedBy)

        // 호송 중 구출 불가
        assertEquals(ScanRejectReason.TARGET_ESCORTED, t2.rescue(g.gameId, t1Qr).rejectReason)
        // 같은 경찰이 두 번째 도둑 동시 호송 불가
        assertEquals(ScanRejectReason.ESCORT_LIMIT, p1.capture(g.gameId, t2Qr).rejectReason)
        // 이미 체포된 도둑 재체포 불가
        assertEquals(ScanRejectReason.ALREADY_CAPTURED, p2.capture(g.gameId, t1Qr).rejectReason)

        // 감옥(구역 중심) 10m 안으로 둘 다 들어오면 자동 수감, 경찰 호송 해제
        move(p1, g.center)
        assertEquals(PlayerStatus.ESCORTED, p1.game(g.gameId).player(t1.userId).status, "경찰만 들어오면 수감되지 않는다")
        move(t1, g.center)
        game = p1.game(g.gameId)
        assertEquals(PlayerStatus.JAILED, game.player(t1.userId).status)
        assertNull(game.player(p1.userId).escortingUserId)
        // 같은 위치가 다시 와도 수감 기록은 한 번만
        move(t1, g.center)
        assertEquals(1, p1.events(g.gameId).count { it.type == GameEventType.THIEF_JAILED })

        // 수감된 도둑은 구출자가 될 수 없고, 활동 중 도둑이 구출한다.
        assertEquals(ScanRejectReason.RESCUER_NOT_ACTIVE, t1.rescue(g.gameId, t2Qr).rejectReason)
        val rescued = t2.rescue(g.gameId, t1Qr)
        assertTrue(rescued.accepted)
        assertEquals(PlayerStatus.ACTIVE, rescued.targetStatus)
        assertEquals(ScanRejectReason.TARGET_NOT_JAILED, t2.rescue(g.gameId, t1Qr).rejectReason, "이미 구출된 도둑")
        assertTrue(p1.events(g.gameId).any { it.type == GameEventType.THIEF_RESCUED }, "경찰도 구출 알림을 받는다")

        // 두 도둑 모두 체포 상태(호송 중 포함)가 되면 즉시 경찰 승리
        assertTrue(p1.capture(g.gameId, t1Qr).accepted)
        val last = p2.capture(g.gameId, t2Qr)
        assertTrue(last.accepted)
        assertEquals(GameStatus.FINISHED, last.gameStatus)
        assertEquals(GameResult.POLICE_WIN, last.gameResult)
        val result = t1.result(g.gameId)
        assertEquals(GameEndReason.ALL_THIEVES_CAPTURED, result.endReason)
        assertEquals(2, result.players.first { it.userId == p1.userId }.capturesMade)
        assertEquals(1, result.players.first { it.userId == t2.userId }.rescuesMade)

        // 종료 후 체포·구출·기권은 결과를 바꾸지 못한다.
        assertEquals(ScanRejectReason.GAME_NOT_PLAYING, p1.capture(g.gameId, t1Qr).rejectReason)
        assertEquals(GameResult.POLICE_WIN, t1.forfeit(g.gameId).result)
        assertEquals(1, p1.events(g.gameId).count { it.type == GameEventType.GAME_FINISHED })
    }

    @Test
    fun invalidScans_areRejectedWithReasons() = gameServerTest {
        val g = startedGame("가")
        val other = startedGame("나")
        val (p1, p2) = g.police
        val (t1, t2) = g.thieves
        val t1Qr = t1.myQr(g.gameId).token

        assertEquals(ScanRejectReason.INVALID_TOKEN, p1.capture(g.gameId, "not-a-real-token").rejectReason)
        assertEquals(ScanRejectReason.OTHER_GAME, p1.capture(g.gameId, other.thieves[0].myQr(other.gameId).token).rejectReason)
        assertEquals(ScanRejectReason.NOT_ALLOWED, t2.capture(g.gameId, t1Qr).rejectReason, "도둑은 체포할 수 없다")
        assertEquals(ScanRejectReason.NOT_ALLOWED, p2.rescue(g.gameId, t1Qr).rejectReason, "경찰은 구출할 수 없다")
        assertEquals(ScanRejectReason.SELF_SCAN, t1.rescue(g.gameId, t1Qr).rejectReason)
        assertEquals(ScanRejectReason.TARGET_NOT_JAILED, t2.rescue(g.gameId, t1Qr).rejectReason)
        // 경찰에게는 QR 이 없다 → 경찰이 경찰을 체포하는 경우 자체가 생기지 않는다.
        assertEquals(io.ktor.http.HttpStatusCode.Forbidden, p2.myQrResponse(g.gameId).status)
    }

    @Test
    fun sameRequestId_isJudgedOnlyOnce() = gameServerTest {
        val g = startedGame()
        val (p1, p2) = g.police
        val qr = g.thieves[0].myQr(g.gameId).token
        val first = p1.capture(g.gameId, qr, requestId = "scan-1")
        val retry = p1.capture(g.gameId, qr, requestId = "scan-1")
        assertEquals(first, retry)
        assertEquals(1, p2.events(g.gameId).count { it.type == GameEventType.THIEF_ARRESTED })
    }

    @Test
    fun simultaneousCaptures_ofSameThief_onlyOneSucceeds() = gameServerTest {
        val g = startedGame()
        val qr = g.thieves[0].myQr(g.gameId).token
        val results = coroutineScope { g.police.map { p -> async { p.capture(g.gameId, qr) } }.awaitAll() }
        assertEquals(1, results.count { it.accepted })
        assertEquals(ScanRejectReason.ALREADY_CAPTURED, results.first { !it.accepted }.rejectReason)
    }

    @Test
    fun timeOver_thievesWin_andLateCaptureIsRejected() = gameServerTest {
        val g = startedGame()
        val (p1, p2) = g.police
        assertTrue(p1.capture(g.gameId, g.thieves[0].myQr(g.gameId).token).accepted)
        val lastQr = g.thieves[1].myQr(g.gameId).token

        // 경계 정책: 종료 시각과 같거나 늦게 도착한 체포는 시간 종료가 먼저 확정된다(GAME_LOGIC.md).
        clock.now = java.time.Instant.ofEpochMilli(g.game.endsAtEpochMillis)
        val late = p2.capture(g.gameId, lastQr)
        assertFalse(late.accepted)
        assertEquals(ScanRejectReason.GAME_NOT_PLAYING, late.rejectReason)
        val game = p2.game(g.gameId)
        assertEquals(GameStatus.FINISHED, game.status)
        assertEquals(GameResult.THIEF_WIN, game.result)
        assertEquals(GameEndReason.TIME_OVER_THIEVES_SURVIVED, game.endReason)
        assertEquals(g.game.endsAtEpochMillis, game.endedAtEpochMillis)
    }

    @Test
    fun captureJustBeforeTimeOver_winsForPolice() = gameServerTest {
        val g = startedGame()
        val (p1, p2) = g.police
        assertTrue(p1.capture(g.gameId, g.thieves[0].myQr(g.gameId).token).accepted)
        val lastQr = g.thieves[1].myQr(g.gameId).token
        clock.now = java.time.Instant.ofEpochMilli(g.game.endsAtEpochMillis - 1)
        assertEquals(GameResult.POLICE_WIN, p2.capture(g.gameId, lastQr).gameResult)
        advanceAndTick(60_000)
        assertEquals(GameResult.POLICE_WIN, p2.game(g.gameId).result, "확정된 결과는 시간 종료로 바뀌지 않는다")
    }

    @Test
    fun forfeit_invalidatesGameImmediately() = gameServerTest {
        val g = startedGame()
        val game = g.thieves[0].forfeit(g.gameId)
        assertEquals(GameStatus.CANCELLED, game.status)
        assertEquals(GameResult.INVALID, game.result)
        assertEquals(GameEndReason.PLAYER_FORFEITED, game.endReason)
        assertTrue(g.police[0].events(g.gameId).any { it.type == GameEventType.GAME_INVALIDATED })
        assertEquals(ScanRejectReason.GAME_NOT_PLAYING, g.police[0].capture(g.gameId, "x").rejectReason)
        // 경기가 끝났으므로 다시 매칭할 수 있다.
        assertEquals(io.ktor.http.HttpStatusCode.Created, g.thieves[0].join(BASE).status)
    }

    @Test
    fun jail_boundaryAndAccuracy() = gameServerTest {
        val g = startedGame()
        val (p1, _) = g.police
        val (t1, _) = g.thieves
        assertTrue(p1.capture(g.gameId, t1.myQr(g.gameId).token).accepted)

        // 10.5m: 감옥 밖
        move(p1, around(9.0, 0.0, g.center))
        move(t1, around(10.5, 180.0, g.center))
        assertEquals(PlayerStatus.ESCORTED, p1.game(g.gameId).player(t1.userId).status)

        // 감옥 안이지만 GPS 정확도가 나쁨(±25m) → 보류
        move(t1, around(9.5, 180.0, g.center), accuracy = 25.0)
        assertEquals(PlayerStatus.ESCORTED, p1.game(g.gameId).player(t1.userId).status)

        // 정확도 회복 + 9.5m → 수감(경찰 위치도 최신이어야 하므로 다시 보낸다)
        move(p1, around(9.0, 0.0, g.center))
        move(t1, around(9.5, 180.0, g.center))
        assertEquals(PlayerStatus.JAILED, p1.game(g.gameId).player(t1.userId).status)
    }

    @Test
    fun jail_requiresFreshPoliceSample() = gameServerTest {
        val g = startedGame()
        val (p1, _) = g.police
        val (t1, _) = g.thieves
        assertTrue(p1.capture(g.gameId, t1.myQr(g.gameId).token).accepted)
        move(p1, g.center)
        advance(deps.config.game.jailSampleMaxAgeMillis)
        // 경찰의 마지막 위치가 오래되어(30초 초과) 도둑만 들어와도 확정하지 않는다.
        move(t1, g.center)
        assertEquals(PlayerStatus.ESCORTED, p1.game(g.gameId).player(t1.userId).status)
        move(p1, g.center)
        assertEquals(PlayerStatus.JAILED, p1.game(g.gameId).player(t1.userId).status)
    }
}
