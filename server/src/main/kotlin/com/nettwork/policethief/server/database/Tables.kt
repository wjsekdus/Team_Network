package com.nettwork.policethief.server.database

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/*
 * Exposed 테이블 매핑. 실제 스키마는 resources/db/migration/V1__initial_schema.sql 이 기준이며,
 * 여기서는 Kotlin 코드에서 컬럼을 타입 안전하게 쓰기 위한 매핑만 정의한다(SchemaUtils.create 사용 금지).
 * enum 값은 문자열(VARCHAR)로 저장하고 Repository 에서 변환한다.
 */

object UsersTable : Table("users") {
    val id = javaUUID("id")
    val nickname = varchar("nickname", 20)
    val createdAt = timestampWithTimeZone("created_at")
    val lastActiveAt = timestampWithTimeZone("last_active_at")
    override val primaryKey = PrimaryKey(id)
}

object AuthTokensTable : Table("auth_tokens") {
    val tokenHash = char("token_hash", 64)
    val userId = javaUUID("user_id").references(UsersTable.id)
    val createdAt = timestampWithTimeZone("created_at")
    val expiresAt = timestampWithTimeZone("expires_at")
    val revokedAt = timestampWithTimeZone("revoked_at").nullable()
    override val primaryKey = PrimaryKey(tokenHash)
}

object PlayerLocationsTable : Table("player_locations") {
    val id = long("id").autoIncrement()
    val userId = javaUUID("user_id").references(UsersTable.id)
    val gameId = javaUUID("game_id").nullable()
    val latitude = double("latitude")
    val longitude = double("longitude")
    val accuracyMeters = double("accuracy_m")
    // Kotlin 속성명 `source` 는 Exposed ColumnSet 의 멤버와 충돌하므로 locationSource 로 쓴다(DB 컬럼명은 source).
    val locationSource = varchar("source", 8)
    val measuredAt = timestampWithTimeZone("measured_at")
    val receivedAt = timestampWithTimeZone("received_at")
    override val primaryKey = PrimaryKey(id)
}

// ---- 아래 테이블은 스키마만 준비됨. 사용하는 Repository 는 향후 구현(docs/development/TODO.md) ----

object GamesTable : Table("games") {
    val id = javaUUID("id")
    val status = varchar("status", 16)
    val centerLatitude = double("center_latitude")
    val centerLongitude = double("center_longitude")
    val currentRadiusMeters = double("current_radius_m")
    val jailRadiusMeters = double("jail_radius_m")
    val createdAt = timestampWithTimeZone("created_at")
    val startsAt = timestampWithTimeZone("starts_at").nullable()
    val startedAt = timestampWithTimeZone("started_at").nullable()
    val endsAt = timestampWithTimeZone("ends_at").nullable()
    val endedAt = timestampWithTimeZone("ended_at").nullable()
    val result = varchar("result", 16).nullable()
    val endReason = varchar("end_reason", 40).nullable()
    override val primaryKey = PrimaryKey(id)
}

object GamePlayersTable : Table("game_players") {
    val gameId = javaUUID("game_id").references(GamesTable.id)
    val userId = javaUUID("user_id").references(UsersTable.id)
    val role = varchar("role", 8)
    val status = varchar("status", 16)
    val capturedBy = javaUUID("captured_by").nullable()
    val warningCount = integer("warning_count")
    val connectionState = varchar("connection_state", 16)
    val disconnectedAt = timestampWithTimeZone("disconnected_at").nullable()
    val joinedAt = timestampWithTimeZone("joined_at")
    override val primaryKey = PrimaryKey(gameId, userId)
}

object MatchmakingQueueTable : Table("matchmaking_queue") {
    val userId = javaUUID("user_id").references(UsersTable.id)
    val latitude = double("latitude")
    val longitude = double("longitude")
    val accuracyMeters = double("accuracy_m")
    val status = varchar("status", 16)
    val gameId = javaUUID("game_id").nullable()
    val joinedAt = timestampWithTimeZone("joined_at")
    val expiresAt = timestampWithTimeZone("expires_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(userId)
}

object GameEventsTable : Table("game_events") {
    val id = long("id").autoIncrement()
    val gameId = javaUUID("game_id").references(GamesTable.id)
    val eventType = varchar("event_type", 32)
    val eventData = text("event_data")
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(id)
}

object QrTokensTable : Table("qr_tokens") {
    val id = javaUUID("id")
    val gameId = javaUUID("game_id").references(GamesTable.id)
    val userId = javaUUID("user_id").references(UsersTable.id)
    val token = varchar("token", 64)
    val createdAt = timestampWithTimeZone("created_at")
    val revokedAt = timestampWithTimeZone("revoked_at").nullable()
    override val primaryKey = PrimaryKey(id)
}
