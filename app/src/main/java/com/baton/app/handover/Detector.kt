package com.baton.app.handover

import com.baton.app.vehicle.VehicleProfile
import com.baton.app.vehicle.VehicleSnapshot

/**
 * 차량 상태에서 미해결 항목을 찾는다. 차량 프로필에 없는 부위·신호는 아예 보지 않는다.
 * 순수 함수라 단위 테스트로 검증한다.
 */
class Detector(
    private val lowBatteryPercent: Int = 20,
    /** 창문이 최대값 대비 몇 % 이상 열려야 "열림"으로 볼지 */
    private val windowOpenRatio: Double = 0.1,
) {
    fun detect(profile: VehicleProfile, s: VehicleSnapshot): List<OpenItem> {
        val items = mutableListOf<OpenItem>()
        if (profile.hasDoorLock && s.doorsLocked == false) {
            items += OpenItem(OpenItem.Type.DOORS_UNLOCKED)
        }
        for (door in profile.doors) {
            if (s.doorOpen[door] == true) items += OpenItem(OpenItem.Type.DOOR_OPEN, door)
        }
        if (profile.hasTrunk && s.trunkOpen == true) items += OpenItem(OpenItem.Type.TRUNK_OPEN)
        for ((spot, max) in profile.windows) {
            val pos = s.windowPos[spot] ?: continue
            if (max > 0 && pos.toDouble() / max >= windowOpenRatio) {
                items += OpenItem(OpenItem.Type.WINDOW_OPEN, spot, pos * 100 / max)
            }
        }
        if (profile.hasCabinLight && s.cabinLightOn == true) items += OpenItem(OpenItem.Type.CABIN_LIGHT_ON)
        val soc = s.batterySoc
        if (profile.hasBatterySoc && soc != null && soc <= lowBatteryPercent) {
            items += OpenItem(OpenItem.Type.LOW_BATTERY, value = soc)
        }
        return items.sortedBy { it.type.tier.ordinal }
    }
}
