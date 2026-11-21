package com.nettwork.policethief

import androidx.compose.ui.window.ComposeUIViewController
import com.nettwork.policethief.config.AppConfig
import com.nettwork.policethief.di.initKoin
import com.nettwork.policethief.platform.iosPlatformModule
import platform.Foundation.NSBundle
import platform.UIKit.UIDevice
import platform.UIKit.UIViewController
import kotlin.experimental.ExperimentalNativeApi

/*
 * ⚠️ iOS 코드는 macOS + Xcode 가 없는 환경에서 작성되어 컴파일·실행이 검증되지 않았다(docs/testing/TEST_REPORT.md).
 */

/**
 * iOS 앱 시작 시 Swift(iOSApp.swift)에서 1회 호출한다.
 * 서버 주소는 Info.plist 의 SERVER_BASE_URL(= Config.xcconfig 값)에서 읽는다.
 */
@OptIn(ExperimentalNativeApi::class)
fun startKoinForIos() {
    val serverUrl = NSBundle.mainBundle.objectForInfoDictionaryKey("SERVER_BASE_URL") as? String
    val version = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String
    initKoin(
        config = AppConfig(
            defaultServerUrl = serverUrl?.takeIf { it.isNotBlank() } ?: error("Info.plist 에 SERVER_BASE_URL 이 없습니다. Config.xcconfig 를 확인하세요."),
            isDevBuild = Platform.isDebugBinary,
            appVersion = version ?: "0.1.0",
            platformName = "iOS ${UIDevice.currentDevice.systemVersion}",
        ),
        platformModule = iosPlatformModule(),
    )
}

/** SwiftUI 에서 Compose 화면을 띄우는 진입점. */
fun MainViewController(): UIViewController = ComposeUIViewController { App() }
