rootProject.name = "PoliceAndThief"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

// shared     : 서버와 앱이 함께 쓰는 API 계약(DTO), 게임 규칙 상수, 순수 Kotlin 검증 로직
// composeApp : Android/iOS 공용 앱 코드(Compose UI, ViewModel, Domain, Data, Platform)
// androidApp : Android 실행용 껍데기(Activity, Manifest, BuildConfig)
// server     : Ktor 서버
include(":shared")
include(":composeApp")
include(":androidApp")
include(":server")
