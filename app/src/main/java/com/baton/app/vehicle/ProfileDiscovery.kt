package com.baton.app.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import com.baton.app.security.BatonLog
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 차종·연식·옵션표 없이, 차량에게 "무엇을 갖고 있는지" 직접 물어서 VehicleProfile을 만든다.
 * 같은 모델이라도 옵션(선루프, 3열 시트 등)이 다르면 SDK 기능 확인 결과가 달라지므로 그대로 반영된다.
 */
class ProfileDiscovery(private val vehicle: Vehicle, private val context: android.content.Context) {

    data class Result(
        val profile: VehicleProfile,
        val log: List<String>,
    )

    suspend fun discover(): Result {
        val log = mutableListOf<String>()
        fun note(s: String) { log += s; BatonLog.i(TAG, s) }

        val doorPos = ask("doorPosition", ::note) { ok, fail -> vehicle.getDoor().checkDoorPositionCapabilities({ _, areas -> ok(areas) }, fail) }
        val doorLock = ask("doorLock", ::note) { ok, fail -> vehicle.getDoor().checkDoorLockCapabilities({ _, areas -> ok(areas) }, fail) }
        val windows = ask("windowPosition", ::note) { ok, fail -> vehicle.getWindow().checkWindowPositionCapabilities({ _, areas -> ok(areas) }, fail) }
        val seats = ask("seatOccupancy", ::note) { ok, fail -> vehicle.getSeat().checkSeatOccupancyCapabilities({ _, areas -> ok(areas) }, fail) }
        val cabin = ask("cabinLight", ::note) { ok, fail ->
            vehicle.getLight().checkCabinLightStateCapabilities({ cfg, _ -> ok(cfg) }, fail)
        }
        val soc = ask("batterySoc", ::note) { ok, fail -> vehicle.getEvBattery().checkEvBatteryStateOfChargeCapabilities(ok, fail) }
        // 차대번호(VIN)는 개인정보와 연결될 수 있어 읽지 않는다 (기능 판단에 불필요)
        val brake = ask("brakeFluid", ::note) { ok, fail ->
            vehicle.getSafety().checkBrakeFluidLevelStateCapabilities({ cfg, _ -> ok(cfg) }, fail)
        }
        // getCapabilityInfo는 PRIVATE 권한 필요(서드파티 불가) → 기능별 확인 API로 대신함

        val doorSpots = doorPos?.keys?.flatMap { area -> area.values.mapNotNull { doorSpot(it) } }?.distinct().orEmpty()
        val hasTrunk = doorPos?.keys?.any { area -> area.values.any { it == DoorValue.REAR } } == true
        val windowSpots = windows?.entries?.flatMap { (area, bounds) ->
            area.values.mapNotNull { v -> windowSpot(v)?.let { it to (bounds.maxValue ?: 0) } }
        }?.toMap().orEmpty()
        val seatSpots = seats?.flatMap { area -> area.values.mapNotNull { seatSpot(it) } }?.distinct().orEmpty()

        val profile = VehicleProfile(
            doors = doorSpots.sortedWith(compareBy({ it.row }, { it.side })),
            windows = windowSpots,
            seats = seatSpots.sortedWith(compareBy({ it.row }, { it.side })),
            hasDoorLock = !doorLock.isNullOrEmpty(),
            hasCabinLight = cabin != null,
            hasBatterySoc = soc != null,
            hasTrunk = hasTrunk,
            hasBrakeFluid = brake != null,
            driverSeat = DriverSeat.resolve(context),
        )
        note("PROFILE rows=${profile.rows} seats=${profile.seatCount} doors=${profile.doors.map { it.code }} " +
            "windows=${profile.windows.mapKeys { it.key.code }} lock=${profile.hasDoorLock} trunk=${profile.hasTrunk} " +
            "cabin=${profile.hasCabinLight} soc=${profile.hasBatterySoc} driver=${profile.driverSeat.code}")
        return Result(profile, log)
    }

    /** SDK 콜백 하나를 기다린다. 실패·시간초과면 null (= 이 차엔 그 기능이 없다고 본다) */
    private suspend fun <T> ask(
        name: String,
        note: (String) -> Unit,
        call: (ok: (T) -> Unit, fail: (Exception) -> Unit) -> Unit,
    ): T? = withTimeoutOrNull(3_000) {
        suspendCancellableCoroutine { cont ->
            try {
                call({ v -> note("$name = $v"); if (cont.isActive) cont.resume(v) },
                    { e -> note("$name 없음: ${e.message}"); if (cont.isActive) cont.resume(null) })
            } catch (e: Exception) {
                note("$name 예외: ${e.javaClass.simpleName} ${e.message}")
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    private fun doorSpot(v: DoorValue): Spot? = when (v) {
        DoorValue.ROW_1_LEFT -> Spot(1, Spot.Side.LEFT)
        DoorValue.ROW_1_RIGHT -> Spot(1, Spot.Side.RIGHT)
        DoorValue.ROW_2_LEFT -> Spot(2, Spot.Side.LEFT)
        DoorValue.ROW_2_RIGHT -> Spot(2, Spot.Side.RIGHT)
        DoorValue.ROW_3_LEFT -> Spot(3, Spot.Side.LEFT)
        DoorValue.ROW_3_RIGHT -> Spot(3, Spot.Side.RIGHT)
        else -> null // HOOD, REAR(트렁크)는 문 목록이 아닌 별도 항목
    }

    private fun windowSpot(v: WindowValue): Spot? {
        val m = Regex("""ROW_([0-9])_(LEFT|CENTER|RIGHT)""").matchEntire(v.name) ?: return null
        return Spot(m.groupValues[1].toInt(), Spot.Side.valueOf(m.groupValues[2]))
    }

    private fun seatSpot(v: SeatValue): Spot? {
        val m = Regex("""ROW_([0-9])_(LEFT|CENTER|RIGHT)""").matchEntire(v.name) ?: return null
        return Spot(m.groupValues[1].toInt(), Spot.Side.valueOf(m.groupValues[2]))
    }

    companion object { const val TAG = "BatonProfile" }
}
