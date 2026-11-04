package com.baton.app.i18n

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import com.baton.app.R
import com.baton.app.handover.OpenItem
import com.baton.app.vehicle.Spot
import java.util.Locale

/**
 * 언어 중립 인계 데이터(OpenItem)를 '받는 사람의 언어' 문장으로 바꾼다.
 * 새 언어 추가 = res/values-xx/strings.xml 하나 추가. 이 코드는 바꿀 필요 없음.
 */
class ItemText(
    base: Context,
    val locale: Locale,
    /** 운전석 위치 (우핸들 차량이면 1열 오른쪽이 '운전석') */
    private val driverSeat: Spot = Spot(1, Spot.Side.LEFT),
) {

    // 주의: Pleos 기기에서는 앱 내부 Resources 언어 지정이 시스템 언어로 덮어써진다(2026-11-04 확인).
    // 화면 언어는 AppLanguage(앱별 언어 API)로 바꾸고, 여기서는 일반 안드로이드 기기용 보조 경로로만 쓴다.
    @Suppress("DEPRECATION")
    private val res: Resources = Resources(
        base.assets,
        base.resources.displayMetrics,
        Configuration(base.resources.configuration).apply { setLocales(LocaleList(locale)) },
    )

    fun str(id: Int, vararg args: Any): String = res.getString(id, *args)

    /** 개수에 따라 단·복수가 바뀌는 문장 (영어 1 item / 2 items 등) */
    fun plural(id: Int, count: Int, vararg args: Any): String = res.getQuantityString(id, count, *args)

    /** 경고등·설정 이름 (모르는 코드면 코드 그대로) */
    fun name(code: String?): String = code?.let { c -> TelltaleNames.res(c)?.let { str(it) } ?: c } ?: ""

    fun item(item: OpenItem): String = when (item.type) {
        OpenItem.Type.WARNING_ON -> item.value?.takeIf { it > 0 }
            ?.let { n -> plural(R.plurals.item_warning_on_trip, n, name(item.code), n) }
            ?: str(R.string.item_warning_on, name(item.code))
        OpenItem.Type.WARNING_DURING_TRIP -> (item.value ?: 1).let { n -> plural(R.plurals.item_warning_trip, n, name(item.code), n) }
        OpenItem.Type.SETTING_CHANGED -> str(R.string.item_setting, name(item.code))
        OpenItem.Type.BRAKE_FLUID_LOW -> str(R.string.item_brake_fluid_low)
        OpenItem.Type.ENGINE_OIL_LOW -> str(R.string.item_engine_oil_low)
        OpenItem.Type.TRANSMISSION_OIL_LOW -> str(R.string.item_transmission_oil_low)
        OpenItem.Type.WASHER_FLUID_LOW -> str(R.string.item_washer_low)
        OpenItem.Type.LOW_BATTERY -> str(R.string.item_low_battery, item.value ?: 0)
    }

    /** 주차 중(내리기 전) 미리보기 문구: 떠나는 사람 시점. 설정은 '되돌릴 수 있어요' */
    fun preview(item: OpenItem): String =
        if (item.type == OpenItem.Type.SETTING_CHANGED) str(R.string.preview_setting, name(item.code)) else item(item)

    /** 해결 순간의 짧은 완료 문구 (예: "타이어 공기압 경고등 꺼짐 확인") */
    fun done(item: OpenItem): String = when (item.type) {
        OpenItem.Type.WARNING_ON -> str(R.string.done_warning, name(item.code))
        OpenItem.Type.WARNING_DURING_TRIP -> str(R.string.done_warning_trip, name(item.code))
        OpenItem.Type.SETTING_CHANGED -> str(R.string.done_setting, name(item.code))
        OpenItem.Type.BRAKE_FLUID_LOW -> str(R.string.done_brake_fluid_low)
        OpenItem.Type.ENGINE_OIL_LOW -> str(R.string.done_engine_oil)
        OpenItem.Type.TRANSMISSION_OIL_LOW -> str(R.string.done_transmission_oil)
        OpenItem.Type.WASHER_FLUID_LOW -> str(R.string.done_washer)
        OpenItem.Type.LOW_BATTERY -> str(R.string.done_low_battery)
    }

    fun tier(t: OpenItem.Tier): String = when (t) {
        OpenItem.Tier.SAFETY -> str(R.string.tier_safety)
        OpenItem.Tier.TODO -> str(R.string.tier_todo)
        OpenItem.Tier.INFO -> str(R.string.tier_info)
    }

    /** 운전석 기준 표기(좌·우핸들 모두). 3열 이상은 "N열 왼쪽"처럼 일반 표기 */
    fun spot(s: Spot?): String {
        if (s == null) return ""
        return when {
            s == driverSeat -> str(R.string.spot_driver)
            s.row == 1 && s.side != Spot.Side.CENTER -> str(R.string.spot_front_passenger)
            s.row == 2 && s.side == Spot.Side.LEFT -> str(R.string.spot_rear_left)
            s.row == 2 && s.side == Spot.Side.RIGHT -> str(R.string.spot_rear_right)
            else -> str(R.string.spot_row_side, s.row, side(s.side))
        }
    }

    private fun side(side: Spot.Side) = when (side) {
        Spot.Side.LEFT -> str(R.string.side_left)
        Spot.Side.CENTER -> str(R.string.side_center)
        Spot.Side.RIGHT -> str(R.string.side_right)
    }

    companion object {
        /** 앱이 화면 문구를 갖고 있는 언어. 새 언어를 추가하면 여기에도 한 줄 */
        val SUPPORTED: List<Locale> = listOf(Locale.KOREAN, Locale.ENGLISH, Locale.JAPANESE, Locale.SIMPLIFIED_CHINESE)
    }
}
