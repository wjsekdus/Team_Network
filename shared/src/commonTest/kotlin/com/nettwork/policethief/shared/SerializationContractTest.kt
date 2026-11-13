package com.nettwork.policethief.shared

import com.nettwork.policethief.shared.api.ApiJson
import com.nettwork.policethief.shared.dto.ClientMessage
import com.nettwork.policethief.shared.dto.ErrorCode
import com.nettwork.policethief.shared.dto.ErrorResponse
import com.nettwork.policethief.shared.dto.GameDto
import com.nettwork.policethief.shared.dto.GamePlayerDto
import com.nettwork.policethief.shared.dto.GuestLoginResponse
import com.nettwork.policethief.shared.dto.LocationUpdateRequest
import com.nettwork.policethief.shared.dto.ServerMessage
import com.nettwork.policethief.shared.dto.UserDto
import com.nettwork.policethief.shared.model.GameStatus
import com.nettwork.policethief.shared.model.LocationSource
import com.nettwork.policethief.shared.model.PlayerRole
import com.nettwork.policethief.shared.model.PlayerStatus
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/** 서버와 클라이언트가 주고받는 JSON 모양이 문서(docs/api/API.md)와 일치하는지 고정하는 테스트. */
class SerializationContractTest {

    @Test
    fun guestLoginResponse_usesDocumentedFieldNames() {
        val response = GuestLoginResponse(
            accessToken = "token-1",
            user = UserDto("u-1", "철수", createdAtEpochMillis = 1L, lastActiveAtEpochMillis = 2L),
        )
        val json = ApiJson.parseToJsonElement(ApiJson.encodeToString(GuestLoginResponse.serializer(), response)).jsonObject

        assertEquals("token-1", json["accessToken"]!!.jsonPrimitive.content)
        assertEquals("Bearer", json["tokenType"]!!.jsonPrimitive.content)
        assertEquals("u-1", json["user"]!!.jsonObject["userId"]!!.jsonPrimitive.content)
    }

    @Test
    fun locationRequest_defaultsSourceToGps_whenFieldMissing() {
        val parsed = ApiJson.decodeFromString(
            LocationUpdateRequest.serializer(),
            """{"latitude":37.5,"longitude":127.0,"accuracyMeters":5.0,"measuredAtEpochMillis":10}""",
        )
        assertEquals(LocationSource.GPS, parsed.source)
    }

    @Test
    fun unknownFields_areIgnored_forForwardCompatibility() {
        val parsed = ApiJson.decodeFromString(
            ErrorResponse.serializer(),
            """{"code":"NOT_FOUND","message":"없음","newField":123}""",
        )
        assertEquals(ErrorCode.NOT_FOUND, parsed.code)
    }

    @Test
    fun serverMessages_useTypeDiscriminator() {
        val encoded = ApiJson.encodeToString(
            ServerMessage.serializer(),
            ServerMessage.TestEvent(sequence = 3, message = "hello", serverTimeEpochMillis = 99),
        )
        assertEquals("test_event", ApiJson.parseToJsonElement(encoded).jsonObject["type"]!!.jsonPrimitive.content)

        val decoded = ApiJson.decodeFromString(ServerMessage.serializer(), encoded)
        assertIs<ServerMessage.TestEvent>(decoded)
        assertEquals(3, decoded.sequence)
    }

    @Test
    fun clientMessages_roundTrip() {
        val ping: ClientMessage = ClientMessage.Ping(clientTimeEpochMillis = 5)
        val decoded = ApiJson.decodeFromString(ClientMessage.serializer(), ApiJson.encodeToString(ClientMessage.serializer(), ping))
        assertEquals(ping, decoded)
    }

    @Test
    fun gameDto_omitsNullFields_andRoundTrips() {
        val game = GameDto(
            gameId = "g-1",
            status = GameStatus.READY,
            centerLatitude = 37.0,
            centerLongitude = 127.0,
            currentRadiusMeters = 500.0,
            jailRadiusMeters = 10.0,
            createdAtEpochMillis = 1,
            players = listOf(GamePlayerDto("u-1", "a", PlayerRole.THIEF, PlayerStatus.ACTIVE)),
        )
        val encoded = ApiJson.encodeToString(GameDto.serializer(), game)
        assertFalse(ApiJson.parseToJsonElement(encoded).jsonObject.containsKey("result"))
        assertEquals(game, ApiJson.decodeFromString(GameDto.serializer(), encoded))
    }
}
