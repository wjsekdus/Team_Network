plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinxSerialization)
    application
}

group = "com.nettwork.policethief"
version = "0.1.0"

application {
    mainClass.set("com.nettwork.policethief.server.ApplicationKt")
    // Windows 콘솔에서 한글 로그가 깨지지 않도록 출력 인코딩을 UTF-8 로 고정한다.
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

dependencies {
    implementation(projects.shared)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.default.headers)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.postgresql)
    implementation(libs.hikari)
    implementation(libs.logback.classic)
    // PostgreSQL 이 없는 PC 에서 빠르게 실행해 보기 위한 선택 옵션(PNT_DB_URL=jdbc:h2:...). 테스트에서도 사용.
    implementation(libs.h2)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(libs.embedded.postgres)
}

tasks.test {
    useJUnitPlatform()
}

/*
 * `gradlew :server:run` 실행 시 server/.env 파일이 있으면 그 값을 환경 변수로 넣어 준다.
 * .env 는 비밀번호가 들어가므로 Git 에 커밋하지 않는다(.gitignore). 예시는 server/.env.example.
 * 이미 OS 에 설정된 환경 변수가 있으면 그 값이 우선한다.
 */
val dotEnv: Map<String, String> = file(".env").takeIf { it.exists() }?.readLines()
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
    ?.associate { line -> line.substringBefore("=").trim() to line.substringAfter("=").trim() }
    ?: emptyMap()

tasks.named<JavaExec>("run") {
    dotEnv.forEach { (key, value) -> if (System.getenv(key) == null) environment(key, value) }
}
