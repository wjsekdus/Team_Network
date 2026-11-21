package com.nettwork.policethief.server.auth

import com.nettwork.policethief.server.common.toUtcOffsetDateTime
import com.nettwork.policethief.server.database.AppDatabase
import com.nettwork.policethief.server.database.AuthTokensTable
import com.nettwork.policethief.server.database.UsersTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID

/** users 테이블 한 행. */
data class UserRecord(
    val id: UUID,
    val nickname: String,
    val createdAt: Instant,
    val lastActiveAt: Instant,
)

/** 사용자 저장소. Service 는 이 인터페이스만 알고, 테스트에서는 실제 DB(H2/PostgreSQL)로 검증한다. */
interface UserRepository {
    suspend fun create(nickname: String, now: Instant): UserRecord
    suspend fun findById(id: UUID): UserRecord?
    suspend fun touchLastActive(id: UUID, now: Instant)
}

/** 인증 토큰 저장소. 토큰 원문이 아니라 해시만 다룬다. */
interface AuthTokenRepository {
    suspend fun save(tokenHash: String, userId: UUID, createdAt: Instant, expiresAt: Instant)

    /** 만료·폐기되지 않은 토큰이면 소유자 userId, 아니면 null. */
    suspend fun findActiveUserId(tokenHash: String, now: Instant): UUID?
}

class ExposedUserRepository(private val db: AppDatabase) : UserRepository {
    override suspend fun create(nickname: String, now: Instant): UserRecord = db.query {
        val id = UUID.randomUUID()
        UsersTable.insert {
            it[UsersTable.id] = id
            it[UsersTable.nickname] = nickname
            it[createdAt] = now.toUtcOffsetDateTime()
            it[lastActiveAt] = now.toUtcOffsetDateTime()
        }
        UserRecord(id, nickname, now, now)
    }

    override suspend fun findById(id: UUID): UserRecord? = db.query {
        UsersTable.selectAll().where { UsersTable.id eq id }.singleOrNull()?.let { row ->
            UserRecord(
                id = row[UsersTable.id],
                nickname = row[UsersTable.nickname],
                createdAt = row[UsersTable.createdAt].toInstant(),
                lastActiveAt = row[UsersTable.lastActiveAt].toInstant(),
            )
        }
    }

    override suspend fun touchLastActive(id: UUID, now: Instant) {
        db.query {
            UsersTable.update({ UsersTable.id eq id }) { it[lastActiveAt] = now.toUtcOffsetDateTime() }
        }
    }
}

class ExposedAuthTokenRepository(private val db: AppDatabase) : AuthTokenRepository {
    override suspend fun save(tokenHash: String, userId: UUID, createdAt: Instant, expiresAt: Instant) {
        db.query {
            AuthTokensTable.insert {
                it[AuthTokensTable.tokenHash] = tokenHash
                it[AuthTokensTable.userId] = userId
                it[AuthTokensTable.createdAt] = createdAt.toUtcOffsetDateTime()
                it[AuthTokensTable.expiresAt] = expiresAt.toUtcOffsetDateTime()
            }
        }
    }

    override suspend fun findActiveUserId(tokenHash: String, now: Instant): UUID? = db.query {
        AuthTokensTable.selectAll()
            .where {
                (AuthTokensTable.tokenHash eq tokenHash) and
                    AuthTokensTable.revokedAt.isNull() and
                    (AuthTokensTable.expiresAt greater now.toUtcOffsetDateTime())
            }
            .singleOrNull()
            ?.get(AuthTokensTable.userId)
    }
}
