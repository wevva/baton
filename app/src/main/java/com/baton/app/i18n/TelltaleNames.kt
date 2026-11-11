package com.baton.app.i18n

import com.baton.app.R

/** 경고등·설정 코드 → 이름 문자열 (tools/gen_strings.py로 생성, 직접 고치지 말 것). getIdentifier를 쓰지 않아 리소스 축소에도 안전 */
object TelltaleNames {
    fun res(code: String): Int? = when (code) {
        "ABS" -> R.string.tt_ABS
        "RBS" -> R.string.tt_RBS
        "ESC" -> R.string.tt_ESC
        "ELECTRONIC_PARKING_BRAKE" -> R.string.tt_ELECTRONIC_PARKING_BRAKE
        "MDPS" -> R.string.tt_MDPS
        "LED_HEADLAMP" -> R.string.tt_LED_HEADLAMP
        "AFS" -> R.string.tt_AFS
        "TPMS" -> R.string.tt_TPMS
        "AIRBAG" -> R.string.tt_AIRBAG
        "EMERGENCY_CALL" -> R.string.tt_EMERGENCY_CALL
        "MASTER_WARNING" -> R.string.tt_MASTER_WARNING
        "ENGINE" -> R.string.tt_ENGINE
        "ENGINE_OIL_PRESSURE" -> R.string.tt_ENGINE_OIL_PRESSURE
        "ENGINE_TEMPERATURE" -> R.string.tt_ENGINE_TEMPERATURE
        "EV_SYSTEM" -> R.string.tt_EV_SYSTEM
        "HEV_SYSTEM" -> R.string.tt_HEV_SYSTEM
        "FCEV_SYSTEM" -> R.string.tt_FCEV_SYSTEM
        "EREV_SYSTEM" -> R.string.tt_EREV_SYSTEM
        "LOW_VOLT_BATTERY" -> R.string.tt_LOW_VOLT_BATTERY
        "POWER_DOWN" -> R.string.tt_POWER_DOWN
        "H2_LEAK" -> R.string.tt_H2_LEAK
        "H2_SENSOR" -> R.string.tt_H2_SENSOR
        "PARTICLE_FILTER" -> R.string.tt_PARTICLE_FILTER
        "SCR" -> R.string.tt_SCR
        "WATER_SEPARATOR" -> R.string.tt_WATER_SEPARATOR
        "IMMOBILIZER" -> R.string.tt_IMMOBILIZER
        "ESC_OFF" -> R.string.tt_ESC_OFF
        "CHILD_PRESENCE_DETECTION_OFF" -> R.string.tt_CHILD_PRESENCE_DETECTION_OFF
        "I_PEDAL" -> R.string.tt_I_PEDAL
        "CREEP_OFF" -> R.string.tt_CREEP_OFF
        "TOW_MODE" -> R.string.tt_TOW_MODE
        "ESC_SPORT" -> R.string.tt_ESC_SPORT
        "DRIVE_MODE_SPORT" -> R.string.tt_DRIVE_MODE_SPORT
        "DRIFT_MODE" -> R.string.tt_DRIFT_MODE
        "TRACK_MODE" -> R.string.tt_TRACK_MODE
        "LAUNCH_MODE" -> R.string.tt_LAUNCH_MODE
        "FOUR_WHEEL_DRIVE_MODE_4L" -> R.string.tt_FOUR_WHEEL_DRIVE_MODE_4L
        else -> null
    }

    val CODES: Set<String> = setOf("ABS", "RBS", "ESC", "ELECTRONIC_PARKING_BRAKE", "MDPS", "LED_HEADLAMP", "AFS", "TPMS", "AIRBAG", "EMERGENCY_CALL", "MASTER_WARNING", "ENGINE", "ENGINE_OIL_PRESSURE", "ENGINE_TEMPERATURE", "EV_SYSTEM", "HEV_SYSTEM", "FCEV_SYSTEM", "EREV_SYSTEM", "LOW_VOLT_BATTERY", "POWER_DOWN", "H2_LEAK", "H2_SENSOR", "PARTICLE_FILTER", "SCR", "WATER_SEPARATOR", "IMMOBILIZER", "ESC_OFF", "CHILD_PRESENCE_DETECTION_OFF", "I_PEDAL", "CREEP_OFF", "TOW_MODE", "ESC_SPORT", "DRIVE_MODE_SPORT", "DRIFT_MODE", "TRACK_MODE", "LAUNCH_MODE", "FOUR_WHEEL_DRIVE_MODE_4L")
}
