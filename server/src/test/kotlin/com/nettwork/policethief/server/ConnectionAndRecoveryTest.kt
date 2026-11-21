package com.nettwork.policethief.server

import com.nettwork.policethief.shared.api.ApiJson
import com.nettwork.policethief.shared.api.ApiPaths
import com.nettwork.policethief.shared.dto.DevBotScanRequest
import com.nettwork.policethief.shared.dto.DevBotsResponse
import com.nettwork.policethief.shared.dto.DevClockAdvanceRequest
import com.nettwork.policethief.shared.dto.DevCreateBotsRequest
import com.nettwork.policethief.shared.dto.DevStatusDto
import com.nettwork.policethief.shared.dto.GameDto
import com.nettwork.policethief.shared.dto.MatchmakingTicketDto
import com.nettwork.policethief.shared.dto.QrScanResultDto
import com.nettwork.policethief.shared.dto.ServerMessage
import com.nettwork.policethief.shared.model.GameEndReason
import com.nettwork.policethief.shared.model.GameEventType
import com.nettwork.policethief.shared.model.GameResult
import com.nettwork.policethief.shared.model.GameStatus
import com.nettwork.policethief.shared.model.LocationSource
import com.nettwork.policethief.shared.model.MatchmakingStatus
import com.nettwork.policethief.shared.model.PlayerConnectionState
import com.nettwork.policethief.shared.model.PlayerRole
import com.nettwork.policethief.shared.rules.GameRules
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConnectionAndRecoveryTest {
    private suspend fun DefaultClientWebSocketSession.receiveMessage(): ServerMessage {
        val frame = withTimeout(5_000) { incoming.receive() }
        assertIs<Frame.Text>(frame)
        return ApiJson.decodeFromString(ServerMessage.serializer(), frame.readText())
    }

    /** 서버의 연결 끊김 처리는 비동기(소켓 종료 후)라 짧게 기다리며 확인한다. */
    private suspend fun awaitCondition(check: suspend () -> Boolean) = withTimeout(5_000) {
        while (!check()) delay(20)
    }

    @Test
    fun disconnect_startsGrace_reconnectRestores_andTimeoutInvalidates() = gameServerTest {
        val g = startedGame()
        val p = g.thieves[0]
        val engine = deps.games

        engine.onUserDisconnected(p.uuid)
        var me = p.game(g.gameId).player(p.userId)
        assertEquals(PlayerConnectionState.RECONNECTING, me.connectionState)
        assertTrue(g.police[0].events(g.gameId).any { it.type == GameEventType.PLAYER_DISCONNECTED })

        advanceAndTick(GameRules.RECONNECT_GRACE_MILLIS - 1)
        engine.onUserConnected(p.uuid)
        me = p.game(g.gameId).player(p.userId)
        assertEquals(PlayerConnectionState.CONNECTED, me.connectionState)
        assertEquals(GameStatus.PLAYING, p.game(g.gameId).status)

        engine.onUserDisconnected(p.uuid)
        advanceAndTick(GameRules.RECONNECT_GRACE_MILLIS)
        val ended = g.police[0].game(g.gameId)
        assertEquals(GameStatus.CANCELLED, ended.status)
        assertEquals(GameEndReason.PLAYER_DISCONNECT_TIMEOUT, ended.endReason)
        assertEquals(PlayerConnectionState.LEFT, ended.player(p.userId).connectionState)
    }

    @Test
    fun disconnectDuringPreparation_alsoGetsGrace() = gameServerTest {
        val players = players(4)
        players.forEach { it.join(BASE) }
        val gameId = players[0].currentGameResponse().body<GameDto>().gameId
        deps.games.onUserDisconnected(players[1].uuid)
        advanceAndTick(GameRules.PREPARATION_MILLIS)
        assertEquals(GameStatus.PLAYING, players[0].game(gameId).status, "준비 중 끊김만으로 경기를 취소하지 않는다")
        advanceAndTick(GameRules.RECONNECT_GRACE_MILLIS)
        assertEquals(GameEndReason.PLAYER_DISCONNECT_TIMEOUT, players[0].game(gameId).endReason)
    }

    @Test
    fun webSocket_welcomeCarriesActiveGame_andEventsArePushed_andCloseMarksReconnecting() = gameServerTest {
        val g = startedGame()
        val thief = g.thieves[0]
        client.webSocket(ApiPaths.WEBSOCKET, request = { bearerAuth(thief.token) }) {
            val welcome = assertIs<ServerMessage.Welcome>(receiveMessage())
            assertEquals(g.gameId, welcome.activeGameId)

            g.police[0].capture(g.gameId, g.thieves[1].myQr(g.gameId).token)
            val push = assertIs<ServerMessage.GameEventPush>(receiveMessage())
            assertEquals(GameEventType.THIEF_ARRESTED, push.event.type)
        }
        awaitCondition { thief.game(g.gameId).player(thief.userId).connectionState == PlayerConnectionState.RECONNECTING }

        // 재접속해도 첫 메시지는 welcome 이어야 한다(예전에는 PLAYER_RECONNECTED 가 먼저 왔다).
        client.webSocket(ApiPaths.WEBSOCKET, request = { bearerAuth(thief.token) }) {
            val again = assertIs<ServerMessage.Welcome>(receiveMessage())
            assertEquals(g.gameId, again.activeGameId)
            val reconnected = assertIs<ServerMessage.GameEventPush>(receiveMessage())
            assertEquals(GameEventType.PLAYER_RECONNECTED, reconnected.event.type)
        }
    }

    @Test
    fun webSocket_thiefDoesNotReceivePoliceLocation() = gameServerTest {
        val g = startedGame()
        val thief = g.thieves[0]
        client.webSocket(ApiPaths.WEBSOCKET, request = { bearerAuth(thief.token) }) {
            assertIs<ServerMessage.Welcome>(receiveMessage())
            advance(15_000)
            g.police[0].locate(g.center)
            advance(15_000)
            g.thieves[1].locate(g.center)
            // 경찰 위치는 오지 않고, 다음에 온 메시지가 동료 도둑의 위치여야 한다.
            val location = assertIs<ServerMessage.PlayerLocation>(receiveMessage())
            assertEquals(g.thieves[1].userId, location.location.userId)
        }
    }

    @Test
    fun secondConnection_replacesFirst() = gameServerTest {
        val p = player("중복접속")
        client.webSocket(ApiPaths.WEBSOCKET, request = { bearerAuth(p.token) }) {
            assertIs<ServerMessage.Welcome>(receiveMessage())
            val first = this
            client.webSocket(ApiPaths.WEBSOCKET, request = { bearerAuth(p.token) }) {
                assertIs<ServerMessage.Welcome>(receiveMessage())
                assertIs<ServerMessage.SessionReplaced>(first.receiveMessage())
            }
        }
    }

    @Test
    fun serverRestart_recoversGame_andAppliesMissedDeadlines() = gameServerTest {
        val g = startedGame()
        g.police[0].capture(g.gameId, g.thieves[0].myQr(g.gameId).token)

        // 같은 DB 로 서버 객체를 새로 만든다(= 재시작). 메모리 상태는 하나도 이어받지 않는다.
        val restarted = ServerDependencies.create(deps.config, db, clock)
        clock.now = java.time.Instant.ofEpochMilli(g.game.endsAtEpochMillis + 60_000)
        restarted.games.recoverAfterRestart()

        val game = restarted.games.getGame(g.police[0].uuid, UUID.fromString(g.gameId))
        assertEquals(GameStatus.FINISHED, game.status)
        assertEquals(GameResult.THIEF_WIN, game.result)
        assertEquals(g.game.endsAtEpochMillis, game.endedAtEpochMillis, "꺼져 있던 동안 지난 종료 시각 기준으로 확정")
        assertEquals(g.police[0].userId, game.player(g.thieves[0].userId).capturedBy)
    }

    @Test
    fun serverRestart_startsGraceForPlayersNotYetReconnected() = gameServerTest {
        val g = startedGame()
        val restarted = ServerDependencies.create(deps.config, db, clock)
        restarted.games.recoverAfterRestart()
        val game = restarted.games.getGame(g.police[0].uuid, UUID.fromString(g.gameId))
        assertEquals(GameStatus.PLAYING, game.status)
        assertTrue(game.players.all { it.connectionState == PlayerConnectionState.RECONNECTING })
        g.all.forEach { restarted.games.onUserConnected(it.uuid) }
        advance(GameRules.RECONNECT_GRACE_MILLIS + 1)
        restarted.games.tick()
        assertEquals(GameStatus.PLAYING, restarted.games.getGame(g.police[0].uuid, UUID.fromString(g.gameId)).status)
    }

    @Test
    fun appRestart_currentGameReturnsSameGame() = gameServerTest {
        val g = startedGame()
        // 앱을 다시 켜면 토큰으로 진행 중 경기를 찾는다(홈이 아니라 경기 화면 복귀).
        val current = g.thieves[1].currentGameResponse().body<GameDto>()
        assertEquals(g.gameId, current.gameId)
        assertEquals(MatchmakingStatus.MATCHED, g.thieves[1].ticket().status)
        g.thieves[1].forfeit(g.gameId)
        assertEquals(HttpStatusCode.NotFound, g.thieves[1].currentGameResponse().status)
        assertEquals(MatchmakingStatus.IDLE, g.thieves[1].ticket().status, "끝난 경기로 돌아가지 않는다")
    }
}

class DevToolsApiTest {
    @Test
    fun devApis_doNotExist_whenDisabled() = gameServerTest {
        val p = player("일반")
        assertEquals(HttpStatusCode.NotFound, client.get(ApiPaths.DEV_STATUS) { bearerAuth(p.token) }.status)
    }

    @Test
    fun onePhoneWithThreeBots_canPlayToTheEnd() = gameServerTest(devToolsEnabled = true) {
        val me = player("개발자")
        assertEquals(HttpStatusCode.Created, me.join(BASE, source = LocationSource.MOCK).status)
        val created = client.post(ApiPaths.DEV_BOTS) {
            bearerAuth(me.token)
            contentType(ContentType.Application.Json)
            setBody(DevCreateBotsRequest(count = 3, latitude = BASE.latitude, longitude = BASE.longitude))
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val ticket = me.ticket()
        assertEquals(MatchmakingStatus.MATCHED, ticket.status)
        val gameId = ticket.gameId!!

        val bots = client.get(ApiPaths.devGameBots(gameId)) { bearerAuth(me.token) }.body<DevBotsResponse>().bots
        assertEquals(3, bots.size)
        // 봇 QR 만 내려주고 사람의 QR 은 내려주지 않는다.
        assertTrue(bots.filter { it.role == PlayerRole.POLICE }.all { it.qrToken == null })

        // 서버 시간 이동: 준비 30초를 건너뛴다.
        val status = client.post(ApiPaths.DEV_CLOCK_ADVANCE) {
            bearerAuth(me.token)
            contentType(ContentType.Application.Json)
            setBody(DevClockAdvanceRequest(GameRules.PREPARATION_MILLIS))
        }.body<DevStatusDto>()
        assertEquals(GameRules.PREPARATION_MILLIS, status.clockOffsetMillis)
        assertEquals(GameStatus.PLAYING, me.game(gameId).status)

        // 봇 3명 중에는 경찰과 도둑이 최소 1명씩 있다. 경찰 봇이 도둑 봇을 체포(일반 판정 코드와 동일).
        val policeBot = bots.first { it.role == PlayerRole.POLICE }
        val thiefBot = bots.first { it.role == PlayerRole.THIEF }
        val scan = client.post(ApiPaths.devBotScan(policeBot.userId)) {
            bearerAuth(me.token)
            contentType(ContentType.Application.Json)
            setBody(DevBotScanRequest(thiefBot.qrToken!!))
        }.body<QrScanResultDto>()
        assertTrue(scan.accepted)

        // 30분을 기다리지 않고 종료 판정: 도둑 1명이 남아 있으므로 도둑 승리.
        client.post(ApiPaths.DEV_CLOCK_ADVANCE) {
            bearerAuth(me.token)
            contentType(ContentType.Application.Json)
            setBody(DevClockAdvanceRequest(GameRules.GAME_DURATION_MILLIS))
        }
        val ended = me.game(gameId)
        assertEquals(GameStatus.FINISHED, ended.status)
        assertEquals(GameResult.THIEF_WIN, ended.result)
    }

    @Test
    fun botsCannotUseDevTools_andHumansCannotBeMovedAsBots() = gameServerTest(devToolsEnabled = true) {
        val me = player("개발자")
        val other = player("다른사람")
        val response = client.post(ApiPaths.devBotScan(other.userId)) {
            bearerAuth(me.token)
            contentType(ContentType.Application.Json)
            setBody(DevBotScanRequest("x"))
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
        val ticket: MatchmakingTicketDto = other.ticket()
        assertEquals(MatchmakingStatus.IDLE, ticket.status)
    }
}
