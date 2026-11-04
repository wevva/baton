package com.baton.app.vehicle

/** 어느 한 순간의 차량 상태. 알 수 없는 값은 null (지원 안 하는 신호 포함). */
data class VehicleSnapshot(
    val gear: Gear = Gear.UNKNOWN,
    val doorsLocked: Boolean? = null,
    val doorOpen: Map<Spot, Boolean> = emptyMap(),
    val trunkOpen: Boolean? = null,
    val windowPos: Map<Spot, Int> = emptyMap(),
    val seatOccupied: Map<Spot, Boolean> = emptyMap(),
    val cabinLightOn: Boolean? = null,
    val batterySoc: Int? = null,
    val timestampMs: Long = 0L,
) {
    enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

    val driverSeated: Boolean? get() = seatOccupied[DRIVER_SEAT]
    val anyoneSeated: Boolean get() = seatOccupied.values.any { it }

    companion object {
        val DRIVER_SEAT = Spot(1, Spot.Side.LEFT)
    }
}
