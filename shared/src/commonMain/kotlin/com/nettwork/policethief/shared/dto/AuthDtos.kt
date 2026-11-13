package com.nettwork.policethief.shared.dto

import kotlinx.serialization.Serializable

/** POST /api/auth/guest 요청. 닉네임 규칙은 NicknamePolicy 참고. */
@Serializable
data class GuestLoginRequest(val nickname: String)

/**
 * POST /api/auth/guest 응답.
 * accessToken 은 이후 모든 인증 API 에 `Authorization: Bearer <token>` 으로 보낸다.
 * 서버는 토큰 원문을 저장하지 않고 SHA-256 해시만 저장한다.
 */
@Serializable
data class GuestLoginResponse(
    val accessToken: String,
    val tokenType: String = "Bearer",
    val user: UserDto,
)

/** GET /api/users/me 응답, 로그인 응답의 user 필드. */
@Serializable
data class UserDto(
    val userId: String,
    val nickname: String,
    val createdAtEpochMillis: Long,
    val lastActiveAtEpochMillis: Long,
)
