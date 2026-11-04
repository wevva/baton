package com.baton.app.vehicle

/**
 * 차량 안의 위치를 SDK와 무관한 바통 자체 코드로 표현한다.
 * 열(row)과 좌/중/우로 나누므로 2인승부터 3열 12인승까지 같은 방식으로 다룬다.
 */
data class Spot(val row: Int, val side: Side) {
    enum class Side { LEFT, CENTER, RIGHT }

    /** 저장·전송용 언어 중립 코드. 예: "R1L", "R3C" */
    val code: String get() = "R$row${side.name.first()}"

    companion object {
        fun fromCode(code: String): Spot? {
            val m = Regex("""R([0-9])([LCR])""").matchEntire(code) ?: return null
            val side = Side.entries.first { it.name.first() == m.groupValues[2].first() }
            return Spot(m.groupValues[1].toInt(), side)
        }
    }
}

/** 차량별로 자동 수집한 구성. 하드코딩된 차종 정보 없이 SDK 기능 확인 결과로만 만든다. */
data class VehicleProfile(
    val doors: List<Spot> = emptyList(),
    val windows: Map<Spot, Int> = emptyMap(),   // 창문 위치 → 최대값(완전 열림)
    val seats: List<Spot> = emptyList(),
    val hasDoorLock: Boolean = false,
    val hasCabinLight: Boolean = false,
    val hasBatterySoc: Boolean = false,
    val hasTrunk: Boolean = false,
) {
    val rows: Int get() = (seats.map { it.row } + doors.map { it.row }).maxOrNull() ?: 0
    val seatCount: Int get() = seats.size
}
