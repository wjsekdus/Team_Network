package com.nettwork.policethief.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/*
 * 화면(Compose)에서만 쓸 수 있는 플랫폼 기능. ViewModel 은 이 함수들을 호출하지 않는다.
 */

/**
 * 권한 요청 팝업을 띄우는 함수를 기억해 돌려준다. 결과는 [onResult] 로 받는다.
 * Android: ActivityResult API, iOS: CLLocationManager / AVCaptureDevice 요청.
 */
@Composable
expect fun rememberPermissionRequester(permission: AppPermission, onResult: (granted: Boolean) -> Unit): () -> Unit

/** QR 코드 이미지. Android: ZXing 으로 생성. iOS: CoreImage CIQRCodeGenerator. */
@Composable
expect fun QrCodeImage(content: String, contentDescription: String, modifier: Modifier)

/**
 * 카메라 미리보기 + QR 인식. 카메라 권한이 있을 때만 호출한다.
 * 인식할 때마다 [onScanned] 를 부르므로, 중복 요청은 호출하는 쪽(ViewModel)에서 막는다.
 * Android: CameraX + ZXing, iOS: AVFoundation(AVCaptureMetadataOutput).
 */
@Composable
expect fun QrScannerView(onScanned: (String) -> Unit, modifier: Modifier)

/** 시스템 뒤로가기 가로채기(D-01, D-02 확인 대화상자용). */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
