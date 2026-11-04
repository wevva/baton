package com.baton.app.handover

import com.baton.app.vehicle.Spot

/**
 * "아직 끝나지 않은 일" 한 건. 문장이 아니라 종류 코드 + 위치 + 값으로만 저장한다.
 * 화면·음성 문장은 받는 사람의 언어로 그때그때 만든다 (i18n/ItemText).
 */
data class OpenItem(
    val type: Type,
    val spot: Spot? = null,
    val value: Int? = null,
) {
    /** 같은 항목인지 판단하는 키 (값이 바뀌어도 같은 항목) */
    val key: String get() = "${type.name}:${spot?.code ?: "-"}"

    enum class Type(val tier: Tier) {
        DOORS_UNLOCKED(Tier.SAFETY),
        DOOR_OPEN(Tier.SAFETY),
        TRUNK_OPEN(Tier.SAFETY),
        /** 브레이크액 부족/위험 — 제동 계통 이상은 다음 운전자가 출발 전에 알아야 한다 */
        BRAKE_FLUID_LOW(Tier.SAFETY),
        WINDOW_OPEN(Tier.TODO),
        CABIN_LIGHT_ON(Tier.TODO),
        LOW_BATTERY(Tier.TODO),
    }

    /** 3단 우선순위: 안전 > 할 일 > 참고 */
    enum class Tier { SAFETY, TODO, INFO }
}
