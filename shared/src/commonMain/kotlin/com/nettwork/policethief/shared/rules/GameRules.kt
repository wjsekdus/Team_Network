package com.nettwork.policethief.shared.rules

/**
 * 확정된 게임 규칙 상수.
 *
 * 서버 판정과 클라이언트 표시가 같은 숫자를 쓰도록 한 곳에 모았다.
 * 규칙이 바뀌면 이 파일과 docs/architecture/ARCHITECTURE.md 의 규칙 표를 함께 수정한다.
 */
object GameRules {
    const val PLAYERS_PER_GAME = 4
    const val POLICE_COUNT = 2
    const val THIEF_COUNT = 2

    /** 매칭 후보를 찾는 반경(현재 위치 기준). */
    const val MATCH_RADIUS_METERS = 500.0

    /** 매칭 최대 대기 시간: 10분. */
    const val MATCH_TIMEOUT_MILLIS = 10 * 60 * 1000L

    /** 매칭 완료 후 역할 공개 및 준비 시간: 30초. */
    const val PREPARATION_MILLIS = 30 * 1000L

    /** 게임 구역 초기 반경. 중심은 참가자 4명 위치의 중심점. */
    const val INITIAL_ZONE_RADIUS_METERS = 500.0

    /** 감옥(게임 구역 중심) 반경. 호송 중인 경찰과 도둑이 모두 이 안에 들어오면 수감. */
    const val JAIL_RADIUS_METERS = 10.0

    /** 경기 제한 시간: 30분. 이때까지 미체포 도둑이 있으면 도둑 승리. */
    const val GAME_DURATION_MILLIS = 30 * 60 * 1000L

    /** 위치 갱신 목표 주기. OS 백그라운드 제한 때문에 보장되지 않는다. */
    const val LOCATION_UPDATE_INTERVAL_MILLIS = 15 * 1000L

    /** 위치 미수신 시 "지연" 경고를 띄우는 기준. */
    const val LOCATION_STALE_WARNING_MILLIS = 30 * 1000L

    /** 연결 끊김 후 재접속을 허용하는 유예시간: 3분. */
    const val RECONNECT_GRACE_MILLIS = 3 * 60 * 1000L

    /** 경찰 1명이 동시에 호송할 수 있는 도둑 수. */
    const val MAX_ESCORTS_PER_POLICE = 1

    /** 과속 기준(기획서 FR-08, 우선순위 Could). */
    const val SPEED_LIMIT_KMH = 25.0

    /** 과속 경고가 이 횟수에 도달하면 실격. */
    const val SPEED_WARNINGS_TO_DISQUALIFY = 3
}
