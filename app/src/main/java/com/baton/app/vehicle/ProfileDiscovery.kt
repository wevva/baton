package com.baton.app.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.area.SeatValue
import com.baton.app.security.BatonLog
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 차종·연식·옵션표 없이, 차량에게 "무엇을 갖고 있는지" 직접 물어서 VehicleProfile을 만든다.
 * 같은 모델이라도 옵션(3열 시트 등)이 다르면 SDK 기능 확인 결과가 달라지므로 그대로 반영된다.
 */
class ProfileDiscovery(private val vehicle: Vehicle, private val context: android.content.Context) {

    data class Result(val profile: VehicleProfile, val log: List<String>)

    suspend fun discover(): Result {
        val log = mutableListOf<String>()
        fun note(s: String) { log += s; BatonLog.i(TAG, s) }

        val seats = ask("seatOccupancy", ::note) { ok, fail -> vehicle.getSeat().checkSeatOccupancyCapabilities({ _, areas -> ok(areas) }, fail) }
        val doorLock = ask("doorLock", ::note) { ok, fail -> vehicle.getDoor().checkDoorLockCapabilities({ _, areas -> ok(areas) }, fail) }
        val soc = ask("batterySoc", ::note) { ok, fail -> vehicle.getEvBattery().checkEvBatteryStateOfChargeCapabilities(ok, fail) }
        val safety = vehicle.getSafety()
        val brake = ask("brakeFluid", ::note) { ok, fail -> safety.checkBrakeFluidLevelStateCapabilities({ cfg, _ -> ok(cfg) }, fail) }
        val engineOil = ask("engineOil", ::note) { ok, fail -> safety.checkEngineOilLevelStateCapabilities({ cfg, _ -> ok(cfg) }, fail) }
        val transOil = ask("transmissionOil", ::note) { ok, fail -> safety.checkTransmissionOilLevelStateCapabilities({ cfg, _ -> ok(cfg) }, fail) }
        val washer = ask("washerFluid", ::note) { ok, fail -> safety.checkLowWasherFluidWarningStateCapabilities(ok, fail) }
        // 경고등: 기능 확인 + 실제 읽기까지 성공해야 지원으로 본다 (권한이 없는 플랫폼이면 자동으로 꺼짐)
        val telltales = ask("telltales", ::note) { ok, fail -> safety.getTelltalesInfo({ ok(it) }, fail) }
        // 차대번호(VIN)는 개인정보와 연결될 수 있어 읽지 않는다 (기능 판단에 불필요)

        val seatSpots = seats?.flatMap { area -> area.values.mapNotNull { seatSpot(it) } }?.distinct().orEmpty()
        val profile = VehicleProfile(
            seats = seatSpots.sortedWith(compareBy({ it.row }, { it.side })),
            hasDoorLock = !doorLock.isNullOrEmpty(),
            hasBatterySoc = soc != null,
            hasBrakeFluid = brake != null,
            hasEngineOil = engineOil != null,
            hasTransmissionOil = transOil != null,
            hasWasherFluid = washer != null,
            hasTelltales = telltales != null,
            driverSeat = DriverSeat.resolve(context),
        )
        note(
            "PROFILE rows=${profile.rows} seats=${profile.seatCount} lock=${profile.hasDoorLock} soc=${profile.hasBatterySoc} " +
                "brake=${profile.hasBrakeFluid} oil=${profile.hasEngineOil} trans=${profile.hasTransmissionOil} " +
                "washer=${profile.hasWasherFluid} telltales=${profile.hasTelltales} driver=${profile.driverSeat.code}"
        )
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

    private fun seatSpot(v: SeatValue): Spot? {
        val m = Regex("""ROW_([0-9])_(LEFT|CENTER|RIGHT)""").matchEntire(v.name) ?: return null
        return Spot(m.groupValues[1].toInt(), Spot.Side.valueOf(m.groupValues[2]))
    }

    companion object { const val TAG = "BatonProfile" }
}
