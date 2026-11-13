package com.nettwork.policethief.server.database

import com.nettwork.policethief.server.config.DatabaseConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.Closeable

/**
 * DB 연결 풀(HikariCP) + 마이그레이션(Flyway) + Exposed 연결을 묶은 객체.
 *
 * 테이블 구조는 Exposed 가 아니라 Flyway SQL(resources/db/migration)로만 변경한다.
 * 그래야 팀원 PC·CI·운영 DB 의 스키마가 항상 같은 순서로 만들어진다.
 */
class AppDatabase private constructor(
    private val dataSource: HikariDataSource,
    private val database: Database,
) : Closeable {

    /**
     * 블로킹 JDBC 호출을 IO 스레드에서 트랜잭션으로 실행한다.
     * Exposed DSL 은 파라미터 바인딩(PreparedStatement)을 사용하므로 SQL Injection 에 안전하다.
     */
    suspend fun <T> query(block: JdbcTransaction.() -> T): T =
        withContext(Dispatchers.IO) { transaction(database) { block() } }

    /** Health API 용 DB 연결 확인. */
    fun isHealthy(): Boolean = runCatching {
        dataSource.connection.use { it.isValid(2) }
    }.getOrElse { false }

    override fun close() = dataSource.close()

    companion object {
        fun connect(config: DatabaseConfig): AppDatabase {
            val hikari = HikariConfig().apply {
                jdbcUrl = config.jdbcUrl
                username = config.user
                password = config.password
                maximumPoolSize = config.maxPoolSize
                isAutoCommit = false
                transactionIsolation = "TRANSACTION_READ_COMMITTED"
                validate()
            }
            val dataSource = HikariDataSource(hikari)
            try {
                Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate()
            } catch (e: Exception) {
                dataSource.close()
                throw IllegalStateException("DB 마이그레이션 실패: ${e.message}", e)
            }
            return AppDatabase(dataSource, Database.connect(dataSource))
        }
    }
}
