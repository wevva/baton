package com.baton.app.handover

import com.baton.app.vehicle.Spot

/**
 * 다음 운전자에게 넘길 항목 한 건. 문장이 아니라 종류 코드 + (위치) + (값) + (경고등·설정 코드)로만 저장한다.
 * 화면·음성 문장은 받는 사람의 언어로 그때그때 만든다 (i18n/ItemText).
 *
 * 넘기는 기준: '보이지 않는데 다음 운행에 영향을 주는 것' (Telltales.kt 참고).
 * 문 잠금·창문·실내등은 넘기지 않는다 — 차량 순정 경고와 블루링크가 이미 다루는 영역.
 */
data class OpenItem(
    val type: Type,
    val spot: Spot? = null,
    /** 퍼센트(배터리) 또는 횟수(지난 운행 중 경고) */
    val value: Int? = null,
    /** 경고등·설정 이름 (Pleos TelltaleType 이름, 예: "TPMS", "ESC_OFF") */
    val code: String? = null,
) {
    /** 같은 항목인지 판단하는 키 (값이 바뀌어도 같은 항목) */
    val key: String get() = if (code != null) "${type.name}:$code" else "${type.name}:${spot?.code ?: "-"}"

    /** 우선순위: 경고등·설정은 분류표, 나머지는 종류 기본값 */
    val tier: Tier get() = code?.let { Telltales.spec(it)?.tier } ?: type.defaultTier

    enum class Type(val defaultTier: Tier) {
        /** 지금 켜져 있는 고장 경고등 (value = 지난 운행 중 켜진 횟수, 없으면 null) */
        WARNING_ON(Tier.SAFETY),
        /** 지난 운행 중 켜졌다가 지금은 꺼진 경고등 (value = 켜진 횟수) — 시동을 걸어도 다음 운전자는 알 수 없다 */
        WARNING_DURING_TRIP(Tier.TODO),
        /** 앞 운전자가 바꿔 둔 설정 (ESC 꺼짐, i-Pedal 등) */
        SETTING_CHANGED(Tier.TODO),
        BRAKE_FLUID_LOW(Tier.SAFETY),
        ENGINE_OIL_LOW(Tier.SAFETY),
        TRANSMISSION_OIL_LOW(Tier.TODO),
        WASHER_FLUID_LOW(Tier.INFO),
        LOW_BATTERY(Tier.TODO),
    }

    /** 3단 우선순위: 안전 > 할 일 > 참고 */
    enum class Tier { SAFETY, TODO, INFO }
}
