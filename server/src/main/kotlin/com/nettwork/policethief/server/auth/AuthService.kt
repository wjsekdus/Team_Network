package com.nettwork.policethief.server.auth

import com.nettwork.policethief.server.common.NotFoundException
import com.nettwork.policethief.server.common.ValidationException
import com.nettwork.policethief.shared.dto.GuestLoginResponse
import com.nettwork.policethief.shared.dto.UserDto
import com.nettwork.policethief.shared.rules.NicknamePolicy
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.UUID

/** 인증에 성공한 요청의 주체. Ktor `call.principal<UserPrincipal>()` 으로 꺼낸다. */
data class UserPrincipal(val userId: UUID)

/**
 * 게스트 로그인과 토큰 검증.
 *
 * 설계 의도
 * - 닉네임은 표시용일 뿐이다. 같은 닉네임으로 로그인해도 매번 새 userId 와 새 토큰을 발급한다.
 * - 토큰은 SecureRandom 32바이트(256비트)라 추측할 수 없고, DB 에는 SHA-256 해시만 저장해
 *   DB 가 유출돼도 토큰 원문을 재사용할 수 없다.
 * - 서명 키가 필요한 JWT 대신 "DB 조회형 불투명 토큰"을 써서 비밀 키 관리 부담을 없앴다.
 */
class AuthService(
    private val users: UserRepository,
    private val tokens: AuthTokenRepository,
    private val clock: Clock,
    private val tokenTtl: Duration,
) {
    private val random = SecureRandom()

    suspend fun guestLogin(rawNickname: String): GuestLoginResponse {
        NicknamePolicy.validate(rawNickname)?.let { error ->
            throw ValidationException("닉네임 규칙을 확인하세요.", mapOf("nickname" to error.name))
        }
        val now = clock.instant()
        val user = users.create(NicknamePolicy.normalize(rawNickname), now)
        val token = newToken()
        tokens.save(hash(token), user.id, now, now.plus(tokenTtl))
        return GuestLoginResponse(accessToken = token, user = user.toDto())
    }

    /** Bearer 토큰 검증. 유효하면 마지막 활동 시각을 갱신하고 주체를 돌려준다. */
    suspend fun authenticate(token: String): UserPrincipal? {
        if (token.isBlank() || token.length > MAX_TOKEN_LENGTH) return null
        val now = clock.instant()
        val userId = tokens.findActiveUserId(hash(token), now) ?: return null
        users.touchLastActive(userId, now)
        return UserPrincipal(userId)
    }

    suspend fun getUser(userId: UUID): UserDto =
        users.findById(userId)?.toDto() ?: throw NotFoundException("사용자를 찾을 수 없습니다.")

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TOKEN_BYTES = 32
        const val MAX_TOKEN_LENGTH = 128
    }
}

fun UserRecord.toDto() = UserDto(
    userId = id.toString(),
    nickname = nickname,
    createdAtEpochMillis = createdAt.toEpochMilli(),
    lastActiveAtEpochMillis = lastActiveAt.toEpochMilli(),
)
