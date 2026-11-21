package com.nettwork.policethief.shared.rules

/** 위치 값 하나가 규칙을 어긴 이유. 서버 오류 응답의 details 에도 이 이름이 들어간다. */
enum class LocationValidationError {
    LATITUDE_OUT_OF_RANGE,
    LONGITUDE_OUT_OF_RANGE,
    ACCURACY_INVALID,
    MEASURED_IN_FUTURE,
    MEASUREMENT_TOO_OLD,
}

/**
 * 위치 데이터의 기본 유효성 검사.
 *
 * 클라이언트는 전송 전에 미리 걸러내기 위해, 서버는 클라이언트를 믿지 않고 다시 확인하기 위해
 * 같은 함수를 호출한다. 게임 구역 이탈·과속 같은 "게임 규칙" 판정은 여기서 하지 않는다.
 */
object LocationValidator {
    /** GPS 정확도(미터)의 허용 상한. 이보다 부정확한 값은 판정에 쓸 수 없으므로 거부한다. */
    const val MAX_ACCURACY_METERS = 1_000.0

    /** 단말과 서버 시계 차이를 감안해 허용하는 "미래 시각" 여유. */
    const val MAX_CLOCK_SKEW_MILLIS = 60 * 1000L

    /** 이보다 오래된 측정값은 현재 위치로 볼 수 없으므로 거부한다. */
    const val MAX_MEASUREMENT_AGE_MILLIS = 5 * 60 * 1000L

    fun validate(
        latitude: Double,
        longitude: Double,
        accuracyMeters: Double,
        measuredAtEpochMillis: Long,
        nowEpochMillis: Long,
    ): List<LocationValidationError> = buildList {
        if (!latitude.isFinite() || latitude !in -90.0..90.0) add(LocationValidationError.LATITUDE_OUT_OF_RANGE)
        if (!longitude.isFinite() || longitude !in -180.0..180.0) add(LocationValidationError.LONGITUDE_OUT_OF_RANGE)
        if (!accuracyMeters.isFinite() || accuracyMeters < 0.0 || accuracyMeters > MAX_ACCURACY_METERS) {
            add(LocationValidationError.ACCURACY_INVALID)
        }
        if (measuredAtEpochMillis > nowEpochMillis + MAX_CLOCK_SKEW_MILLIS) add(LocationValidationError.MEASURED_IN_FUTURE)
        if (measuredAtEpochMillis < nowEpochMillis - MAX_MEASUREMENT_AGE_MILLIS) add(LocationValidationError.MEASUREMENT_TOO_OLD)
    }
}
