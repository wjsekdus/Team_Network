package com.nettwork.policethief.server.game

import com.nettwork.policethief.server.common.NotImplementedFeatureException
import com.nettwork.policethief.shared.dto.GameDto
import com.nettwork.policethief.shared.dto.GameEventDto
import com.nettwork.policethief.shared.dto.JoinMatchmakingRequest
import com.nettwork.policethief.shared.dto.MatchmakingTicketDto
import com.nettwork.policethief.shared.dto.QrScanRequest
import com.nettwork.policethief.shared.dto.QrScanResultDto
import com.nettwork.policethief.shared.dto.QrTokenDto
import com.nettwork.policethief.shared.dto.VisibleLocationsResponse
import java.util.UUID

/*
 * 향후 구현할 기능의 서버 측 계약(인터페이스).
 *
 * 지금은 모든 메서드가 NotImplementedFeatureException(HTTP 501)을 던지는 기본 구현만 있다.
 * 팀원이 기능을 구현할 때는 이 인터페이스의 실제 구현 클래스를 만들고 ServerDependencies 에서 교체한다.
 * 라우트(GameRoutes.kt)는 수정할 필요가 없다.
 *
 * 공통 원칙: 모든 판정(매칭, 역할 배정, 체포, 수감, 구출, 승패, 실격)은 서버에서만 확정한다.
 */

/** Matchmaking: 반경 500m 4인 매칭, 10분 제한. */
interface MatchmakingService {
    suspend fun join(userId: UUID, request: JoinMatchmakingRequest): MatchmakingTicketDto
    suspend fun cancel(userId: UUID)
    suspend fun myTicket(userId: UUID): MatchmakingTicketDto
}

/** Game: 경기 생성·역할 배정·시작·종료, 위치 공개 범위, 기권·재접속 처리. */
interface GameService {
    suspend fun currentGame(userId: UUID): GameDto
    suspend fun getGame(userId: UUID, gameId: UUID): GameDto

    /** 요청자 역할에 따라 필터링된 위치(경찰: 전원, 도둑: 도둑끼리). 경기 종료 후에는 거부. */
    suspend fun visibleLocations(userId: UUID, gameId: UUID): VisibleLocationsResponse

    /** 명시적 기권. 경기는 즉시 CANCELLED / INVALID 로 종료된다. */
    suspend fun forfeit(userId: UUID, gameId: UUID): GameDto

    /** afterEventId 이후 이벤트(재접속 시 누락 이벤트 재조회용). */
    suspend fun events(userId: UUID, gameId: UUID, afterEventId: Long): List<GameEventDto>
}

/** QR 토큰 발급·조회. 경기별 고정 토큰. */
interface QrTokenService {
    suspend fun myToken(userId: UUID, gameId: UUID): QrTokenDto
}

/** Capture: 경찰이 도둑 QR 스캔 → 체포·호송(경찰 1명당 1명). */
interface CaptureService {
    suspend fun capture(policeUserId: UUID, gameId: UUID, request: QrScanRequest): QrScanResultDto
}

/** Rescue: 미체포 도둑이 수감된 도둑 QR 스캔 → 구출(호송 중 구출 불가). */
interface RescueService {
    suspend fun rescue(thiefUserId: UUID, gameId: UUID, request: QrScanRequest): QrScanResultDto
}

class NotImplementedMatchmakingService : MatchmakingService {
    override suspend fun join(userId: UUID, request: JoinMatchmakingRequest) = notImplemented("matchmaking.join")
    override suspend fun cancel(userId: UUID) = notImplemented("matchmaking.cancel")
    override suspend fun myTicket(userId: UUID) = notImplemented("matchmaking.myTicket")
}

class NotImplementedGameService : GameService {
    override suspend fun currentGame(userId: UUID) = notImplemented("game.current")
    override suspend fun getGame(userId: UUID, gameId: UUID) = notImplemented("game.get")
    override suspend fun visibleLocations(userId: UUID, gameId: UUID) = notImplemented("game.visibleLocations")
    override suspend fun forfeit(userId: UUID, gameId: UUID) = notImplemented("game.forfeit")
    override suspend fun events(userId: UUID, gameId: UUID, afterEventId: Long) = notImplemented("game.events")
}

class NotImplementedQrTokenService : QrTokenService {
    override suspend fun myToken(userId: UUID, gameId: UUID) = notImplemented("qr.myToken")
}

class NotImplementedCaptureService : CaptureService {
    override suspend fun capture(policeUserId: UUID, gameId: UUID, request: QrScanRequest) = notImplemented("capture")
}

class NotImplementedRescueService : RescueService {
    override suspend fun rescue(thiefUserId: UUID, gameId: UUID, request: QrScanRequest) = notImplemented("rescue")
}

private fun notImplemented(feature: String): Nothing = throw NotImplementedFeatureException(feature)
