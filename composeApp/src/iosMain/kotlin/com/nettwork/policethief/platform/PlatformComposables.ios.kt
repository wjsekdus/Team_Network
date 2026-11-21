package com.nettwork.policethief.platform

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMetadataMachineReadableCodeObject
import platform.AVFoundation.AVMetadataObjectTypeQRCode
import platform.CoreGraphics.CGAffineTransformMakeScale
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIFilter
import platform.CoreImage.filterWithName
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.setValue
import platform.QuartzCore.CALayer
import platform.QuartzCore.kCAFilterNearest
import platform.UIKit.UIColor
import platform.UIKit.UIImage
import platform.UIKit.UIImageView
import platform.UIKit.UIView
import platform.UIKit.UIViewContentMode
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_get_global_queue
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

/** CoreImage CIQRCodeGenerator 로 QR 이미지를 만들어 UIImageView 로 보여 준다(확대 시 흐려지지 않게 nearest 필터). ⚠️ macOS 검증 필요. */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun QrCodeImage(content: String, contentDescription: String, modifier: Modifier) {
    val image = remember(content) { qrImage(content) }
    Box(modifier.background(Color.White).semantics { this.contentDescription = contentDescription }, contentAlignment = Alignment.Center) {
        if (image == null) {
            Text("QR 을 만들지 못했습니다\n$content", color = Color.Black, textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
        } else {
            UIKitView(
                factory = {
                    UIImageView(image).apply {
                        contentMode = UIViewContentMode.UIViewContentModeScaleAspectFit
                        layer.magnificationFilter = kCAFilterNearest
                        backgroundColor = UIColor.whiteColor
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun qrImage(content: String): UIImage? {
    val filter = CIFilter.filterWithName("CIQRCodeGenerator") ?: return null
    val data = NSString.create(string = content).dataUsingEncoding(NSUTF8StringEncoding) ?: return null
    filter.setValue(data, forKey = "inputMessage")
    filter.setValue("M", forKey = "inputCorrectionLevel")
    val output = filter.outputImage ?: return null
    return UIImage.imageWithCIImage(output.imageByApplyingTransform(CGAffineTransformMakeScale(12.0, 12.0)))
}

/**
 * AVFoundation QR 스캐너(AVCaptureSession + AVCaptureMetadataOutput). 카메라 권한이 있을 때만 호출된다.
 * ⚠️ macOS + 실제 iPhone 에서 검증 필요(시뮬레이터에는 카메라가 없다).
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun QrScannerView(onScanned: (String) -> Unit, modifier: Modifier) {
    val currentOnScanned by rememberUpdatedState(onScanned)
    val scanner = remember { IosQrScanner { currentOnScanned(it) } }
    DisposableEffect(scanner) {
        scanner.start()
        onDispose { scanner.stop() }
    }
    UIKitView(factory = { scanner.view }, modifier = modifier)
}

@OptIn(ExperimentalForeignApi::class)
private class IosQrScanner(private val onResult: (String) -> Unit) : NSObject(), AVCaptureMetadataOutputObjectsDelegateProtocol {
    private val session = AVCaptureSession()
    private val previewLayer = AVCaptureVideoPreviewLayer(session = session).apply { videoGravity = AVLayerVideoGravityResizeAspectFill }
    val view: UIView = PreviewContainerView(previewLayer)

    init {
        val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)
        val input = device?.let { AVCaptureDeviceInput.deviceInputWithDevice(it, null) }
        if (input != null && session.canAddInput(input)) session.addInput(input)
        val output = AVCaptureMetadataOutput()
        if (session.canAddOutput(output)) {
            session.addOutput(output)
            output.setMetadataObjectsDelegate(this, dispatch_get_main_queue())
            output.metadataObjectTypes = listOf(AVMetadataObjectTypeQRCode)
        }
    }

    fun start() {
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) { session.startRunning() }
    }

    fun stop() {
        session.stopRunning()
    }

    override fun captureOutput(output: AVCaptureOutput, didOutputMetadataObjects: List<*>, fromConnection: AVCaptureConnection) {
        val code = didOutputMetadataObjects.firstOrNull() as? AVMetadataMachineReadableCodeObject ?: return
        code.stringValue?.let(onResult)
    }
}

/** 미리보기 레이어가 화면 크기에 맞게 따라오도록 layoutSubviews 에서 frame 을 맞춘다. */
@OptIn(ExperimentalForeignApi::class)
private class PreviewContainerView(private val previewLayer: CALayer) : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    init {
        layer.addSublayer(previewLayer)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        previewLayer.frame = bounds
    }
}

/** iOS 는 시스템 뒤로가기 버튼이 없다. 화면의 [나가기] 버튼으로 D-02 를 연다. */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
