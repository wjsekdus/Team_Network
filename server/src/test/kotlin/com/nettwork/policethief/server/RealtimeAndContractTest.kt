package com.nettwork.policethief.server

import com.nettwork.policethief.shared.api.ApiJson
import com.nettwork.policethief.shared.api.ApiPaths
import com.nettwork.policethief.shared.dto.ClientMessage
import com.nettwork.policethief.shared.dto.ErrorCode
import com.nettwork.policethief.shared.dto.ErrorResponse
import com.nettwork.policethief.shared.dto.ServerMessage
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RealtimeApiTest {
    private suspend fun DefaultClientWebSocketSession.receiveMessage(): ServerMessage {
        val frame = incoming.receive()
        assertIs<Frame.Text>(frame)
        return ApiJson.decodeFromString(ServerMessage.serializer(), frame.readText())
    }

    private suspend fun DefaultClientWebSocketSession.sendMessage(message: ClientMessage) =
        send(Frame.Text(ApiJson.encodeToString(ClientMessage.serializer(), message)))

    @Test
    fun authenticatedClient_receivesWelcome_andTestEvent() = serverTest { client ->
        val login = client.guestLogin()
        client.webSocket(ApiPaths.WEBSOCKET, request = { bearerAuth(login.accessToken) }) {
            val welcome = receiveMessage()
            assertIs<ServerMessage.Welcome>(welcome)
            assertEquals(login.user.userId, welcome.userId)

            sendMessage(ClientMessage.RequestTestEvent("hello"))
            val event = receiveMessage()
            assertIs<ServerMessage.TestEvent>(event)
            assertTrue(event.message.contains("hello"))

            sendMessage(ClientMessage.Ping(clientTimeEpochMillis = 42))
            val pong = receiveMessage()
            assertIs<ServerMessage.Pong>(pong)
            assertEquals(42, pong.clientTimeEpochMillis)
        }
    }

    @Test
    fun malformedMessage_returnsErrorWithoutClosing() = serverTest { client ->
        val login = client.guestLogin()
        client.webSocket(ApiPaths.WEBSOCKET, request = { bearerAuth(login.accessToken) }) {
            receiveMessage() // welcome
            send(Frame.Text("""{"type":"unknown"}"""))
            val error = receiveMessage()
            assertIs<ServerMessage.Error>(error)
            sendMessage(ClientMessage.Ping(1))
            assertIs<ServerMessage.Pong>(receiveMessage())
        }
    }

    @Test
    fun unauthenticatedHandshake_isRejected() = serverTest { client ->
        assertFails {
            client.webSocket(ApiPaths.WEBSOCKET) { receiveMessage() }
        }
    }
}

class NotImplementedContractTest {
    @Test
    fun futureGameApis_return501_insteadOfFakeSuccess() = serverTest { client ->
        val token = client.guestLogin().accessToken
        val response = client.get(ApiPaths.GAMES_CURRENT) { bearerAuth(token) }
        assertEquals(HttpStatusCode.NotImplemented, response.status)
        assertEquals(ErrorCode.NOT_IMPLEMENTED, response.body<ErrorResponse>().code)
    }

    @Test
    fun futureGameApis_stillRequireAuthentication() = serverTest { client ->
        assertEquals(HttpStatusCode.Unauthorized, client.get(ApiPaths.GAMES_CURRENT).status)
    }
}
