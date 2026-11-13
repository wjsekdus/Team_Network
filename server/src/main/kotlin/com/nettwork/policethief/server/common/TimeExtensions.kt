package com.nettwork.policethief.server.common

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** DB 에는 TIMESTAMP WITH TIME ZONE(UTC), API 에는 epoch millis 로 주고받는다. */
fun OffsetDateTime.toEpochMillis(): Long = toInstant().toEpochMilli()

fun Instant.toUtcOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)

fun Long.epochMillisToUtc(): OffsetDateTime = Instant.ofEpochMilli(this).toUtcOffsetDateTime()
