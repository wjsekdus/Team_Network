package com.nettwork.policethief.platform

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger

/**
 * Android 경기 알림: 중요도 높은 알림 채널 + 짧은 진동.
 * 알림 권한(Android 13+)이 없으면 알림은 조용히 생략한다(앱은 계속 동작, 앱 안 배너는 표시됨).
 * 앱이 백그라운드에 있어도 위치 서비스(Foreground Service)가 살아 있고 WebSocket 이 연결돼 있는 동안에는 알림이 뜬다.
 * 앱 프로세스가 완전히 종료된 경우까지 받으려면 푸시(FCM 등)가 필요하다 — 이번 구현 범위 밖(미검증 항목으로 문서화).
 */
class AndroidGameAlerts(private val context: Context) : GameAlerts {
    private val ids = AtomicInteger(2_000)

    override fun notify(title: String, message: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "경기 이벤트", NotificationManager.IMPORTANCE_HIGH))
        }
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pending = launch?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .apply { pending?.let { setContentIntent(it) } }
            .build()
        try {
            NotificationManagerCompat.from(context).notify(ids.incrementAndGet(), notification)
        } catch (_: SecurityException) {
            // 권한이 도중에 회수된 경우
        }
    }

    override fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(VibrationEffect.createOneShot(VIBRATION_MILLIS, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private companion object {
        const val CHANNEL_ID = "game_events"
        const val VIBRATION_MILLIS = 400L
    }
}
