package com.baton.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 차량(기기)이 켜지거나 앱이 업데이트되면 감시 서비스를 시작한다.
 * 외부 공개 안 함(exported=false): 시스템 방송만 받고, 다른 앱은 이 수신기를 호출할 수 없다.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> HandoverService.start(context)
        }
    }
}
