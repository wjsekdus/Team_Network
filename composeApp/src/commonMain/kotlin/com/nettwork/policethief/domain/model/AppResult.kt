package com.nettwork.policethief.domain.model

/**
 * 성공/실패를 명시적으로 표현하는 결과 타입.
 * Repository 와 UseCase 는 예외를 던지지 않고 이 타입을 돌려주므로, ViewModel 은 when 으로 모든 경우를 처리하게 된다.
 */
sealed interface AppResult<out T> {
    data class Success<T>(val value: T) : AppResult<T>
    data class Failure(val error: AppError) : AppResult<Nothing>
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(value))
    is AppResult.Failure -> this
}

/** 사용자에게 보여줄 수 있는 실패 원인. UI 는 [userMessage] 를 그대로 표시할 수 있다. */
sealed class AppError(val userMessage: String) {
    /** 서버에 닿지 못함(오프라인, 주소 오류, 타임아웃). */
    class Network(detail: String?) : AppError("서버에 연결할 수 없습니다. 네트워크와 서버 주소를 확인하세요." + (detail?.let { "\n($it)" } ?: ""))

    /** 토큰 없음/만료. 다시 로그인해야 한다. */
    data object Unauthorized : AppError("로그인이 만료되었습니다. 다시 로그인해 주세요.")

    /** 입력값 검증 실패. fields 에 필드별 사유가 들어 있다. */
    class Validation(message: String, val fields: Map<String, String> = emptyMap()) : AppError(message)

    /** 서버 계약은 있지만 아직 구현되지 않은 기능(HTTP 501). */
    class NotImplemented(feature: String) : AppError("아직 구현되지 않은 기능입니다: $feature")

    /** 위치 수집 실패. */
    class Location(message: String, val reason: LocationFailureReason) : AppError(message)

    /** 서버가 오류를 돌려줌. */
    class Server(val httpStatus: Int, message: String) : AppError(message)

    class Unknown(detail: String?) : AppError("알 수 없는 오류가 발생했습니다." + (detail?.let { "\n($it)" } ?: ""))
}

enum class LocationFailureReason { PERMISSION_DENIED, SERVICE_DISABLED, TIMEOUT, UNAVAILABLE, INVALID_DATA }
