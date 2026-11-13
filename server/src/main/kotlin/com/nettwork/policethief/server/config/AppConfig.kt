package com.nettwork.policethief.server.config

/**
 * 서버 실행 설정. 모든 값은 환경 변수에서 읽는다(소스 코드에 주소·비밀번호를 하드코딩하지 않기 위함).
 * 변수 목록과 예시는 server/.env.example 참고.
 */
data class AppConfig(
    val host: String,
    val port: Int,
    val database: DatabaseConfig,
    /** true 면 위치 source=MOCK(개발자 테스트 좌표)을 받아들인다. 운영에서는 false. */
    val allowMockLocations: Boolean,
    val tokenTtlDays: Long,
    val appVersion: String,
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
                appVersion = env["PNT_APP_VERSION"] ?: "0.1.0",
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
