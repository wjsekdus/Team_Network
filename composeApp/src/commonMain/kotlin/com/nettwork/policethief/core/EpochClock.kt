package com.nettwork.policethief.core

import kotlin.time.Clock

/** 현재 시각(UTC epoch millis). 테스트에서 시간을 고정할 수 있도록 인터페이스로 둔다. */
fun interface EpochClock {
    fun nowMillis(): Long
}

object SystemEpochClock : EpochClock {
    override fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
}
