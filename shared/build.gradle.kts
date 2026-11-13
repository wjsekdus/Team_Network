import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * shared 모듈: 서버(JVM)와 앱(Android/iOS)이 "같은 코드"로 API 계약을 공유한다.
 * - dto     : JSON 으로 오가는 요청/응답 (kotlinx.serialization)
 * - model   : 상태 enum 등 양쪽이 동일하게 이해해야 하는 어휘
 * - rules   : 게임 규칙 상수와 입력 검증 (순수 Kotlin, 플랫폼 의존 없음)
 * - geo     : 거리/중심 계산 같은 위치 계산
 * 이 모듈에는 UI, DB, 네트워크 라이브러리를 넣지 않는다.
 */
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinxSerialization)
}

kotlin {
    jvm()

    android {
        namespace = "com.nettwork.policethief.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
