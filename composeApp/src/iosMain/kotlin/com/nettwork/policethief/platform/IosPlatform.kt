package com.nettwork.policethief.platform

import com.nettwork.policethief.di.REAL_LOCATION_PROVIDER
import com.nettwork.policethief.domain.model.PlayerLocation
import com.nettwork.policethief.shared.model.LocationSource
import com.russhwolf.settings.ExperimentalSettingsImplementation
import com.russhwolf.settings.KeychainSettings
import com.russhwolf.settings.NSUserDefaultsSettings
import com.russhwolf.settings.Settings
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.dsl.module
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.darwin.NSObject

/** iOS 플랫폼 구현 등록. ⚠️ macOS 환경에서 빌드·실행 검증 필요. */
@OptIn(ExperimentalSettingsImplementation::class)
fun iosPlatformModule() = module {
    single<HttpClientEngine> { Darwin.create() }
    single<Settings> { NSUserDefaultsSettings(NSUserDefaults.standardUserDefaults) }
    single<SecureTokenStorage> { KeychainTokenStorage(KeychainSettings(service = "com.nettwork.policethief.auth")) }
    single<PermissionController> { IosPermissionController() }
    single<LocationProvider>(REAL_LOCATION_PROVIDER) { RealLocationProvider() }
    single<BackgroundLocationController> { IosBackgroundLocationController() }
}

/** Keychain 기반 토큰 저장소(multiplatform-settings 의 KeychainSettings 사용). */
class KeychainTokenStorage(private val keychain: Settings) : SecureTokenStorage {
    override fun readToken(): String? = keychain.getStringOrNull(KEY)
    override fun writeToken(token: String) = keychain.putString(KEY, token)
    override fun clear() = keychain.remove(KEY)

    private companion object {
        const val KEY = "auth_token"
    }
}

class IosPermissionController : PermissionController {
    override fun status(permission: AppPermission): PermissionState = when (permission) {
        AppPermission.LOCATION -> when (CLLocationManager().authorizationStatus) {
            kCLAuthorizationStatusAuthorizedWhenInUse, kCLAuthorizationStatusAuthorizedAlways -> PermissionState.GRANTED
            kCLAuthorizationStatusNotDetermined -> PermissionState.NOT_DETERMINED
            else -> PermissionState.DENIED
        }
        AppPermission.CAMERA -> when (AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)) {
            AVAuthorizationStatusAuthorized -> PermissionState.GRANTED
            AVAuthorizationStatusNotDetermined -> PermissionState.NOT_DETERMINED
            else -> PermissionState.DENIED
        }
        // 알림 권한은 비동기 API 로만 조회할 수 있어 동기 상태는 "미확인"으로 둔다. TODO(Notification)
        AppPermission.NOTIFICATION -> PermissionState.NOT_DETERMINED
    }

    override fun openAppSettings() {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
    }
}

/**
 * Core Location 1회 위치 요청(requestLocation). CLLocationManager 는 메인 스레드에서 다룬다.
 */
@OptIn(ExperimentalForeignApi::class)
class RealLocationProvider : LocationProvider {
    override suspend fun getCurrentLocation(): PlayerLocation = withContext(Dispatchers.Main) {
        if (!CLLocationManager.locationServicesEnabled()) throw LocationException.ServiceDisabled()
        val manager = CLLocationManager()
        val status = manager.authorizationStatus
        if (status != kCLAuthorizationStatusAuthorizedWhenInUse && status != kCLAuthorizationStatusAuthorizedAlways) {
            throw LocationException.PermissionDenied()
        }
        val result = CompletableDeferred<CLLocation>()
        val delegate = OneShotLocationDelegate(result)
        manager.delegate = delegate
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.requestLocation()
        val location = withTimeoutOrNull(20_000) { result.await() } ?: throw LocationException.Timeout()
        manager.delegate = null
        location.coordinate.useContents {
            PlayerLocation(
                userId = null,
                latitude = latitude,
                longitude = longitude,
                accuracyMeters = location.horizontalAccuracy,
                timestampEpochMillis = (location.timestamp.timeIntervalSince1970 * 1000).toLong(),
                source = LocationSource.GPS,
            )
        }
    }
}

private class OneShotLocationDelegate(private val result: CompletableDeferred<CLLocation>) : NSObject(), CLLocationManagerDelegateProtocol {
    override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
        (didUpdateLocations.lastOrNull() as? CLLocation)?.let { result.complete(it) }
    }

    override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
        result.completeExceptionally(LocationException.Unavailable(didFailWithError.localizedDescription))
    }
}

/**
 * TODO(iOS 백그라운드 위치): Info.plist UIBackgroundModes=location +
 * CLLocationManager.allowsBackgroundLocationUpdates / startUpdatingLocation 으로 구현 예정.
 * iOS 는 앱이 일시 중단되면 주기 실행이 보장되지 않는다. 구현 전까지 미지원으로 표시한다.
 */
class IosBackgroundLocationController : BackgroundLocationController {
    override val isSupported: Boolean = false
    override val isRunning: StateFlow<Boolean> = MutableStateFlow(false)
    override fun start() = Unit
    override fun stop() = Unit
}
