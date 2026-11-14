package com.nettwork.policethief.server.common

import com.nettwork.policethief.shared.dto.ErrorCode
import io.ktor.http.HttpStatusCode

/**
 * 서비스 계층에서 "사용자에게 설명 가능한" 실패를 표현하는 예외.
 * StatusPages(plugins/ErrorHandling.kt)가 ErrorResponse JSON 으로 바꿔 응답한다.
 */
open class ApiException(
    val status: HttpStatusCode,
    val code: ErrorCode,
    override val message: String,
    val details: Map<String, String> = emptyMap(),
) : RuntimeException(message)

class ValidationException(message: String, details: Map<String, String> = emptyMap()) :
    ApiException(HttpStatusCode.UnprocessableEntity, ErrorCode.VALIDATION_FAILED, message, details)

class ForbiddenException(message: String, details: Map<String, String> = emptyMap()) :
    ApiException(HttpStatusCode.Forbidden, ErrorCode.FORBIDDEN, message, details)

class NotFoundException(message: String, details: Map<String, String> = emptyMap()) :
    ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, message, details)

/** 현재 상태와 맞지 않는 요청(이미 경기 중, 이미 종료된 경기 등). details["reason"] 에 기계가 읽을 사유를 담는다. */
class ConflictException(message: String, reason: String) :
    ApiException(HttpStatusCode.Conflict, ErrorCode.CONFLICT, message, mapOf("reason" to reason))
