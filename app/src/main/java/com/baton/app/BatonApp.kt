package com.baton.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 차량 연결·인계 상태는 화면(Activity)이 아니라 앱 전체가 들고 있는다.
 * 언어 변경·화면 회전 등으로 화면이 다시 만들어져도 진행 중인 인계가 사라지지 않는다.
 */
class BatonApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var probe: SignalProbe
        private set
    lateinit var controller: BatonController
        private set

    override fun onCreate() {
        super.onCreate()
        probe = SignalProbe(this) { _, _ -> }
        probe.start()
        controller = BatonController(this, probe.vehicle, scope)
        controller.start()
    }
}
