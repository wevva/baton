package com.baton.app

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WheelArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WheelValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.DoorLockStateListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import android.content.Context
import com.baton.app.security.BatonLog

/**
 * 바통이 쓸 차량 신호를 한 번씩 읽어 보고, 핵심 신호(기어·도어 잠금·SoC)는 변화를 구독한다.
 * 개발 초기 "이 에뮬레이터에서 실제로 어떤 신호가 나오는가"를 확인하기 위한 도구.
 */
class SignalProbe(context: Context, private val onUpdate: (String, String) -> Unit) {

    val vehicle = Vehicle(context.applicationContext)
    private val driverDoor = DoorArea(DoorValue.ROW_1_LEFT)
    // 에뮬레이터 DOOR_LOCK은 4개 문을 합친 단일 영역(0x55)만 지원
    private val allDoors = DoorArea(DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT, DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT)
    private val driverSeat = SeatArea(SeatValue.ROW_1_LEFT)

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) = report("[실시간] 기어", gear?.name ?: "null")
        override fun onFailed(e: Exception) = fail("[실시간] 기어", e)
    }
    private val doorLockListener = object : DoorLockStateListener {
        override fun onDoorLockStateUpdated(locked: Boolean?) = report("[실시간] 도어 잠금(전체)", lockText(locked))
        override fun onFailed(e: Exception) = fail("[실시간] 도어 잠금(전체)", e)
    }
    private val socListener = object : EvBatteryStateOfChargeListener {
        override fun onEvBatteryStateOfChargeUpdated(soc: Int?) = report("[실시간] 배터리 SoC", "$soc%")
        override fun onFailed(e: Exception) = fail("[실시간] 배터리 SoC", e)
    }

    fun start() {
        try {
            vehicle.initialize()
            report("SDK", "initialize() 완료")
        } catch (e: Exception) {
            fail("SDK", e)
        }
    }

    fun subscribe() {
        guard("[실시간] 기어") { vehicle.getGear().registerGearSelection(gearListener) }
        guard("[실시간] 도어 잠금(전체)") { vehicle.getDoor().registerDoorLockState(allDoors, doorLockListener) }
        guard("[실시간] 배터리 SoC") { vehicle.getEvBattery().registerEvBatteryStateOfCharge(socListener) }
    }

    fun readAll() {
        guard("기어") {
            vehicle.getGear().getCurrentGearSelectionState({ report("기어", it?.name ?: "null") }, { fail("기어", it) })
        }
        guard("운전석 도어 위치") {
            vehicle.getDoor().getCurrentDoorPosition(driverDoor, { report("운전석 도어 위치", "$it") }, { fail("운전석 도어 위치", it) })
        }
        guard("도어 잠금(전체)") {
            vehicle.getDoor().getDoorLockState(allDoors, { report("도어 잠금(전체)", lockText(it)) }, { fail("도어 잠금(전체)", it) })
        }
        guard("운전석 착석") {
            vehicle.getSeat().getCurrentSeatOccupancy(driverSeat, { report("운전석 착석", "$it") }, { fail("운전석 착석", it) })
        }
        guard("운전석 안전벨트") {
            vehicle.getSeat().getCurrentSeatBeltBuckledState(driverSeat, { report("운전석 안전벨트", "$it") }, { fail("운전석 안전벨트", it) })
        }
        guard("타이어 공기압(앞왼)") {
            vehicle.getTire().getTirePressure(WheelArea(WheelValue.LEFT_FRONT), { report("타이어 공기압(앞왼)", "$it") }, { fail("타이어 공기압(앞왼)", it) })
        }
        guard("경고등") {
            vehicle.getSafety().getTelltalesInfo({ report("경고등", it?.toString() ?: "null") }, { fail("경고등", it) })
        }
        guard("타이어 저압 기준(앞왼)") {
            vehicle.getTire().getLowTirePressure(WheelArea(WheelValue.LEFT_FRONT), { report("타이어 저압 기준(앞왼)", "$it") }, { fail("타이어 저압 기준(앞왼)", it) })
        }
        guard("배터리 SoC") {
            vehicle.getEvBattery().getEvBatteryStateOfCharge({ report("배터리 SoC", "$it%") }, { fail("배터리 SoC", it) })
        }
        guard("충전구 열림") {
            vehicle.getEvBattery().isEvChargePortOpen({ report("충전구 열림", "$it") }, { fail("충전구 열림", it) })
        }
        guard("주행 가능 거리") {
            vehicle.getOdometer().getRangeRemainingDistance({ report("주행 가능 거리", "$it") }, { fail("주행 가능 거리", it) })
        }
        guard("누적 주행거리") {
            vehicle.getOdometer().getCurrentDistanceDriven({ report("누적 주행거리", "$it") }, { fail("누적 주행거리", it) })
        }
        guard("연료") {
            vehicle.getFuel().getFuelPercent({ report("연료", "$it%") }, { fail("연료", it) })
        }
        guard("엔진오일") {
            vehicle.getSafety().getEngineOilLevelState({ report("엔진오일", it?.name ?: "null") }, { fail("엔진오일", it) })
        }
        guard("브레이크액") {
            vehicle.getSafety().getBrakeFluidLevelState({ report("브레이크액", it?.name ?: "null") }, { fail("브레이크액", it) })
        }
        guard("워셔액 부족") {
            vehicle.getSafety().hasLowWasherFluidWarning({ report("워셔액 부족", "$it") }, { fail("워셔액 부족", it) })
        }
    }

    fun stop() {
        runCatching { vehicle.getGear().unregisterGearSelection(gearListener) }
        runCatching { vehicle.getDoor().unregisterDoorLockState(allDoors, doorLockListener) }
        runCatching { vehicle.getEvBattery().unregisterEvBatteryStateOfCharge(socListener) }
        runCatching { vehicle.release() }
    }

    private fun lockText(locked: Boolean?) = when (locked) { true -> "잠김"; false -> "열림"; null -> "null" }

    private fun guard(name: String, block: () -> Unit) {
        try { block() } catch (e: Exception) { fail(name, e) }
    }

    private fun report(name: String, value: String) {
        BatonLog.i(TAG, "$name = $value")
        onUpdate(name, value)
    }

    private fun fail(name: String, e: Exception) {
        val msg = "실패: ${e.javaClass.simpleName} ${e.message ?: ""}".trim()
        BatonLog.w(TAG, "$name = $msg")
        onUpdate(name, msg)
    }

    companion object {
        const val TAG = "BatonProbe"
    }
}
