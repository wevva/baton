package com.baton.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.baton.app.security.BatonLog

/**
 * 화면을 열지 않아도 주차·하차·탑승을 감지하도록 앱을 살려 두는 감시 서비스.
 * 실제 일은 BatonApp(→BatonController)이 하고, 이 서비스는 프로세스 유지와 상시 알림만 담당한다.
 * - 외부 공개 안 함(exported=false). 차량을 제어하지 않는다.
 * - 알림 권한이 없으면 알림은 보이지 않지만 서비스는 동작한다(Android 13+ 정책).
 */
class HandoverService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        (application as BatonApp).controller.start() // 이미 시작됐으면 아무것도 안 함
        runCatching {
            val n = notification(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        }.onFailure { BatonLog.w("Baton", "감시 서비스 시작 실패: ${it.javaClass.simpleName}") }
        return START_STICKY // 시스템이 종료해도 다시 살아나 감시를 이어간다
    }

    companion object {
        private const val CHANNEL_ID = "baton_watch"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, HandoverService::class.java)) }
                .onFailure { BatonLog.w("Baton", "감시 서비스 요청 실패: ${it.javaClass.simpleName}") }
        }

        private fun notification(context: Context): Notification {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_MIN)
            )
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT, // 보안: 변경 불가 PendingIntent
            )
            return Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_baton)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(R.string.notif_text))
                .setContentIntent(open)
                .setOngoing(true)
                .build()
        }
    }
}
