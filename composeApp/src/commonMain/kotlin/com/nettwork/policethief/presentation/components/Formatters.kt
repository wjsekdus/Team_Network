package com.nettwork.policethief.presentation.components

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToLong
import kotlin.time.Instant

/** epoch millis → 기기 시간대의 "HH:mm:ss". */
fun formatClockTime(epochMillis: Long): String {
    val time = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault())
    return "${time.hour.pad()}:${time.minute.pad()}:${time.second.pad()}"
}

/** 남은 시간 "mm:ss". 음수는 0 으로. */
fun formatRemaining(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0) / 1000)
    return "${(totalSeconds / 60).toInt().pad()}:${(totalSeconds % 60).toInt().pad()}"
}

/** 좌표를 소수점 5자리(약 1m) 문자열로. */
fun formatCoordinate(value: Double): String {
    val scaled = (value * 100_000).roundToLong()
    val sign = if (scaled < 0) "-" else ""
    val abs = kotlin.math.abs(scaled)
    return "$sign${abs / 100_000}.${(abs % 100_000).toString().padStart(5, '0')}"
}

fun formatMeters(value: Double): String = if (value >= 1000) "${(value / 100).roundToLong() / 10.0}km" else "${value.roundToLong()}m"

private fun Int.pad() = toString().padStart(2, '0')
