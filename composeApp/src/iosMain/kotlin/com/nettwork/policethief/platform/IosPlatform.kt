package com.nettwork.policethief.platform

import com.nettwork.policethief.di.REAL_LOCATION_PROVIDER
import com.nettwork.policethief.domain.model.PlayerLocation
import com.nettwork.policethief.domain.repository.LocationRepository
import com.nettwork.policethief.shared.model.LocationSource
import com.nettwork.policethief.shared.rules.GameRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import platform.AudioToolbox.AudioServicesPlaySystemSound
import platform.AudioToolbox.kSystemSoundID_Vibrate
import platform.Foundation.NSUUID
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNUserNotificationCenter
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
    single<GameAlerts> { IosGameAlerts() }
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
 * iOS 백그라운드 위치: Info.plist UIBackgroundModes=location + allowsBackgroundLocationUpdates 로
 * 앱이 백그라운드로 가도 위치 업데이트를 계속 받고, 약 15초마다 서버로 보낸다.
 * ⚠️ macOS 에서 검증 필요. iOS 는 앱을 일시 중단·종료할 수 있어 15초 주기가 보장되지 않는다.
 * "앱 사용 중 허용"만 받은 경우 백그라운드 전환 시 상태 표시줄에 위치 사용 표시가 나타난다.
 */
@OptIn(ExperimentalForeignApi::class)
class IosBackgroundLocationController : BackgroundLocationController, KoinComponent {
    private val locations: LocationRepository by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val running = MutableStateFlow(false)
    override val isSupported: Boolean = true
    override val isRunning: StateFlow<Boolean> = running

    private var manager: CLLocationManager? = null
    private var lastSentAtMillis = 0L
    private val delegate = object : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            val location = didUpdateLocations.lastOrNull() as? CLLocation ?: return
            val now = (platform.Foundation.NSDate().timeIntervalSince1970 * 1000).toLong()
            if (now - lastSentAtMillis < GameRules.LOCATION_UPDATE_INTERVAL_MILLIS) return
            lastSentAtMillis = now
            val sample = location.coordinate.useContents {
                PlayerLocation(null, latitude, longitude, location.horizontalAccuracy, (location.timestamp.timeIntervalSince1970 * 1000).toLong(), LocationSource.GPS)
            }
            scope.launch { locations.sendLocation(sample) }
        }
    }

    override fun start() {
        if (manager != null) return
        val status = CLLocationManager().authorizationStatus
        if (status != kCLAuthorizationStatusAuthorizedWhenInUse && status != kCLAuthorizationStatusAuthorizedAlways) return
        manager = CLLocationManager().apply {
            this.delegate = this@IosBackgroundLocationController.delegate
            desiredAccuracy = kCLLocationAccuracyBest
            distanceFilter = 5.0
            allowsBackgroundLocationUpdates = true
            pausesLocationUpdatesAutomatically = false
            showsBackgroundLocationIndicator = true
            startUpdatingLocation()
        }
        running.value = true
    }

    override fun stop() {
        manager?.stopUpdatingLocation()
        manager?.delegate = null
        manager = null
        running.value = false
    }
}

/**
 * iOS 경기 알림: 로컬 알림(UNUserNotificationCenter) + 시스템 진동.
 * 앱이 화면에 떠 있을 때는 iOS 기본 동작상 배너가 뜨지 않으므로 앱 안 배너(NoticeHost)로 보인다. ⚠️ macOS 에서 검증 필요.
 */
class IosGameAlerts : GameAlerts {
    override fun notify(title: String, message: String) {
        val content = UNMutableNotificationContent().apply {
            setTitle(title)
            setBody(message)
        }
        val request = UNNotificationRequest.requestWithIdentifier(NSUUID().UUIDString, content, null)
        UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request, withCompletionHandler = null)
    }

    override fun vibrate() {
        AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
    }
}
