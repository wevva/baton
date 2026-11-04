package com.baton.app.handover

import com.baton.app.vehicle.VehicleProfile
import com.baton.app.vehicle.VehicleSnapshot

/**
 * 차량 상태에서 '지금' 다음 운전자에게 넘길 항목을 찾는다 (경고등·바꿔 둔 설정·소모품·배터리).
 * '지난 운행 중 켜졌던 경고'는 상태 머신이 운행 기록으로 따로 만든다.
 * 차량 프로필에 없는 신호는 아예 보지 않는다. 순수 함수라 단위 테스트로 검증한다.
 */
class Detector(
    private val lowBatteryPercent: Int = 20,
) {
    fun detect(profile: VehicleProfile, s: VehicleSnapshot): List<OpenItem> {
        val items = mutableListOf<OpenItem>()
        val lamps = s.telltalesOn
        if (profile.hasTelltales && lamps != null) {
            for (name in lamps) {
                val spec = Telltales.spec(name) ?: continue // 분류표에 없거나 기록 금지 신호는 무시
                items += when (spec.kind) {
                    Telltales.Kind.FAULT -> OpenItem(OpenItem.Type.WARNING_ON, code = name)
                    Telltales.Kind.SETTING -> OpenItem(OpenItem.Type.SETTING_CHANGED, code = name)
                }
            }
        }
        if (profile.hasBrakeFluid && s.brakeFluidLow == true) items += OpenItem(OpenItem.Type.BRAKE_FLUID_LOW)
        if (profile.hasEngineOil && s.engineOilLow == true) items += OpenItem(OpenItem.Type.ENGINE_OIL_LOW)
        if (profile.hasTransmissionOil && s.transmissionOilLow == true) items += OpenItem(OpenItem.Type.TRANSMISSION_OIL_LOW)
        if (profile.hasWasherFluid && s.washerFluidLow == true) items += OpenItem(OpenItem.Type.WASHER_FLUID_LOW)
        val soc = s.batterySoc
        if (profile.hasBatterySoc && soc != null && soc <= lowBatteryPercent) {
            items += OpenItem(OpenItem.Type.LOW_BATTERY, value = soc)
        }
        return sort(items)
    }

    /**
     * 이 항목을 판단할 신호 값이 실제로 들어와 있는지.
     * 값이 없으면(앱 재시작 직후, 신호 끊김) '해결됨'으로 보지 않는다 — 모르는 것은 해결된 것이 아니다.
     */
    fun isKnown(item: OpenItem, s: VehicleSnapshot): Boolean = when (item.type) {
        OpenItem.Type.WARNING_ON, OpenItem.Type.SETTING_CHANGED -> s.telltalesOn != null
        OpenItem.Type.WARNING_DURING_TRIP -> false // 지난 운행의 기록 — 신호로 '해결'되지 않는다 (확인 처리만)
        OpenItem.Type.BRAKE_FLUID_LOW -> s.brakeFluidLow != null
        OpenItem.Type.ENGINE_OIL_LOW -> s.engineOilLow != null
        OpenItem.Type.TRANSMISSION_OIL_LOW -> s.transmissionOilLow != null
        OpenItem.Type.WASHER_FLUID_LOW -> s.washerFluidLow != null
        OpenItem.Type.LOW_BATTERY -> s.batterySoc != null
    }

    companion object {
        /** 안전 → 할 일 → 참고, 같은 등급 안에서는 지금 켜진 경고 → 설정 → 지난 운행 → 소모품 순 */
        fun sort(items: List<OpenItem>): List<OpenItem> =
            items.distinctBy { it.key }.sortedWith(compareBy({ it.tier.ordinal }, { it.type.ordinal }, { it.code ?: "" }))
    }
}
