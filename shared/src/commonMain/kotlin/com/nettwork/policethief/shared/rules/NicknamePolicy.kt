package com.nettwork.policethief.shared.rules

/** 닉네임 규칙 위반 사유. */
enum class NicknameError { BLANK, TOO_SHORT, TOO_LONG, INVALID_CHARACTERS }

/**
 * 게스트 닉네임 규칙. 닉네임은 "표시 이름"일 뿐 인증 수단이 아니므로 중복을 허용한다.
 * (같은 닉네임이라도 서버가 서로 다른 userId 와 토큰을 발급한다.)
 */
object NicknamePolicy {
    const val MIN_LENGTH = 2
    const val MAX_LENGTH = 12

    // 한글 완성형, 영문, 숫자, 밑줄만 허용한다. 공백·특수문자는 지도 마커/알림 표시가 깨지지 않도록 막는다.
    private val allowed = Regex("^[가-힣a-zA-Z0-9_]+$")

    /** 앞뒤 공백을 제거한 값. 서버와 클라이언트 모두 이 값으로 검증·저장한다. */
    fun normalize(raw: String): String = raw.trim()

    /** 규칙을 만족하면 null, 아니면 첫 번째 위반 사유를 돌려준다. */
    fun validate(raw: String): NicknameError? {
        val nickname = normalize(raw)
        return when {
            nickname.isEmpty() -> NicknameError.BLANK
            nickname.length < MIN_LENGTH -> NicknameError.TOO_SHORT
            nickname.length > MAX_LENGTH -> NicknameError.TOO_LONG
            !allowed.matches(nickname) -> NicknameError.INVALID_CHARACTERS
            else -> null
        }
    }
}
