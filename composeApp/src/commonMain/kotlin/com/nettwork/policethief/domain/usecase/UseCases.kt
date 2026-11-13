package com.nettwork.policethief.domain.usecase

import com.nettwork.policethief.domain.model.AppError
import com.nettwork.policethief.domain.model.AppResult
import com.nettwork.policethief.domain.model.LocationFailureReason
import com.nettwork.policethief.domain.model.PlayerLocation
import com.nettwork.policethief.domain.model.ServerStatus
import com.nettwork.policethief.domain.model.User
import com.nettwork.policethief.domain.repository.AuthRepository
import com.nettwork.policethief.domain.repository.LocationRepository
import com.nettwork.policethief.domain.repository.ServerStatusRepository
import com.nettwork.policethief.domain.repository.SessionRestoreResult
import com.nettwork.policethief.shared.rules.NicknameError
import com.nettwork.policethief.shared.rules.NicknamePolicy

/*
 * UseCase: 화면 하나의 "의도"를 표현한다. ViewModel → UseCase → Repository 순서로 호출된다.
 * 단순 위임만 하는 UseCase 는 만들지 않고, 규칙(검증·조합)이 있는 경우에만 만든다.
 */

/** 닉네임 검증 후 게스트 로그인. 서버도 같은 규칙(NicknamePolicy)으로 다시 검증한다. */
class GuestLoginUseCase(private val auth: AuthRepository) {
    suspend operator fun invoke(rawNickname: String): AppResult<User> {
        NicknamePolicy.validate(rawNickname)?.let { error ->
            return AppResult.Failure(AppError.Validation(error.toMessage(), mapOf("nickname" to error.name)))
        }
        return auth.guestLogin(NicknamePolicy.normalize(rawNickname))
    }
}

fun NicknameError.toMessage(): String = when (this) {
    NicknameError.BLANK -> "닉네임을 입력해 주세요."
    NicknameError.TOO_SHORT -> "닉네임은 ${NicknamePolicy.MIN_LENGTH}자 이상이어야 합니다."
    NicknameError.TOO_LONG -> "닉네임은 ${NicknamePolicy.MAX_LENGTH}자 이하여야 합니다."
    NicknameError.INVALID_CHARACTERS -> "한글, 영문, 숫자, 밑줄(_)만 사용할 수 있습니다."
}

/** 앱 시작 시 저장된 토큰으로 로그인 상태를 복구한다. */
class RestoreSessionUseCase(private val auth: AuthRepository) {
    suspend operator fun invoke(): AppResult<SessionRestoreResult> = auth.restoreSession()
}

class CheckServerStatusUseCase(private val repository: ServerStatusRepository) {
    suspend operator fun invoke(): AppResult<ServerStatus> = repository.check()
}

/**
 * 현재 위치를 얻어 서버로 보낸다.
 * 위치 수집에 실패하면 전송하지 않고 실패를 그대로 돌려준다(이전 위치를 "현재"로 속여 보내지 않음).
 */
class ShareCurrentLocationUseCase(private val locations: LocationRepository) {
    suspend operator fun invoke(): AppResult<PlayerLocation> =
        when (val current = locations.fetchCurrentLocation()) {
            is AppResult.Failure -> current
            is AppResult.Success -> locations.sendLocation(current.value)
        }
}

/** 위치 실패 원인을 화면 행동(설정 열기/다시 시도)으로 바꾸기 쉽게 꺼내는 헬퍼. */
fun AppError.locationReason(): LocationFailureReason? = (this as? AppError.Location)?.reason
