package com.baton.app.handover

/**
 * 경고등(telltale) 분류표 — 무엇을 다음 운전자에게 넘길지의 기준.
 *
 * 기준 1) 미국 상용차 운행 전 점검(DVIR, 49 CFR 396.11) 점검 대상:
 *   브레이크, 주차 브레이크, 조향, 등화, 타이어, 경음기, 와이퍼, 미러, 연결 장치, 휠, 비상 장비
 *   + 승용 전동화 차량의 구동계(엔진·EV·HEV·FCEV 시스템, 12V 배터리)
 * 기준 2) '보이지 않는데 다음 운행에 영향을 주는 것'만 넘긴다.
 * 기준 3) 운전 습관을 드러내는 신호(과속, 운전자 주의 경고, 안전벨트)는 기록하지 않는다 — 감시 도구가 되지 않기 위해.
 * 기준 4) 문 잠금·창문·실내등은 차량 순정 경고·블루링크가 이미 다룬다 → 넘기지 않는다.
 *
 * 이름은 Pleos Vehicle SDK의 TelltaleType 이름과 같다 (순수 로직 유지를 위해 문자열로 다룬다).
 */
object Telltales {

    enum class Kind {
        /** 고장·이상 경고 — 지금 켜져 있으면 알리고, 지난 운행 중 켜졌던 횟수도 넘긴다 */
        FAULT,
        /** 앞 운전자가 바꿔 둔 설정 — 다음 운전자가 모르고 출발하면 거동이 달라진다 */
        SETTING,
    }

    /** DVIR 점검 범주 (기획서·문서에서 근거 표시용) */
    enum class Dvir { BRAKES, PARKING_BRAKE, STEERING, LIGHTING, TIRES, EMERGENCY, POWERTRAIN, DRIVING_AID, NONE }

    data class Spec(val kind: Kind, val dvir: Dvir, val tier: OpenItem.Tier)

    private val S = OpenItem.Tier.SAFETY
    private val T = OpenItem.Tier.TODO
    private val I = OpenItem.Tier.INFO

    val SPECS: Map<String, Spec> = mapOf(
        // 제동
        "ABS" to Spec(Kind.FAULT, Dvir.BRAKES, S),
        "RBS" to Spec(Kind.FAULT, Dvir.BRAKES, S),          // 회생 제동 시스템
        "ESC" to Spec(Kind.FAULT, Dvir.BRAKES, S),          // 차체 자세 제어 작동/이상
        "ELECTRONIC_PARKING_BRAKE" to Spec(Kind.FAULT, Dvir.PARKING_BRAKE, S),
        // 조향
        "MDPS" to Spec(Kind.FAULT, Dvir.STEERING, S),
        // 등화
        "LED_HEADLAMP" to Spec(Kind.FAULT, Dvir.LIGHTING, T),
        "AFS" to Spec(Kind.FAULT, Dvir.LIGHTING, T),
        // 타이어
        "TPMS" to Spec(Kind.FAULT, Dvir.TIRES, S),
        // 비상 장비
        "AIRBAG" to Spec(Kind.FAULT, Dvir.EMERGENCY, S),
        "EMERGENCY_CALL" to Spec(Kind.FAULT, Dvir.EMERGENCY, T),
        // 구동계
        "MASTER_WARNING" to Spec(Kind.FAULT, Dvir.POWERTRAIN, S),
        "ENGINE" to Spec(Kind.FAULT, Dvir.POWERTRAIN, T),
        "ENGINE_OIL_PRESSURE" to Spec(Kind.FAULT, Dvir.POWERTRAIN, S),
        "ENGINE_TEMPERATURE" to Spec(Kind.FAULT, Dvir.POWERTRAIN, S),
        "EV_SYSTEM" to Spec(Kind.FAULT, Dvir.POWERTRAIN, S),
        "HEV_SYSTEM" to Spec(Kind.FAULT, Dvir.POWERTRAIN, S),
        "FCEV_SYSTEM" to Spec(Kind.FAULT, Dvir.POWERTRAIN, S),
        "EREV_SYSTEM" to Spec(Kind.FAULT, Dvir.POWERTRAIN, S),
        "LOW_VOLT_BATTERY" to Spec(Kind.FAULT, Dvir.POWERTRAIN, S), // 12V 배터리 — 방전되면 시동 불가
        "POWER_DOWN" to Spec(Kind.FAULT, Dvir.POWERTRAIN, S),       // 출력 제한
        "H2_LEAK" to Spec(Kind.FAULT, Dvir.POWERTRAIN, S),
        "H2_SENSOR" to Spec(Kind.FAULT, Dvir.POWERTRAIN, T),
        "PARTICLE_FILTER" to Spec(Kind.FAULT, Dvir.POWERTRAIN, T),
        "SCR" to Spec(Kind.FAULT, Dvir.POWERTRAIN, T),
        "WATER_SEPARATOR" to Spec(Kind.FAULT, Dvir.POWERTRAIN, T),
        "IMMOBILIZER" to Spec(Kind.FAULT, Dvir.POWERTRAIN, T),
        // 바꿔 둔 설정 — 다음 운전자가 모르면 거동이 달라짐
        "ESC_OFF" to Spec(Kind.SETTING, Dvir.BRAKES, S),            // 차체 자세 제어 꺼짐
        // PASSENGER_AIRBAG_OFF(조수석 에어백 OFF 표시)는 넘기지 않는다: 현대·기아 최신 차는 승객 구분 시스템(ODS)이
        // 지금 조수석에 앉은 사람을 보고 자동으로 켜고 끄므로 '앞사람이 남긴 설정'이 아니고, 실내에서 보인다.
        // 넘기면 조수석이 빌 때마다 숨길 수 없는 안전 항목이 생긴다 (2026-11-11 기아 취급설명서 ODS 항목 확인)
        "CHILD_PRESENCE_DETECTION_OFF" to Spec(Kind.SETTING, Dvir.EMERGENCY, T),
        "I_PEDAL" to Spec(Kind.SETTING, Dvir.DRIVING_AID, T),        // 원페달: 가속 페달만 떼도 강하게 감속
        "CREEP_OFF" to Spec(Kind.SETTING, Dvir.DRIVING_AID, T),      // 브레이크 떼도 앞으로 안 감
        "TOW_MODE" to Spec(Kind.SETTING, Dvir.DRIVING_AID, T),
        "ESC_SPORT" to Spec(Kind.SETTING, Dvir.BRAKES, T),
        "DRIVE_MODE_SPORT" to Spec(Kind.SETTING, Dvir.DRIVING_AID, I),
        "DRIFT_MODE" to Spec(Kind.SETTING, Dvir.DRIVING_AID, T),
        "TRACK_MODE" to Spec(Kind.SETTING, Dvir.DRIVING_AID, T),
        "LAUNCH_MODE" to Spec(Kind.SETTING, Dvir.DRIVING_AID, T),
        "FOUR_WHEEL_DRIVE_MODE_4L" to Spec(Kind.SETTING, Dvir.DRIVING_AID, T),
    )

    /** 기록하지 않는 신호 (운전 습관·개인 행동을 드러냄) — 테스트로 고정 */
    val NEVER_RECORDED = setOf("OVER_SPEED", "DRIVER_ATTENTION", "SEAT_BELT", "ICY_ROAD")

    fun spec(name: String): Spec? = if (name in NEVER_RECORDED) null else SPECS[name]
}
