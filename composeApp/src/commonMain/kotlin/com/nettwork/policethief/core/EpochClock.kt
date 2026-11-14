package com.nettwork.policethief.core

import kotlin.time.Clock

/** 현재 시각(UTC epoch millis). 테스트에서 시간을 고정할 수 있도록 인터페이스로 둔다. */
fun interface EpochClock {
    fun nowMillis(): Long
}

object SystemEpochClock : EpochClock {
    override fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
}

/**
 * 서버 시계 추정. 서버 응답의 serverTimeEpochMillis 와 받은 순간의 단말 시각 차이(offset)를 기억해
 * "지금 서버 시각"을 계산한다. 남은 시간·준비 카운트다운은 이 시계로 표시하므로
 * 사용자가 단말 시간을 바꿔도 화면 표시만 잠시 틀릴 뿐 실제 시작·종료(서버 판정)는 바뀌지 않는다.
 */
class ServerClock(private val device: EpochClock) : EpochClock {
    private var offsetMillis: Long = 0
    var isSynced: Boolean = false
        private set

    fun update(serverTimeEpochMillis: Long) {
        if (serverTimeEpochMillis <= 0) return
        offsetMillis = serverTimeEpochMillis - device.nowMillis()
        isSynced = true
    }

    override fun nowMillis(): Long = device.nowMillis() + offsetMillis
}
