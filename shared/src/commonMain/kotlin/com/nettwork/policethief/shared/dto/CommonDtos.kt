package com.nettwork.policethief.shared.dto

import kotlinx.serialization.Serializable

/*
 * 시간 표기 규칙: 모든 시각은 UTC 기준 epoch milliseconds(Long)로 주고받는다. (필드명 접미사 ...EpochMillis)
 * ID 규칙: userId, gameId 는 서버가 만든 UUID 문자열이다.
 */

/** GET /api/health 응답. */
@Serializable
data class HealthResponse(
    val status: String,
    val database: String,
    val version: String,
    val serverTimeEpochMillis: Long,
)

/** 모든 오류 응답의 공통 형식. HTTP 상태 코드와 함께 내려간다. */
@Serializable
data class ErrorResponse(
    val code: ErrorCode,
    val message: String,
    val details: Map<String, String> = emptyMap(),
)

@Serializable
enum class ErrorCode {
    BAD_REQUEST,
    VALIDATION_FAILED,
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,
    CONFLICT,
    NOT_IMPLEMENTED,
    INTERNAL_ERROR,
}
