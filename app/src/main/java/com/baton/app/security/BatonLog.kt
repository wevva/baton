package com.baton.app.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log

/**
 * 앱 로그 단일 창구. release(디버그 불가) 빌드에서는 아무것도 남기지 않는다.
 * 차량 상태(좌석·잠금·창문)가 시스템 로그에 남아 다른 앱·진단 도구로 새는 것을 막는다.
 * (R8 로그 제거 규칙과 이중 방어)
 */
object BatonLog {
    @Volatile var enabled: Boolean = false
        private set

    fun init(context: Context) {
        enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    fun i(tag: String, msg: String) { if (enabled) Log.i(tag, msg) }
    fun w(tag: String, msg: String) { if (enabled) Log.w(tag, msg) }
}
