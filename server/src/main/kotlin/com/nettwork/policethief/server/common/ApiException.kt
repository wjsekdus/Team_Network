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

class NotFoundException(message: String) :
    ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, message)

/**
 * 계약(API 경로·DTO)만 정의되고 아직 구현되지 않은 기능.
 * 클라이언트가 "정상 동작"으로 오해하지 않도록 501 Not Implemented 로 응답한다.
 */
class NotImplementedFeatureException(feature: String) :
    ApiException(HttpStatusCode.NotImplemented, ErrorCode.NOT_IMPLEMENTED, "아직 구현되지 않은 기능입니다: $feature")
