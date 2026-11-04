package com.baton.app.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.OilLevelState
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.DoorLockStateListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.OilLevelStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.WindowPositionListener
import com.baton.app.security.BatonLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 프로필에 있는 부위만 실시간 구독해서 VehicleSnapshot 하나로 합친다.
 * 지원하지 않는 신호는 구독하지 않으므로 차종마다 자동으로 범위가 달라진다.
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

        if (profile.hasDoorLock) guard("doorLock") {
            val area = ALL_DOORS
            val l = object : DoorLockStateListener {
                override fun onDoorLockStateUpdated(locked: Boolean?) = set { it.copy(doorsLocked = locked) }
                override fun onFailed(e: Exception) = warn("doorLock", e)
            }
            vehicle.getDoor().registerDoorLockState(area, l)
            cleanups += { vehicle.getDoor().unregisterDoorLockState(area, l) }
        }

        for (spot in profile.doors) guard("door ${spot.code}") {
            val area = DoorArea(doorValue(spot) ?: return@guard)
            val l = object : DoorPositionListener {
                override fun onPositionUpdated(pos: Int?) =
                    set { it.copy(doorOpen = it.doorOpen + (spot to ((pos ?: 0) > 0))) }
                override fun onFailed(e: Exception) = warn("door ${spot.code}", e)
            }
            vehicle.getDoor().registerDoorPosition(area, l)
            cleanups += { vehicle.getDoor().unregisterDoorPosition(area, l) }
        }

        if (profile.hasTrunk) guard("trunk") {
            val area = DoorArea(DoorValue.REAR)
            val l = object : DoorPositionListener {
                override fun onPositionUpdated(pos: Int?) = set { it.copy(trunkOpen = (pos ?: 0) > 0) }
                override fun onFailed(e: Exception) = warn("trunk", e)
            }
            vehicle.getDoor().registerDoorPosition(area, l)
            cleanups += { vehicle.getDoor().unregisterDoorPosition(area, l) }
        }

        for (spot in profile.windows.keys) guard("window ${spot.code}") {
            val area = WindowArea(windowValue(spot) ?: return@guard)
            val l = object : WindowPositionListener {
                override fun onWindowPositionUpdated(pos: Int?) =
                    set { it.copy(windowPos = it.windowPos + (spot to (pos ?: 0))) }
                override fun onFailed(e: Exception) = warn("window ${spot.code}", e)
            }
            vehicle.getWindow().registerWindowPosition(area, l)
            cleanups += { vehicle.getWindow().unregisterWindowPosition(area, l) }
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

        if (profile.hasCabinLight) guard("cabinLight") {
            val l = object : LightStateListener {
                override fun onLightStateUpdated(state: LightState?) = set { it.copy(cabinLightOn = state == LightState.ON) }
                override fun onFailed(e: Exception) = warn("cabinLight", e)
            }
            vehicle.getLight().registerCabinLightState(l)
            cleanups += { vehicle.getLight().unregisterCabinLightState(l) }
        }

        if (profile.hasBrakeFluid) guard("brakeFluid") {
            val l = object : OilLevelStateListener {
                override fun onOilLevelUpdated(level: OilLevelState?) = set {
                    it.copy(brakeFluidLow = level?.let { v -> v == OilLevelState.LOW || v == OilLevelState.CRITICALLY_LOW })
                }
                override fun onFailed(e: Exception) = warn("brakeFluid", e)
            }
            vehicle.getSafety().registerBrakeFluidLevelState(l)
            cleanups += { vehicle.getSafety().unregisterBrakeFluidLevelState(l) }
        }

        if (profile.hasBatterySoc) guard("soc") {
            val l = object : EvBatteryStateOfChargeListener {
                override fun onEvBatteryStateOfChargeUpdated(soc: Int?) = set { it.copy(batterySoc = soc) }
                override fun onFailed(e: Exception) = warn("soc", e)
            }
            vehicle.getEvBattery().registerEvBatteryStateOfCharge(l)
            cleanups += { vehicle.getEvBattery().unregisterEvBatteryStateOfCharge(l) }
        }
    }

    fun stop() {
        cleanups.forEach { runCatching(it) }
        cleanups.clear()
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

        private fun doorValue(s: Spot) = runCatching { DoorValue.valueOf("ROW_${s.row}_${s.side.name}") }.getOrNull()
        private fun windowValue(s: Spot) = runCatching { WindowValue.valueOf("ROW_${s.row}_${s.side.name}") }.getOrNull()
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
