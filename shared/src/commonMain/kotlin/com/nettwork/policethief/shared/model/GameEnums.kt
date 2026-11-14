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
 * READY(지시서의 PREPARING) → PLAYING → FINISHED 가 정상 흐름이고,
 * 기권·실격·이탈 확정 시 CANCELLED(지시서의 INVALID, 결과는 항상 [GameResult.INVALID])로 끝난다.
 * A 방식과 API 호환을 유지하기 위해 기존 이름을 그대로 쓴다.
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
    /** 도둑 2명 모두 체포(호송 중 포함) → 경찰 승리. */
    ALL_THIEVES_CAPTURED,

    /** 30분 동안 경찰 승리 조건 미충족 → 도둑 승리. */
    TIME_OVER_THIEVES_SURVIVED,

    /** 명시적 기권 → 무효. */
    PLAYER_FORFEITED,

    /** 연결 끊김 후 3분 안에 재접속하지 못함 → 무효. */
    PLAYER_DISCONNECT_TIMEOUT,

    /** 과속 경고 3회 → 실격 → 무효. */
    PLAYER_DISQUALIFIED_SPEEDING,

    /** 게임 구역 연속 이탈 시간 초과 → 실격 → 무효(기준 시간은 서버 설정값, GAME_CONFIG.md). */
    PLAYER_DISQUALIFIED_ZONE_OUT,
}

/**
 * 플레이어의 접속 상태. 명시적 기권(LEFT)과 일시적 연결 끊김(RECONNECTING)을 구분한다.
 * RECONNECTING 상태가 재접속 유예시간(3분)을 넘기면 서버가 LEFT 로 확정하고 경기를 무효 처리한다.
 */
@Serializable
enum class PlayerConnectionState { CONNECTED, RECONNECTING, LEFT }

/**
 * 매칭 상태. IDLE(대기열에 없음) → WAITING → MATCHED / CANCELLED / EXPIRED(10분 초과, 지시서의 TIMED_OUT).
 * IDLE 은 DB 에 저장하지 않고 "대기열 행이 없음"을 API 로 표현할 때만 쓴다.
 */
@Serializable
enum class MatchmakingStatus { IDLE, WAITING, MATCHED, CANCELLED, EXPIRED }

/** WebSocket 매칭 알림 종류. */
@Serializable
enum class MatchmakingEventType { MATCHING_UPDATED, MATCHING_COMPLETED, MATCHING_TIMEOUT, MATCHING_CANCELLED }

/** 도둑 미션 상태. */
@Serializable
enum class MissionStatus { ACTIVE, COMPLETED, FAILED, CANCELLED }

/**
 * QR 스캔(체포·구출) 거절 사유. 서버가 정하고 앱은 사유별 안내 문구를 보여 준다.
 * HTTP 오류(401/403/404)가 아니라 "게임 규칙상 불가"일 때 accepted=false 와 함께 내려간다.
 */
@Serializable
enum class ScanRejectReason {
    /** 역할상 할 수 없는 스캔(도둑의 체포, 경찰의 구출, 경찰 QR 스캔 등). */
    NOT_ALLOWED,
    INVALID_TOKEN,
    OTHER_GAME,
    ALREADY_CAPTURED,
    ESCORT_LIMIT,
    TARGET_ESCORTED,
    TARGET_NOT_JAILED,
    SELF_SCAN,
    RESCUER_NOT_ACTIVE,
    GAME_NOT_PLAYING,
}

/**
 * 위치의 출처. MOCK 은 개발자 테스트 좌표이며, 서버는 개발 모드에서만 허용하고 별도로 표시해 저장한다.
 */
@Serializable
enum class LocationSource { GPS, MOCK }

/**
 * 경기 이벤트 종류. game_events 테이블과 WebSocket 푸시에 같은 값을 쓴다.
 * (일시 이벤트) 표시가 있는 종류는 DB 에 저장하지 않고 접속 중인 참가자에게만 보낸다(eventId = 0).
 * 매칭 단계 알림(MATCHING_UPDATED / COMPLETED / TIMEOUT)은 경기 ID 가 없으므로 [MatchmakingEventType] 으로 따로 보낸다.
 */
@Serializable
enum class GameEventType {
    /** 매칭 확정, 역할 공개 + 30초 준비 시작. */
    GAME_PREPARING,
    GAME_STARTED,

    /** (일시 이벤트) 공개가 허용된 참가자의 새 위치. */
    PLAYER_LOCATION_UPDATED,
    PLAYER_DISCONNECTED,
    PLAYER_RECONNECTED,
    THIEF_ARRESTED,
    THIEF_JAILED,
    THIEF_RESCUED,

    /** (일시 이벤트) 감옥 안으로 보이지만 GPS 정확도·최신성이 부족해 수감 판정 보류. */
    JAIL_CHECK_PENDING,
    ZONE_SHRINK_WARNING,
    ZONE_SHRUNK,
    ZONE_OUT_WARNING,
    ZONE_RETURNED,
    MISSION_STARTED,
    MISSION_COMPLETED,
    MISSION_FAILED,
    SPEED_WARNING,
    PLAYER_ELIMINATED,
    PLAYER_FORFEITED,

    /** 승패 확정(경찰 승리 / 도둑 승리). */
    GAME_FINISHED,

    /** 무효 경기 확정(기권·실격·이탈). */
    GAME_INVALIDATED,

    /** 경찰의 "도둑 폰 울리기"(Feature Flag, 기본 꺼짐). 대상 도둑에게만 전달. */
    THIEF_PHONE_RING,
}

/**
 * 이벤트 data(JSON) 의 키 이름. 서버가 채우고 앱이 읽는다. 키가 없을 수도 있으므로 읽을 때는 항상 null 을 고려한다.
 */
object GameEventKeys {
    /** 이벤트의 대상(체포된 도둑, 경고 받은 사람 등). */
    const val USER_ID = "userId"
    const val NICKNAME = "nickname"

    /** 이벤트를 일으킨 사람(체포한 경찰, 구출한 도둑 등). */
    const val BY_USER_ID = "byUserId"
    const val BY_NICKNAME = "byNickname"
    const val RADIUS_METERS = "radiusMeters"
    const val AT_EPOCH_MILLIS = "atEpochMillis"
    const val WARNING_COUNT = "warningCount"
    const val SPEED_KMH = "speedKmh"
    const val MISSION_ID = "missionId"
    const val RESULT = "result"
    const val REASON = "reason"
    const val STAGE = "stage"
    const val DISTANCE_METERS = "distanceMeters"
    const val ACCURACY_METERS = "accuracyMeters"
}
