package com.baton.app.vehicle

/** 어느 한 순간의 차량 상태. 알 수 없는 값은 null (지원 안 하는 신호 포함). */
data class VehicleSnapshot(
    val gear: Gear = Gear.UNKNOWN,
    /** 착석 센서 없는 차에서 하차·탑승 판단에만 쓴다 (다음 운전자에게 '잠금' 자체는 넘기지 않음) */
    val doorsLocked: Boolean? = null,
    val seatOccupied: Map<Spot, Boolean> = emptyMap(),
    val batterySoc: Int? = null,
    /** 소모품 부족: true=부족, false=정상, null=모름 */
    val brakeFluidLow: Boolean? = null,
    val engineOilLow: Boolean? = null,
    val transmissionOilLow: Boolean? = null,
    val washerFluidLow: Boolean? = null,
    /** 켜져 있는(ON·깜빡임) 경고등 이름 집합 (Pleos TelltaleType 이름). null = 아직 모름 */
    val telltalesOn: Set<String>? = null,
    /**
     * 경고등별 '켜짐' 누적 횟수 (앱 시작 이후). 신호를 받는 즉시 센다.
     * 상태 흐름이 중간값을 건너뛰어도(1초 안에 켜졌다 꺼짐) 횟수는 빠지지 않는다.
     */
    val lampOnCounts: Map<String, Int> = emptyMap(),
    val timestampMs: Long = 0L,
) {
    enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

    val anyoneSeated: Boolean get() = seatOccupied.values.any { it }

    /** 착석 센서 값이 하나라도 들어왔는지. 모르는 상태를 '아무도 없음'으로 오해하지 않기 위함 */
    val seatsKnown: Boolean get() = seatOccupied.isNotEmpty()

    companion object {
        val DRIVER_SEAT = Spot(1, Spot.Side.LEFT)
    }
}
