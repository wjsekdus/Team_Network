package com.nettwork.policethief.data.repository

import com.nettwork.policethief.core.EpochClock
import com.nettwork.policethief.domain.model.AppResult
import com.nettwork.policethief.domain.model.GameEvent
import com.nettwork.policethief.domain.model.GameSnapshot
import com.nettwork.policethief.domain.model.PlayerLocation
import com.nettwork.policethief.domain.model.RealtimeEvent
import com.nettwork.policethief.domain.repository.AuthRepository
import com.nettwork.policethief.domain.repository.GameNotice
import com.nettwork.policethief.domain.repository.GameRepository
import com.nettwork.policethief.domain.repository.GameSessionRepository
import com.nettwork.policethief.domain.repository.GameSessionState
import com.nettwork.policethief.domain.repository.LocationRepository
import com.nettwork.policethief.domain.repository.RealtimeRepository
import com.nettwork.policethief.platform.BackgroundLocationController
import com.nettwork.policethief.shared.model.GameEventKeys
import com.nettwork.policethief.shared.model.GameEventType
import com.nettwork.policethief.shared.model.PlayerRole
import com.nettwork.policethief.shared.rules.GameRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 경기 하나를 서버와 계속 맞추는 저장소(앱 범위 싱글턴). 화면을 옮겨 다녀도 동기화와 위치 전송이 끊기지 않는다.
 *
 * 동기화 규칙(지시서 17장)
 * - 저장된 이벤트(eventId > 0)는 ID 로 중복을 거른다. 같은 이벤트가 두 번 와도 알림·상태가 두 번 바뀌지 않는다.
 * - 경기 상태는 버전(version)이 더 큰 응답만 받아들인다(늦게 도착한 옛 응답 무시).
 * - 이벤트를 받으면 GET /state 로 서버 상태를 다시 받는다(이벤트 내용으로 단말이 상태를 추측하지 않음).
 * - WebSocket 재연결(Welcome) 시, 그리고 [resyncIntervalMillis] 마다 다시 받아 놓친 메시지를 메운다.
 *
 * 위치 전송
 * - 경기(준비 포함) 중에는 약 15초마다 내 위치를 보낸다. 백그라운드 위치 서비스가 켜져 있으면 서비스가 보내므로 중복 전송하지 않는다.
 * - 경기가 끝나면 위치 전송과 백그라운드 서비스를 멈춘다(배터리, 지시서 10.4).
 */
class GameSessionRepositoryImpl(
    private val games: GameRepository,
    private val locations: LocationRepository,
    private val realtime: RealtimeRepository,
    private val auth: AuthRepository,
    private val background: BackgroundLocationController,
    private val clock: EpochClock,
    private val scope: CoroutineScope,
    private val resyncIntervalMillis: Long = GameRules.LOCATION_UPDATE_INTERVAL_MILLIS,
    private val locationIntervalMillis: Long = GameRules.LOCATION_UPDATE_INTERVAL_MILLIS,
    /** 경기가 시작되면 백그라운드 위치 서비스를 자동으로 켤지. */
    private val autoStartBackground: () -> Boolean = { true },
) : GameSessionRepository {
    private val sessionState = MutableStateFlow<GameSessionState?>(null)
    override val state: StateFlow<GameSessionState?> = sessionState.asStateFlow()

    private val noticeFlow = MutableSharedFlow<GameNotice>(extraBufferCapacity = 32)
    override val notices: Flow<GameNotice> = noticeFlow.asSharedFlow()

    private var job: Job? = null
    private val refreshLock = Mutex()
    private val eventLock = Mutex()
    private val seenEventIds = LinkedHashSet<Long>()
    private var initialLoadDone = false

    override fun start(gameId: String) {
        if (sessionState.value?.gameId == gameId && job?.isActive == true) return
        stop()
        sessionState.value = GameSessionState(gameId)
        seenEventIds.clear()
        initialLoadDone = false
        realtime.connect()
        job = scope.launch {
            launch { realtime.events.collect { handle(gameId, it) } }
            launch {
                while (isActive) {
                    refresh()
                    if (sessionState.value?.snapshot?.game?.isEnded == true) break
                    delay(resyncIntervalMillis)
                }
            }
            launch { locationLoop(gameId) }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
        sessionState.value = null
    }

    override suspend fun refresh() {
        val gameId = sessionState.value?.gameId ?: return
        refreshLock.withLock {
            when (val result = games.state(gameId)) {
                is AppResult.Success -> applySnapshot(gameId, result.value)
                is AppResult.Failure -> sessionState.update { it?.takeIf { s -> s.gameId == gameId }?.copy(syncError = result.error.userMessage) ?: it }
            }
        }
    }

    private suspend fun applySnapshot(gameId: String, incoming: GameSnapshot) {
        val now = clock.nowMillis()
        // 첫 조회에서 받은 과거 이벤트는 "이미 본 것"으로만 기록한다(재실행 시 지난 알림을 다시 띄우지 않음).
        val notify = initialLoadDone
        incoming.recentEvents.filter { it.eventId > 0 }.forEach { if (markNew(it.eventId) && notify) emitNotice(it) }
        initialLoadDone = true
        sessionState.update { current ->
            if (current == null || current.gameId != gameId) return@update current
            val previous = current.snapshot
            if (previous != null && previous.game.version > incoming.game.version) {
                current.copy(syncError = null, syncedAtEpochMillis = now)
            } else {
                current.copy(
                    snapshot = incoming,
                    syncedAtEpochMillis = now,
                    locationsReceivedAtEpochMillis = if (incoming.locations.isNotEmpty()) now else current.locationsReceivedAtEpochMillis,
                    syncError = null,
                )
            }
        }
        if (incoming.game.isEnded) background.stop()
    }

    private suspend fun handle(gameId: String, event: RealtimeEvent) {
        when (event) {
            is RealtimeEvent.Game -> {
                if (event.event.gameId != gameId) return
                if (event.event.eventId > 0) {
                    if (!markNew(event.event.eventId)) return
                    emitNotice(event.event)
                    refresh()
                } else {
                    emitNotice(event.event)
                }
            }
            is RealtimeEvent.Location -> if (event.gameId == gameId) mergeLocation(event.location)
            is RealtimeEvent.Welcome -> if (event.activeGameId == gameId) refresh()
            else -> Unit
        }
    }

    /** 실시간 위치 반영. 같은 사람의 더 오래된 측정값은 무시한다(순서 역전). */
    private fun mergeLocation(location: PlayerLocation) {
        val userId = location.userId ?: return
        sessionState.update { current ->
            val snapshot = current?.snapshot ?: return@update current
            if (snapshot.game.isEnded) return@update current
            val existing = snapshot.locations.firstOrNull { it.userId == userId }
            if (existing != null && existing.timestampEpochMillis >= location.timestampEpochMillis) return@update current
            current.copy(
                snapshot = snapshot.copy(locations = snapshot.locations.filter { it.userId != userId } + location),
                locationsReceivedAtEpochMillis = clock.nowMillis(),
            )
        }
    }

    private suspend fun locationLoop(gameId: String) {
        var backgroundRequested = false
        while (true) {
            val game = sessionState.value?.takeIf { it.gameId == gameId }?.snapshot?.game
            if (game != null && game.isEnded) {
                background.stop()
                return
            }
            if (game != null) {
                if (!backgroundRequested && background.isSupported && autoStartBackground()) {
                    background.start()
                    backgroundRequested = true
                }
                if (background.isRunning.value) {
                    sessionState.update { it?.copy(lastSentAtEpochMillis = locations.lastSentAtEpochMillis.value, myLocation = locations.lastLocation.value) }
                } else {
                    sendOnce()
                }
            }
            delay(if (game == null) 1_000 else locationIntervalMillis)
        }
    }

    private suspend fun sendOnce() {
        when (val current = locations.fetchCurrentLocation()) {
            is AppResult.Failure -> sessionState.update { it?.copy(myLocationError = current.error.userMessage) }
            is AppResult.Success -> {
                sessionState.update { it?.copy(myLocation = current.value, myLocationError = null) }
                val sent = locations.sendLocation(current.value)
                sessionState.update {
                    it?.copy(
                        lastSentAtEpochMillis = locations.lastSentAtEpochMillis.value,
                        sendError = (sent as? AppResult.Failure)?.error?.userMessage,
                    )
                }
            }
        }
    }

    /** 처음 보는 이벤트면 기록하고 true. WebSocket 수신과 재조회가 동시에 와도 한 번만 true 가 된다. */
    private suspend fun markNew(eventId: Long): Boolean = eventLock.withLock {
        if (eventId in seenEventIds) return@withLock false
        seenEventIds += eventId
        while (seenEventIds.size > MAX_REMEMBERED_EVENTS) seenEventIds.remove(seenEventIds.first())
        true
    }

    private fun emitNotice(event: GameEvent) {
        GameNotices.from(event, auth.currentUser.value?.userId)?.let { noticeFlow.tryEmit(it) }
    }

    private companion object {
        const val MAX_REMEMBERED_EVENTS = 500
    }
}

/** 서버 이벤트 → 사용자 알림 문구. 알림이 필요 없는 이벤트는 null. */
object GameNotices {
    fun from(event: GameEvent, myUserId: String?): GameNotice? {
        val target = event.data[GameEventKeys.NICKNAME] ?: "참가자"
        val by = event.data[GameEventKeys.BY_NICKNAME]
        val isMe = event.data[GameEventKeys.USER_ID] == myUserId
        val radius = event.data[GameEventKeys.RADIUS_METERS]?.toDoubleOrNull()?.toInt()
        fun notice(title: String, message: String, important: Boolean = true) = GameNotice(event.eventId, event.type, title, message, important)
        return when (event.type) {
            GameEventType.GAME_PREPARING -> notice("매칭 완료", "역할과 게임 구역을 확인하세요. 30초 뒤 경기가 시작됩니다.")
            GameEventType.GAME_STARTED -> notice("경기 시작", "30분 경기가 시작되었습니다.")
            GameEventType.THIEF_ARRESTED -> if (isMe) notice("체포되었습니다", "${by ?: "경찰"}에게 체포되었습니다. 감옥으로 호송됩니다.") else notice("체포", "$target 체포" + (by?.let { " (경찰 $it)" } ?: ""))
            GameEventType.THIEF_JAILED -> notice("수감", if (isMe) "감옥에 수감되었습니다. 동료가 내 QR 을 스캔하면 구출됩니다." else "$target 수감 완료")
            GameEventType.THIEF_RESCUED -> notice("구출", if (isMe) "동료가 구출해 주었습니다! 다시 도망치세요." else "$target 구출" + (by?.let { " (구출: $it)" } ?: ""))
            GameEventType.ZONE_SHRINK_WARNING -> notice("구역 축소 예정", "곧 게임 구역이 ${radius ?: "-"}m 로 줄어듭니다.")
            GameEventType.ZONE_SHRUNK -> notice("구역 축소", "게임 구역이 ${radius ?: "-"}m 로 줄었습니다.")
            GameEventType.ZONE_OUT_WARNING -> notice("구역 이탈 경고", "게임 구역 밖입니다. 바로 구역 안으로 돌아오세요. 계속 벗어나 있으면 실격됩니다.")
            GameEventType.ZONE_RETURNED -> notice("구역 복귀", "게임 구역 안으로 돌아왔습니다.", important = false)
            GameEventType.MISSION_STARTED -> notice("미션 시작", "지도에 표시된 미션 구역으로 이동하세요.")
            GameEventType.MISSION_COMPLETED -> notice("미션 성공", (by?.let { "$it 님이 " } ?: "") + "미션을 완료했습니다.")
            GameEventType.MISSION_FAILED -> notice("미션 실패", "제한 시간이 지나 다음 구역 축소가 앞당겨집니다.")
            GameEventType.SPEED_WARNING -> notice(
                "과속 경고",
                "$target 과속 경고 ${event.data[GameEventKeys.WARNING_COUNT] ?: "-"}/${GameRules.SPEED_WARNINGS_TO_DISQUALIFY}회 (25km/h 초과)",
            )
            GameEventType.PLAYER_DISCONNECTED -> notice("연결 끊김", "$target 연결이 끊겼습니다. 3분 안에 돌아오지 않으면 무효 경기가 됩니다.", important = false)
            GameEventType.PLAYER_RECONNECTED -> notice("재접속", "$target 다시 연결되었습니다.", important = false)
            GameEventType.PLAYER_ELIMINATED -> notice("실격", "$target 실격")
            GameEventType.PLAYER_FORFEITED -> notice("기권", "$target 게임에서 나갔습니다.")
            GameEventType.GAME_FINISHED -> notice("경기 종료", if (event.data[GameEventKeys.RESULT] == "POLICE_WIN") "경찰 승리" else "도둑 승리")
            GameEventType.GAME_INVALIDATED -> notice("무효 경기", "경기가 무효로 종료되었습니다.")
            GameEventType.JAIL_CHECK_PENDING -> notice("수감 확인 중", "GPS 정확도가 낮아 수감을 확정하지 못했습니다. 하늘이 보이는 곳에서 잠시 기다리세요.", important = false)
            GameEventType.THIEF_PHONE_RING -> notice("경찰이 위치를 찾고 있습니다", "경찰이 내 휴대폰을 울렸습니다.")
            GameEventType.PLAYER_LOCATION_UPDATED -> null
        }
    }
}
