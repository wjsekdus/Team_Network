package com.nettwork.policethief.config

/**
 * 앱 실행 설정. 플랫폼 진입점(Android: BuildConfig, iOS: Info.plist)이 만들어 Koin 에 넣는다.
 *
 * 서버 주소를 소스에 하드코딩하지 않기 위해 빌드 설정에서 주입한다.
 * - Android: local.properties 의 pnt.serverUrl (androidApp/build.gradle.kts)
 * - iOS: iosApp/Configuration/Config.xcconfig 의 SERVER_BASE_URL
 * 개발 빌드에서는 설정 화면에서 주소를 바꿀 수 있다(Cloudflare 임시 URL 이 바뀌어도 재빌드 불필요).
 */
data class AppConfig(
    val defaultServerUrl: String,
    /** 개발 빌드 여부. true 일 때만 테스트 좌표, Mock 게임 조작, 서버 주소 변경 메뉴가 보인다. */
    val isDevBuild: Boolean,
    val appVersion: String,
    val platformName: String,
)
