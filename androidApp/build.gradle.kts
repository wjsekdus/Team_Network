import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

/*
 * 서버 주소 설정(소스 코드 하드코딩 금지).
 * 우선순위: 환경 변수 PNT_SERVER_URL > local.properties 의 pnt.serverUrl > 에뮬레이터 기본값
 * - 에뮬레이터에서 PC 의 서버: http://10.0.2.2:8080 (기본값)
 * - 같은 Wi-Fi 실기기: http://<PC의 IP>:8080
 * - 외부 네트워크: https://<임시주소>.trycloudflare.com (Cloudflare Tunnel)
 * 개발(debug) 빌드는 앱 설정 화면에서도 주소를 바꿀 수 있다.
 */
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val serverUrl: String = System.getenv("PNT_SERVER_URL")
    ?: localProperties.getProperty("pnt.serverUrl")
    ?: "http://10.0.2.2:8080"

android {
    namespace = "com.nettwork.policethief.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.nettwork.policethief"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
    }
    buildFeatures {
        buildConfig = true
    }
    buildTypes {
        getByName("debug") {
            // 로컬 개발 서버(http://)를 허용. 외부 접속은 Cloudflare Tunnel 의 https 를 사용한다.
            manifestPlaceholders["usesCleartextTraffic"] = "true"
        }
        getByName("release") {
            isMinifyEnabled = false
            manifestPlaceholders["usesCleartextTraffic"] = "false"
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/io.netty.versions.properties"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

dependencies {
    implementation(projects.composeApp)
    implementation(projects.shared)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.foundation)
    implementation(libs.compose.uiToolingPreview)
    implementation(libs.koin.core)

    // Robolectric 기반 UI 흐름 테스트(에뮬레이터 없이 JVM 에서 실행): gradlew :androidApp:testDebugUnitTest
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.multiplatform.settings)
    testImplementation(libs.multiplatform.settings.test)
    testImplementation(libs.kotlinx.coroutines.test)
    // 앱의 실제 API/WebSocket 클라이언트 코드를 실제 서버 모듈에 붙여 보는 통합 테스트용
    testImplementation(projects.server)
    testImplementation(libs.ktor.server.test.host)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
