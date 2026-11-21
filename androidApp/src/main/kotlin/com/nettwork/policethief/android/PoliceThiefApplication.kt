package com.nettwork.policethief.android

import android.app.Application
import android.os.Build
import com.nettwork.policethief.config.AppConfig
import com.nettwork.policethief.di.initKoin
import com.nettwork.policethief.platform.androidPlatformModule

/** 앱 프로세스 시작 시 1회: 빌드 설정(BuildConfig)으로 AppConfig 를 만들고 Koin 을 시작한다. */
class PoliceThiefApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin(
            config = AppConfig(
                defaultServerUrl = BuildConfig.SERVER_URL,
                isDevBuild = BuildConfig.DEBUG,
                appVersion = BuildConfig.VERSION_NAME,
                platformName = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            ),
            platformModule = androidPlatformModule(this),
        )
    }
}
