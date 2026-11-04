package com.baton.app.vehicle

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import com.baton.app.security.BatonLog

/**
 * 운전석 위치를 차량에서 읽는다 (AAOS 표준 속성 INFO_DRIVER_SEAT, 권한 CAR_INFO = normal).
 * 좌핸들(한국·미국·중국)은 1열 왼쪽, 우핸들(일본·영국·호주 등)은 1열 오른쪽.
 * 읽지 못하면 좌핸들로 가정한다.
 */
object DriverSeat {
    // VehicleAreaSeat 비트값 (AOSP hardware/interfaces/automotive/vehicle)
    private const val ROW_1_LEFT = 0x0001
    private const val ROW_1_CENTER = 0x0002
    private const val ROW_1_RIGHT = 0x0004

    val DEFAULT = Spot(1, Spot.Side.LEFT)

    fun resolve(context: Context): Spot {
        var car: Car? = null
        return try {
            car = Car.createCar(context)
            val pm = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager ?: return DEFAULT
            // INFO_DRIVER_SEAT는 좌석 영역 속성이지만 areaId는 0으로 조회한다 (AOSP 정의)
            when (pm.getIntProperty(VehiclePropertyIds.INFO_DRIVER_SEAT, 0)) {
                ROW_1_RIGHT -> Spot(1, Spot.Side.RIGHT)
                ROW_1_CENTER -> Spot(1, Spot.Side.CENTER)
                ROW_1_LEFT -> DEFAULT
                else -> DEFAULT
            }
        } catch (e: Exception) {
            BatonLog.w("BatonProfile", "운전석 위치 읽기 실패 → 좌핸들 가정: ${e.javaClass.simpleName}")
            DEFAULT
        } finally {
            runCatching { car?.disconnect() }
        }
    }
}
