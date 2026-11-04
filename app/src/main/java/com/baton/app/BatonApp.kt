package com.baton.app

import ai.pleos.playground.vehicle.Vehicle
import android.app.Application
import com.baton.app.security.BatonLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 차량 연결·인계 상태는 화면(Activity)이 아니라 앱 전체가 들고 있는다.
 * 언어 변경·화면 회전 등으로 화면이 다시 만들어져도 진행 중인 인계가 사라지지 않는다.
 * 앱 프로세스가 뜨는 경로(화면 실행, 부팅 수신, 감시 서비스)와 무관하게 한 번만 시작한다.
 */
class BatonApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var vehicle: Vehicle
        private set
    lateinit var controller: BatonController
        private set

    override fun onCreate() {
        super.onCreate()
        BatonLog.init(this)
        vehicle = Vehicle(this)
        runCatching { vehicle.initialize() }.onFailure { BatonLog.w("Baton", "Vehicle 초기화 실패: ${it.javaClass.simpleName}") }
        controller = BatonController(this, vehicle, scope)
        controller.start()
    }
}
