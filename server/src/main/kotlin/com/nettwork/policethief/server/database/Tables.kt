package com.nettwork.policethief.server.database

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/*
 * Exposed 테이블 매핑. 실제 스키마는 resources/db/migration/V1__initial_schema.sql + V2__game_progress.sql 이 기준이며,
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

// ---- 경기 진행 (V1 + V2 마이그레이션) ----

object GamesTable : Table("games") {
    val id = javaUUID("id")
    val status = varchar("status", 16)
    val version = long("version")
    val centerLatitude = double("center_latitude")
    val centerLongitude = double("center_longitude")
    val initialRadiusMeters = double("initial_radius_m")
    val currentRadiusMeters = double("current_radius_m")
    val jailRadiusMeters = double("jail_radius_m")
    val createdAt = timestampWithTimeZone("created_at")
    val startsAt = timestampWithTimeZone("starts_at").nullable()
    val startedAt = timestampWithTimeZone("started_at").nullable()
    val endsAt = timestampWithTimeZone("ends_at").nullable()
    val endedAt = timestampWithTimeZone("ended_at").nullable()
    val result = varchar("result", 16).nullable()
    val endReason = varchar("end_reason", 40).nullable()
    val nextMissionAt = timestampWithTimeZone("next_mission_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object GamePlayersTable : Table("game_players") {
    val gameId = javaUUID("game_id").references(GamesTable.id)
    val userId = javaUUID("user_id").references(UsersTable.id)
    val role = varchar("role", 8)
    val status = varchar("status", 16)
    val capturedBy = javaUUID("captured_by").nullable()
    val escortingUserId = javaUUID("escorting_user_id").nullable()
    val warningCount = integer("warning_count")
    val connectionState = varchar("connection_state", 16)
    val disconnectedAt = timestampWithTimeZone("disconnected_at").nullable()
    val outOfZoneSince = timestampWithTimeZone("out_of_zone_since").nullable()
    val speedCandidateCount = integer("speed_candidate_count")
    val lastSpeedWarningAt = timestampWithTimeZone("last_speed_warning_at").nullable()
    val lastRingAt = timestampWithTimeZone("last_ring_at").nullable()
    val joinedAt = timestampWithTimeZone("joined_at")
    override val primaryKey = PrimaryKey(gameId, userId)
}

object ActiveParticipationsTable : Table("active_participations") {
    val userId = javaUUID("user_id").references(UsersTable.id)
    val gameId = javaUUID("game_id").references(GamesTable.id)
    val joinedAt = timestampWithTimeZone("joined_at")
    override val primaryKey = PrimaryKey(userId)
}

object MatchmakingQueueTable : Table("matchmaking_queue") {
    val userId = javaUUID("user_id").references(UsersTable.id)
    val latitude = double("latitude")
    val longitude = double("longitude")
    val accuracyMeters = double("accuracy_m")
    val locationSource = varchar("source", 8)
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
    val gameVersion = long("game_version")
    val visibility = varchar("visibility", 8)
    val targetUserId = javaUUID("target_user_id").nullable()
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

object EscortsTable : Table("escorts") {
    val id = long("id").autoIncrement()
    val gameId = javaUUID("game_id").references(GamesTable.id)
    val policeId = javaUUID("police_id").references(UsersTable.id)
    val thiefId = javaUUID("thief_id").references(UsersTable.id)
    val startedAt = timestampWithTimeZone("started_at")
    val endedAt = timestampWithTimeZone("ended_at").nullable()
    val endReason = varchar("end_reason", 16).nullable()
    override val primaryKey = PrimaryKey(id)
}

object GameZoneStagesTable : Table("game_zone_stages") {
    val gameId = javaUUID("game_id").references(GamesTable.id)
    val stageNo = integer("stage_no")
    val scheduledAt = timestampWithTimeZone("scheduled_at")
    val radiusMeters = double("radius_m")
    val warnedAt = timestampWithTimeZone("warned_at").nullable()
    val appliedAt = timestampWithTimeZone("applied_at").nullable()
    override val primaryKey = PrimaryKey(gameId, stageNo)
}

object MissionsTable : Table("missions") {
    val id = javaUUID("id")
    val gameId = javaUUID("game_id").references(GamesTable.id)
    val targetLatitude = double("target_latitude")
    val targetLongitude = double("target_longitude")
    val targetRadiusMeters = double("target_radius_m")
    val startedAt = timestampWithTimeZone("started_at")
    val deadlineAt = timestampWithTimeZone("deadline_at")
    val status = varchar("status", 16)
    val completedBy = javaUUID("completed_by").nullable()
    val endedAt = timestampWithTimeZone("ended_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object GameResultsTable : Table("game_results") {
    val gameId = javaUUID("game_id").references(GamesTable.id)
    val result = varchar("result", 16)
    val endReason = varchar("end_reason", 40)
    val decidedAt = timestampWithTimeZone("decided_at")
    val decidedVersion = long("decided_version")
    override val primaryKey = PrimaryKey(gameId)
}

object ScanRequestsTable : Table("scan_requests") {
    val userId = javaUUID("user_id").references(UsersTable.id)
    val requestId = varchar("request_id", 64)
    val gameId = javaUUID("game_id").references(GamesTable.id)
    val kind = varchar("kind", 8)
    val resultJson = text("result_json")
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(userId, requestId)
}
