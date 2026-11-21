package com.nettwork.policethief.shared.dto

import com.nettwork.policethief.shared.model.PlayerRole
import com.nettwork.policethief.shared.model.PlayerStatus
import kotlinx.serialization.Serializable

/*
 * 개발자 테스트 도구 API(서버 환경 변수 PNT_DEV_TOOLS=true 인 개발 서버에서만 동작).
 * 휴대폰 1대로도 4인 경기를 끝까지 진행해 볼 수 있도록 "봇" 참가자와 "서버 시간 이동"을 제공한다.
 * 봇의 행동도 일반 사용자와 같은 서버 판정 코드를 거친다(판정 우회 없음).
 */

/** GET /api/dev/status */
@Serializable
data class DevStatusDto(val enabled: Boolean, val clockOffsetMillis: Long, val serverTimeEpochMillis: Long)

/** POST /api/dev/bots: 요청자 주변에 봇을 만들어 매칭 대기열에 넣는다. 좌표가 없으면 요청자의 마지막 위치를 쓴다. */
@Serializable
data class DevCreateBotsRequest(
    val count: Int = 3,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** 기준점에서 봇을 흩어 놓을 거리(미터). */
    val spreadMeters: Double = 40.0,
)

@Serializable
data class DevBotDto(
    val userId: String,
    val nickname: String,
    val role: PlayerRole? = null,
    val status: PlayerStatus? = null,
    /** 봇이 도둑이면 그 봇의 QR 토큰(개발 도구에서 "가상 스캔"에 사용). 사람 참가자의 토큰은 절대 내려주지 않는다. */
    val qrToken: String? = null,
)

@Serializable
data class DevBotsResponse(val bots: List<DevBotDto>)

/** POST /api/dev/bots/{botUserId}/location */
@Serializable
data class DevBotLocationRequest(val latitude: Double, val longitude: Double, val accuracyMeters: Double = 5.0)

/** POST /api/dev/bots/{botUserId}/scan: 봇이 QR 을 스캔한 것으로 처리(봇 역할에 따라 체포 또는 구출). */
@Serializable
data class DevBotScanRequest(val token: String)

/** POST /api/dev/bots/{botUserId}/presence: 봇의 연결 끊김/재접속을 흉내 낸다(3분 유예 테스트). */
@Serializable
data class DevBotPresenceRequest(val connected: Boolean)

/** POST /api/dev/clock/advance: 서버 시계를 앞으로 돌린다(30분을 기다리지 않고 종료 판정 확인). */
@Serializable
data class DevClockAdvanceRequest(val millis: Long)
