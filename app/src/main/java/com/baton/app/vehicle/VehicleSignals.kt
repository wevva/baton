package com.baton.app.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.OilLevelState
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.listener.DoorLockStateListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.LowWasherFluidWaringListener
import ai.pleos.playground.vehicle.listener.OilLevelStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import com.baton.app.security.BatonLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 프로필에 있는 신호만 실시간 구독해서 VehicleSnapshot 하나로 합친다 (읽기 전용 — 제어 호출 없음).
 * 구독: 기어, 착석, (착석 센서 없는 차만) 도어 잠금, 배터리, 소모품, 경고등.
 */
class VehicleSignals(
    private val vehicle: Vehicle,
    private val profile: VehicleProfile,
    private val quirks: Quirks = Quirks.detect(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _snapshot = MutableStateFlow(VehicleSnapshot())
    val snapshot: StateFlow<VehicleSnapshot> = _snapshot.asStateFlow()

    private val cleanups = mutableListOf<() -> Unit>()

    private fun set(change: (VehicleSnapshot) -> VehicleSnapshot) =
        _snapshot.update { change(it).copy(timestampMs = clock()) }

    fun start() {
        guard("gear") {
            val l = object : GearSelectionListener {
                override fun onSelected(gear: VehicleGear?) = set { it.copy(gear = gear.toGear()) }
                override fun onFailed(e: Exception) = warn("gear", e)
            }
            vehicle.getGear().registerGearSelection(l)
            cleanups += { vehicle.getGear().unregisterGearSelection(l) }
        }

        for (spot in profile.seats) guard("seat ${spot.code}") {
            val area = SeatArea(seatValue(spot) ?: return@guard)
            val l = object : SeatOccupancyListener {
                override fun onSeatOccupancyUpdated(occupied: Boolean?) {
                    val v = occupied ?: return
                    val actual = if (quirks.seatOccupancyInverted) !v else v
                    set { it.copy(seatOccupied = it.seatOccupied + (spot to actual)) }
                }
                override fun onFailed(e: Exception) = warn("seat ${spot.code}", e)
            }
            vehicle.getSeat().registerSeatOccupancy(area, l)
            cleanups += { vehicle.getSeat().unregisterSeatOccupancy(area, l) }
        }

        // 착석 센서가 없는 차만: 하차·탑승 판단용 (잠금 상태 자체는 다음 운전자에게 넘기지 않음)
        if (profile.seats.isEmpty() && profile.hasDoorLock) guard("doorLock") {
            val area = ALL_DOORS
            val l = object : DoorLockStateListener {
                override fun onDoorLockStateUpdated(locked: Boolean?) = set { it.copy(doorsLocked = locked) }
                override fun onFailed(e: Exception) = warn("doorLock", e)
            }
            vehicle.getDoor().registerDoorLockState(area, l)
            cleanups += { vehicle.getDoor().unregisterDoorLockState(area, l) }
        }

        if (profile.hasBatterySoc) guard("soc") {
            val l = object : EvBatteryStateOfChargeListener {
                override fun onEvBatteryStateOfChargeUpdated(soc: Int?) = set { it.copy(batterySoc = soc) }
                override fun onFailed(e: Exception) = warn("soc", e)
            }
            vehicle.getEvBattery().registerEvBatteryStateOfCharge(l)
            cleanups += { vehicle.getEvBattery().unregisterEvBatteryStateOfCharge(l) }
        }

        val safety = vehicle.getSafety()
        if (profile.hasBrakeFluid) guard("brakeFluid") {
            val l = oilListener("brakeFluid") { low -> set { it.copy(brakeFluidLow = low) } }
            safety.registerBrakeFluidLevelState(l)
            cleanups += { safety.unregisterBrakeFluidLevelState(l) }
        }
        if (profile.hasEngineOil) guard("engineOil") {
            val l = oilListener("engineOil") { low -> set { it.copy(engineOilLow = low) } }
            safety.registerEngineOilLevelState(l)
            cleanups += { safety.unregisterEngineOilLevelState(l) }
        }
        if (profile.hasTransmissionOil) guard("transmissionOil") {
            val l = oilListener("transmissionOil") { low -> set { it.copy(transmissionOilLow = low) } }
            safety.registerTransmissionOilLevelState(l)
            cleanups += { safety.unregisterTransmissionOilLevelState(l) }
        }
        if (profile.hasWasherFluid) guard("washer") {
            val l = object : LowWasherFluidWaringListener {
                override fun onLowWasherFluidWaringUpdated(low: Boolean?) = set { it.copy(washerFluidLow = low) }
                override fun onFailed(e: Exception) = warn("washer", e)
            }
            safety.registerLowWasherFluidWaringState(l)
            cleanups += { safety.unregisterLowWasherFluidWaringState(l) }
        }
        if (profile.hasTelltales) guard("telltales") {
            val l = object : TelltaleInfoListener {
                override fun onTelltaleInfoUpdated(info: Telltales?) = set { it.copy(telltalesOn = lampsOn(info)) }
                override fun onFailed(e: Exception) = warn("telltales", e)
            }
            safety.registerTelltales(l)
            cleanups += { safety.unregisterTelltales(l) }
            // 구독 직후 현재 상태를 한 번 읽어 둔다 (변화가 없으면 콜백이 오지 않을 수 있음)
            safety.getTelltalesInfo({ info -> set { it.copy(telltalesOn = lampsOn(info)) } }, { e -> warn("telltales get", e) })
        }
    }

    fun stop() {
        cleanups.forEach { runCatching(it) }
        cleanups.clear()
    }

    private fun lampsOn(info: Telltales?): Set<String> =
        info?.telltales.orEmpty()
            .filter { it.state == TelltaleState.ON || it.state == TelltaleState.BLINK }
            .map { it.type.name }
            .toSet()

    private fun oilListener(name: String, onLow: (Boolean?) -> Unit) = object : OilLevelStateListener {
        override fun onOilLevelUpdated(level: OilLevelState?) =
            onLow(level?.let { it == OilLevelState.LOW || it == OilLevelState.CRITICALLY_LOW })
        override fun onFailed(e: Exception) = warn(name, e)
    }

    private inline fun guard(name: String, block: () -> Unit) {
        try { block() } catch (e: Exception) { warn(name, e) }
    }

    private fun warn(name: String, e: Exception) {
        BatonLog.w(TAG, "$name: ${e.javaClass.simpleName} ${e.message}")
    }

    private fun VehicleGear?.toGear() = when (this) {
        VehicleGear.GEAR_PARK -> VehicleSnapshot.Gear.PARK
        VehicleGear.GEAR_REVERSE -> VehicleSnapshot.Gear.REVERSE
        VehicleGear.GEAR_NEUTRAL -> VehicleSnapshot.Gear.NEUTRAL
        null, VehicleGear.GEAR_UNKNOWN -> VehicleSnapshot.Gear.UNKNOWN
        else -> VehicleSnapshot.Gear.DRIVE // D, 1~9단
    }

    companion object {
        const val TAG = "BatonSignals"

        /** 에뮬레이터 DOOR_LOCK은 4개 문을 합친 단일 영역(0x55)만 지원 */
        private val ALL_DOORS = DoorArea(DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT, DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT)

        private fun seatValue(s: Spot) = runCatching { SeatValue.valueOf("ROW_${s.row}_${s.side.name}") }.getOrNull()
    }
}

/** 차량 플랫폼별 신호 특이점을 한 곳에서 보정한다. */
data class Quirks(val seatOccupancyInverted: Boolean) {
    companion object {
        /** Pleos 에뮬레이터(ranchu)는 착석 값이 반전되어 들어옴 (2026-11-04 3회 재현) */
        fun detect(): Quirks = Quirks(seatOccupancyInverted = android.os.Build.HARDWARE == "ranchu")
    }
}
