package com.nettwork.policethief.platform

import com.nettwork.policethief.domain.repository.GameSessionRepository
import com.nettwork.policethief.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 경기 알림을 진동·시스템 알림으로 내보낸다(앱 범위, DI 에서 앱 시작 시 생성).
 * 앱 안 배너는 화면(NoticeHost)이 같은 notices 를 받아 따로 그린다.
 * 사용자 설정(진동·시스템 알림)을 따르고, 중요하지 않은 알림(재접속 등)은 배너로만 보여 준다.
 */
class GameAlertDispatcher(
    session: GameSessionRepository,
    private val alerts: GameAlerts,
    private val preferences: UserPreferencesRepository,
    scope: CoroutineScope,
) {
    init {
        scope.launch {
            session.notices.collect { notice ->
                if (!notice.important) return@collect
                val prefs = preferences.current.value
                if (prefs.vibration) runCatching { alerts.vibrate() }
                if (prefs.systemNotifications) runCatching { alerts.notify(notice.title, notice.message) }
            }
        }
    }
}
