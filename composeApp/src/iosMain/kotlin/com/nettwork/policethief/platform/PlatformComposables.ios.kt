package com.nettwork.policethief.platform

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.requestAccessForMediaType
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/** ⚠️ macOS 에서 검증 필요. 위치는 "앱 사용 중 허용"(WhenInUse)만 요청한다. */
@Composable
actual fun rememberPermissionRequester(permission: AppPermission, onResult: (granted: Boolean) -> Unit): () -> Unit {
    val locationManager = remember { CLLocationManager() }
    val delegate = remember {
        object : NSObject(), CLLocationManagerDelegateProtocol {
            override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
                val status = manager.authorizationStatus
                if (status != kCLAuthorizationStatusNotDetermined) {
                    onResult(status == kCLAuthorizationStatusAuthorizedWhenInUse || status == kCLAuthorizationStatusAuthorizedAlways)
                }
            }
        }
    }
    return remember(permission) {
        {
            when (permission) {
                AppPermission.LOCATION -> {
                    locationManager.delegate = delegate
                    locationManager.requestWhenInUseAuthorization()
                }
                AppPermission.CAMERA -> AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
                    dispatch_async(dispatch_get_main_queue()) { onResult(granted) }
                }
                AppPermission.NOTIFICATION -> UNUserNotificationCenter.currentNotificationCenter()
                    .requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound) { granted, _ ->
                        dispatch_async(dispatch_get_main_queue()) { onResult(granted) }
                    }
            }
        }
    }
}

/** TODO(iOS QR): CoreImage CIQRCodeGenerator 로 실제 QR 이미지 생성. 현재는 토큰 문자열만 보여 준다. */
@Composable
actual fun QrCodeImage(content: String, contentDescription: String, modifier: Modifier) {
    Box(modifier.background(Color.White).semantics { this.contentDescription = contentDescription }, contentAlignment = Alignment.Center) {
        Text("iOS QR 렌더링 미구현\n$content", color = Color.Black, textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
    }
}

/** iOS 는 시스템 뒤로가기 버튼이 없다. 화면의 [나가기] 버튼으로 D-02 를 연다. */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
