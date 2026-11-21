package com.nettwork.policethief.server.game

/**
 * 팀 규칙으로 아직 확정되지 않은 판정 수치(PROVISIONAL)와 선택 기능 스위치.
 *
 * 확정된 규칙(4명, 2:2, 500m, 10분, 30초, 10m, 30분, 3분, 25km/h, 경고 3회)은 여기 두지 않고
 * shared 의 GameRules 상수만 사용한다(설정으로 바꿀 수 없게 하기 위함).
 * 아래 값의 근거와 바꾸는 방법은 docs/development/GAME_CONFIG.md 에 정리했다.
 * 모든 값은 환경 변수(PNT_GAME_*)로 덮어쓸 수 있다.
 */
data class GameSettings(
    // ---- 위치 품질 (PROVISIONAL) ----
    /** 매칭에 쓸 위치는 이 시간 안에 받은 것이어야 한다. */
    val matchLocationMaxAgeMillis: Long = 60_000,
    /** 매칭 요청 위치의 GPS 정확도 상한(미터). */
    val matchMaxAccuracyMeters: Double = 100.0,
    /** 구역 이탈·미션 판정에 쓸 위치의 정확도 상한(미터). 이보다 부정확하면 판정하지 않는다. */
    val judgementMaxAccuracyMeters: Double = 30.0,
    /** 감옥 자동 수감 판정에 쓸 위치의 정확도 상한(미터). */
    val jailMaxAccuracyMeters: Double = 15.0,
    /** 감옥 판정 위치의 최신성: 측정 후 이 시간이 지나면 쓰지 않는다. */
    val jailSampleMaxAgeMillis: Long = 30_000,

    // ---- 구역 (PROVISIONAL) ----
    /** 연속 구역 이탈 허용 시간. 넘으면 실격(무효 경기). 0 이면 실격 판정을 끈다. UI 설계안의 30초를 초기값으로 사용. */
    val zoneOutDisqualifyMillis: Long = 30_000,
    /** 구역 축소 일정: 경기 시작 후 offset 에 radius 로 축소. 중심은 유지. */
    val zoneShrinkStages: List<ZoneStagePlan> = listOf(
        ZoneStagePlan(offsetMillis = 10 * MINUTE, radiusMeters = 400.0),
        ZoneStagePlan(offsetMillis = 20 * MINUTE, radiusMeters = 300.0),
        ZoneStagePlan(offsetMillis = 25 * MINUTE, radiusMeters = 200.0),
    ),
    /** 축소 예정 알림을 보내는 시점(축소 몇 ms 전). */
    val zoneShrinkWarningMillis: Long = 60_000,

    // ---- 미션 (PROVISIONAL) ----
    /** 경기 시작 후 첫 미션까지 대기 시간. */
    val missionFirstDelayMillis: Long = 3 * MINUTE,
    /** 미션 종료(성공/실패) 후 다음 미션까지 간격. */
    val missionIntervalMillis: Long = 5 * MINUTE,
    /** 미션 제한 시간. */
    val missionDurationMillis: Long = 4 * MINUTE,
    /** 미션 구역 반경(미터). */
    val missionRadiusMeters: Double = 30.0,
    /** 미션 목표 지점은 감옥(구역 중심)에서 최소 이만큼 떨어진 곳에 만든다. */
    val missionMinDistanceFromJailMeters: Double = 50.0,
    /** 미션 실패 시 다음 구역 축소를 이만큼 앞당긴다. */
    val missionFailureShrinkAdvanceMillis: Long = 2 * MINUTE,

    // ---- 과속 (25km/h·3회는 확정, 판정 방법은 PROVISIONAL) ----
    /** 속도 계산에 쓰는 두 표본의 최소 시간 간격. 너무 짧으면 GPS 흔들림이 속도로 보인다. */
    val speedMinSampleIntervalMillis: Long = 5_000,
    /** 속도 계산에 쓸 표본의 정확도 상한(미터). */
    val speedMaxAccuracyMeters: Double = 20.0,
    /** 기준 초과 속도가 연속 몇 번 나와야 경고 1회로 인정할지. 단발성 GPS 튐으로 경고하지 않기 위함. */
    val speedConsecutiveSamplesRequired: Int = 2,
    /** 경고 사이 최소 간격(한 번 과속 구간에서 경고가 연달아 쌓이지 않게). */
    val speedWarningCooldownMillis: Long = 60_000,
    /** 이보다 빠른 이동은 순간 이동(GPS 오류)으로 보고 판정에서 제외한다. */
    val teleportSpeedKmh: Double = 150.0,

    // ---- 선택 기능 ----
    /** 경찰의 "도둑 폰 울리기"(UI 설계안 기능, 최신 요구사항에서 필수 여부 미확정) — 기본 꺼짐. */
    val ringThiefPhoneEnabled: Boolean = false,
    /** 폰 울리기 쿨다운(UI 설계안 60초). */
    val ringCooldownMillis: Long = 60_000,

    // ---- 운영 ----
    /** 위치 기록 보관 기간(받은 시각 기준). 경기 위치 포함 모든 기록을 지나면 삭제한다(경기는 최대 30분 남짓이라 진행 중 판정에 영향 없음). */
    val locationRetentionMillis: Long = 24 * 60 * MINUTE,
    /** 서버 스케줄러(시간 기반 판정) 실행 간격. */
    val schedulerIntervalMillis: Long = 1_000,
) {
    init {
        require(zoneShrinkStages.zipWithNext().all { (a, b) -> a.offsetMillis < b.offsetMillis && a.radiusMeters > b.radiusMeters }) {
            "구역 축소 단계는 시간 순서대로, 반경은 점점 작아지도록 설정해야 합니다."
        }
        require(speedConsecutiveSamplesRequired >= 1) { "speedConsecutiveSamplesRequired 는 1 이상이어야 합니다." }
    }

    companion object {
        const val MINUTE = 60_000L

        /** 환경 변수로 기본값을 덮어쓴다. 형식이 틀리면 서버 시작을 거부해 잘못된 값으로 경기가 진행되지 않게 한다. */
        fun fromEnvironment(env: Map<String, String>): GameSettings {
            val d = GameSettings()
            fun long(key: String, default: Long) = env[key]?.let { it.toLongOrNull() ?: invalid(key, it) } ?: default
            fun double(key: String, default: Double) = env[key]?.let { it.toDoubleOrNull() ?: invalid(key, it) } ?: default
            fun int(key: String, default: Int) = env[key]?.let { it.toIntOrNull() ?: invalid(key, it) } ?: default
            fun bool(key: String, default: Boolean) = env[key]?.let { it.toBooleanStrictOrNull() ?: invalid(key, it) } ?: default
            fun seconds(key: String, defaultMillis: Long) = env[key]?.let { (it.toLongOrNull() ?: invalid(key, it)) * 1000 } ?: defaultMillis

            return GameSettings(
                matchLocationMaxAgeMillis = seconds("PNT_GAME_MATCH_LOCATION_MAX_AGE_SECONDS", d.matchLocationMaxAgeMillis),
                matchMaxAccuracyMeters = double("PNT_GAME_MATCH_MAX_ACCURACY_M", d.matchMaxAccuracyMeters),
                judgementMaxAccuracyMeters = double("PNT_GAME_JUDGEMENT_MAX_ACCURACY_M", d.judgementMaxAccuracyMeters),
                jailMaxAccuracyMeters = double("PNT_GAME_JAIL_MAX_ACCURACY_M", d.jailMaxAccuracyMeters),
                jailSampleMaxAgeMillis = seconds("PNT_GAME_JAIL_SAMPLE_MAX_AGE_SECONDS", d.jailSampleMaxAgeMillis),
                zoneOutDisqualifyMillis = seconds("PNT_GAME_ZONE_OUT_DISQUALIFY_SECONDS", d.zoneOutDisqualifyMillis),
                zoneShrinkStages = env["PNT_GAME_ZONE_STAGES"]?.let { parseStages(it) } ?: d.zoneShrinkStages,
                zoneShrinkWarningMillis = seconds("PNT_GAME_ZONE_WARNING_SECONDS", d.zoneShrinkWarningMillis),
                missionFirstDelayMillis = seconds("PNT_GAME_MISSION_FIRST_DELAY_SECONDS", d.missionFirstDelayMillis),
                missionIntervalMillis = seconds("PNT_GAME_MISSION_INTERVAL_SECONDS", d.missionIntervalMillis),
                missionDurationMillis = seconds("PNT_GAME_MISSION_DURATION_SECONDS", d.missionDurationMillis),
                missionRadiusMeters = double("PNT_GAME_MISSION_RADIUS_M", d.missionRadiusMeters),
                missionMinDistanceFromJailMeters = double("PNT_GAME_MISSION_MIN_DISTANCE_FROM_JAIL_M", d.missionMinDistanceFromJailMeters),
                missionFailureShrinkAdvanceMillis = seconds("PNT_GAME_MISSION_FAILURE_ADVANCE_SECONDS", d.missionFailureShrinkAdvanceMillis),
                speedMinSampleIntervalMillis = seconds("PNT_GAME_SPEED_MIN_INTERVAL_SECONDS", d.speedMinSampleIntervalMillis),
                speedMaxAccuracyMeters = double("PNT_GAME_SPEED_MAX_ACCURACY_M", d.speedMaxAccuracyMeters),
                speedConsecutiveSamplesRequired = int("PNT_GAME_SPEED_CONSECUTIVE_SAMPLES", d.speedConsecutiveSamplesRequired),
                speedWarningCooldownMillis = seconds("PNT_GAME_SPEED_WARNING_COOLDOWN_SECONDS", d.speedWarningCooldownMillis),
                teleportSpeedKmh = double("PNT_GAME_TELEPORT_SPEED_KMH", d.teleportSpeedKmh),
                ringThiefPhoneEnabled = bool("PNT_FEATURE_RING_THIEF_PHONE", d.ringThiefPhoneEnabled),
                ringCooldownMillis = seconds("PNT_GAME_RING_COOLDOWN_SECONDS", d.ringCooldownMillis),
                locationRetentionMillis = long("PNT_LOCATION_RETENTION_HOURS", d.locationRetentionMillis / (60 * MINUTE)) * 60 * MINUTE,
            )
        }

        /** "600:400,1200:300" → (600초 후 400m, 1200초 후 300m). 빈 문자열이면 축소 없음. */
        fun parseStages(raw: String): List<ZoneStagePlan> = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { part ->
            val (seconds, meters) = part.split(':').also { if (it.size != 2) invalid("PNT_GAME_ZONE_STAGES", raw) }
            ZoneStagePlan(
                offsetMillis = (seconds.trim().toLongOrNull() ?: invalid("PNT_GAME_ZONE_STAGES", raw)) * 1000,
                radiusMeters = meters.trim().toDoubleOrNull() ?: invalid("PNT_GAME_ZONE_STAGES", raw),
            )
        }

        private fun invalid(key: String, value: String): Nothing =
            throw IllegalStateException("환경 변수 $key 의 값 '$value' 형식이 올바르지 않습니다. docs/development/GAME_CONFIG.md 참고.")
    }
}

/** 구역 축소 한 단계: 경기 시작 후 [offsetMillis] 에 반경 [radiusMeters] 로 줄인다. */
data class ZoneStagePlan(val offsetMillis: Long, val radiusMeters: Double)
