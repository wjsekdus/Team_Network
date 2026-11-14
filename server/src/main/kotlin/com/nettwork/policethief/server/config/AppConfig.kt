package com.nettwork.policethief.server.config

import com.nettwork.policethief.server.game.GameSettings

/**
 * 서버 실행 설정. 모든 값은 환경 변수에서 읽는다(소스 코드에 주소·비밀번호를 하드코딩하지 않기 위함).
 * 변수 목록과 예시는 server/.env.example 참고.
 */
data class AppConfig(
    val host: String,
    val port: Int,
    val database: DatabaseConfig,
    /** true 면 위치 source=MOCK(개발자 테스트 좌표)을 받아들이고, MOCK 좌표는 과속 판정에서 제외한다. 운영에서는 false. */
    val allowMockLocations: Boolean,
    val tokenTtlDays: Long,
    val appVersion: String,
    /** true 면 /api/dev/... 개발자 도구(봇, 서버 시간 이동)를 연다. 운영에서는 반드시 false. */
    val devToolsEnabled: Boolean = false,
    /** false 면 시간 기반 판정 스케줄러를 띄우지 않는다(테스트에서 직접 tick 을 호출할 때). */
    val schedulerEnabled: Boolean = true,
    val game: GameSettings = GameSettings(),
) {
    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): AppConfig {
            fun required(key: String): String =
                env[key] ?: throw IllegalStateException("환경 변수 $key 가 설정되지 않았습니다. server/.env.example 을 참고하세요.")

            return AppConfig(
                host = env["PNT_SERVER_HOST"] ?: "0.0.0.0",
                port = env["PNT_SERVER_PORT"]?.toIntOrNull() ?: 8080,
                database = DatabaseConfig(
                    jdbcUrl = env["PNT_DB_URL"] ?: "jdbc:postgresql://localhost:5432/police_thief",
                    user = env["PNT_DB_USER"] ?: "police_thief",
                    password = required("PNT_DB_PASSWORD"),
                    maxPoolSize = env["PNT_DB_POOL_SIZE"]?.toIntOrNull() ?: 10,
                ),
                allowMockLocations = env["PNT_ALLOW_MOCK_LOCATIONS"]?.toBooleanStrictOrNull() ?: false,
                tokenTtlDays = env["PNT_TOKEN_TTL_DAYS"]?.toLongOrNull() ?: 30,
                appVersion = env["PNT_APP_VERSION"] ?: "0.2.0",
                devToolsEnabled = env["PNT_DEV_TOOLS"]?.toBooleanStrictOrNull() ?: false,
                game = GameSettings.fromEnvironment(env),
            )
        }
    }
}

data class DatabaseConfig(
    val jdbcUrl: String,
    val user: String,
    val password: String,
    val maxPoolSize: Int,
) {
    override fun toString(): String = "DatabaseConfig(jdbcUrl=$jdbcUrl, user=$user, password=****, maxPoolSize=$maxPoolSize)"
}
