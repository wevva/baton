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
class ItemText(base: Context, val locale: Locale) {

    // 주의: Pleos 기기에서는 앱 내부 Resources 언어 지정이 시스템 언어로 덮어써진다(2026-11-04 확인).
    // 화면 언어는 AppLanguage(앱별 언어 API)로 바꾸고, 여기서는 일반 안드로이드 기기용 보조 경로로만 쓴다.
    @Suppress("DEPRECATION")
    private val res: Resources = Resources(
        base.assets,
        base.resources.displayMetrics,
        Configuration(base.resources.configuration).apply { setLocales(LocaleList(locale)) },
    )

    fun str(id: Int, vararg args: Any): String = res.getString(id, *args)

    fun item(item: OpenItem): String = when (item.type) {
        OpenItem.Type.DOORS_UNLOCKED -> str(R.string.item_doors_unlocked)
        OpenItem.Type.DOOR_OPEN -> str(R.string.item_door_open, spot(item.spot))
        OpenItem.Type.TRUNK_OPEN -> str(R.string.item_trunk_open)
        OpenItem.Type.WINDOW_OPEN -> str(R.string.item_window_open, spot(item.spot), item.value ?: 0)
        OpenItem.Type.CABIN_LIGHT_ON -> str(R.string.item_cabin_light_on)
        OpenItem.Type.LOW_BATTERY -> str(R.string.item_low_battery, item.value ?: 0)
    }

    /** 해결 순간의 짧은 완료 문구 (예: "운전석 창문 닫힘 확인") */
    fun done(item: OpenItem): String = when (item.type) {
        OpenItem.Type.DOORS_UNLOCKED -> str(R.string.done_doors_unlocked)
        OpenItem.Type.DOOR_OPEN -> str(R.string.done_door_open, spot(item.spot))
        OpenItem.Type.TRUNK_OPEN -> str(R.string.done_trunk_open)
        OpenItem.Type.WINDOW_OPEN -> str(R.string.done_window_open, spot(item.spot))
        OpenItem.Type.CABIN_LIGHT_ON -> str(R.string.done_cabin_light_on)
        OpenItem.Type.LOW_BATTERY -> str(R.string.done_low_battery)
    }

    fun tier(t: OpenItem.Tier): String = when (t) {
        OpenItem.Tier.SAFETY -> str(R.string.tier_safety)
        OpenItem.Tier.TODO -> str(R.string.tier_todo)
        OpenItem.Tier.INFO -> str(R.string.tier_info)
    }

    /** 1열 왼쪽 = 운전석 (좌측 운전 기준). 3열 이상은 "N열 왼쪽"처럼 일반 표기 */
    fun spot(s: Spot?): String {
        if (s == null) return ""
        return when {
            s.row == 1 && s.side == Spot.Side.LEFT -> str(R.string.spot_driver)
            s.row == 1 && s.side == Spot.Side.RIGHT -> str(R.string.spot_front_passenger)
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
