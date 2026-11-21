package com.nettwork.policethief.platform.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nettwork.policethief.domain.model.AppResult
import com.nettwork.policethief.domain.usecase.ShareCurrentLocationUseCase
import com.nettwork.policethief.shared.rules.GameRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import com.nettwork.policethief.platform.BackgroundLocationController

/**
 * 화면이 꺼져도 약 15초마다 위치를 서버로 보내는 Foreground Service.
 *
 * - 상단 알림이 떠 있는 동안만 동작한다(Android 정책). 사용자가 알림에서 앱을 확인할 수 있다.
 * - Android 14+ 는 시작 시점에 위치 권한이 있어야 하며, 없으면 SecurityException 이 나므로 즉시 종료한다.
 * - Doze/배터리 최적화 때문에 15초 주기가 보장되지 않는다(미검증 항목: docs/testing/TEST_REPORT.md).
 */
class LocationTrackingService : Service(), KoinComponent {
    private val shareLocation: ShareCurrentLocationUseCase by inject()
    private var scope: CoroutineScope? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification("위치 공유를 시작합니다"),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "위치 권한이 없어 백그라운드 위치 공유를 시작할 수 없습니다: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (scope == null) {
            running.value = true
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { it.launch { loop() } }
        }
        return START_STICKY
    }

    private suspend fun kotlinx.coroutines.CoroutineScope.loop() {
        while (isActive) {
            val text = when (val result = shareLocation()) {
                is AppResult.Success -> "마지막 전송 성공 (정확도 ±${result.value.accuracyMeters.toInt()}m)"
                is AppResult.Failure -> "전송 실패: ${result.error.userMessage.lineSequence().first()}"
            }
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
            delay(GameRules.LOCATION_UPDATE_INTERVAL_MILLIS)
        }
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        running.value = false
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "경기 위치 공유", NotificationManager.IMPORTANCE_LOW))
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("경찰과 도둑 · 위치 공유 중")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val TAG = "LocationTrackingService"
        private const val CHANNEL_ID = "location_tracking"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.nettwork.policethief.STOP_TRACKING"
        internal val running = MutableStateFlow(false)
    }
}

class AndroidBackgroundLocationController(private val context: Context) : BackgroundLocationController {
    override val isSupported: Boolean = true
    override val isRunning: StateFlow<Boolean> = LocationTrackingService.running.asStateFlow()

    override fun start() {
        // Android 12+ 는 앱이 백그라운드일 때 Foreground Service 시작을 막는다(ForegroundServiceStartNotAllowedException).
        // 그때는 앱 화면이 보이는 동안의 위치 전송(GameSessionRepository)으로 대신하고, 앱 복귀 후 다시 시도된다.
        try {
            ContextCompat.startForegroundService(context, Intent(context, LocationTrackingService::class.java))
        } catch (e: IllegalStateException) {
            android.util.Log.w("BackgroundLocation", "백그라운드 위치 서비스를 시작하지 못했습니다: ${e.message}")
        }
    }

    override fun stop() {
        context.stopService(Intent(context, LocationTrackingService::class.java))
    }
}
