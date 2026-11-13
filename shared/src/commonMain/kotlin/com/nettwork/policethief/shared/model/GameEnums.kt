package com.nettwork.policethief.shared.model

import kotlinx.serialization.Serializable

/*
 * 서버와 클라이언트가 함께 사용하는 상태 어휘.
 * JSON 에는 enum 이름 그대로(예: "POLICE") 직렬화된다. 이름을 바꾸면 API 호환성이 깨지므로 주의한다.
 */

/** 참가자 역할. 매칭 확정 시 서버가 무작위로 경찰 2명, 도둑 2명을 배정한다. */
@Serializable
enum class PlayerRole { POLICE, THIEF }

/**
 * 경기(Game) 자체의 상태. 플레이어 개인 상태([PlayerStatus])와 구분한다.
 *
 * READY → PLAYING → FINISHED 가 정상 흐름이고, 기권·이탈 확정 시 CANCELLED 로 끝난다.
 */
@Serializable
enum class GameStatus {
    /** 4명 확정 후 역할 공개와 30초 준비시간 진행 중. */
    READY,

    /** 30분 경기 진행 중. */
    PLAYING,

    /** 승패가 확정되어 정상 종료. */
    FINISHED,

    /** 기권·실격·재접속 유예시간 초과 등으로 무효 처리되어 종료. */
    CANCELLED,
}

/** 경기 안에서 플레이어 한 명의 상태. */
@Serializable
enum class PlayerStatus {
    /** 정상 활동 중(경찰은 항상 ACTIVE, 도둑은 미체포 상태). */
    ACTIVE,

    /** 경찰에게 체포되어 감옥으로 호송 중. 이 상태에서는 구출할 수 없다. */
    ESCORTED,

    /** 감옥에 수감됨. 미체포 동료 도둑이 QR 을 스캔하면 구출된다. */
    JAILED,

    /** 실격 또는 이탈 확정. */
    ELIMINATED,
}

/** 경기 결과. CANCELLED 경기의 결과는 항상 INVALID 이다. */
@Serializable
enum class GameResult { POLICE_WIN, THIEF_WIN, INVALID }

/** 경기가 끝난 이유. 결과 화면(S-12)의 안내 문구를 결정한다. */
@Serializable
enum class GameEndReason {
    ALL_THIEVES_CAPTURED,
    TIME_OVER_THIEVES_SURVIVED,
    PLAYER_FORFEITED,
    PLAYER_DISCONNECT_TIMEOUT,
    PLAYER_DISQUALIFIED_SPEEDING,
    LOCATION_TIMEOUT,
}

/**
 * 플레이어의 접속 상태. 명시적 기권(LEFT)과 일시적 연결 끊김(RECONNECTING)을 구분한다.
 * RECONNECTING 상태가 재접속 유예시간(3분)을 넘기면 서버가 LEFT 로 확정하고 경기를 무효 처리한다.
 */
@Serializable
enum class PlayerConnectionState { CONNECTED, RECONNECTING, LEFT }

/** 매칭 대기열 항목 상태. */
@Serializable
enum class MatchmakingStatus { WAITING, MATCHED, CANCELLED, EXPIRED }

/**
 * 위치의 출처. MOCK 은 개발자 테스트 좌표이며, 서버는 개발 모드에서만 허용하고 별도로 표시해 저장한다.
 */
@Serializable
enum class LocationSource { GPS, MOCK }

/** 경기 이벤트 종류. game_events 테이블과 WebSocket 푸시에 같은 값을 쓴다. */
@Serializable
enum class GameEventType {
    GAME_CREATED,
    ROLES_ASSIGNED,
    GAME_STARTED,
    PLAYER_CAPTURED,
    PLAYER_JAILED,
    PLAYER_RESCUED,
    ZONE_SHRUNK,
    SPEED_WARNING,
    PLAYER_DISCONNECTED,
    PLAYER_RECONNECTED,
    PLAYER_FORFEITED,
    PLAYER_ELIMINATED,
    GAME_FINISHED,
    GAME_CANCELLED,
}
