package com.baton.app.handover

import com.baton.app.vehicle.VehicleProfile
import com.baton.app.vehicle.VehicleSnapshot
import com.baton.app.vehicle.VehicleSnapshot.Gear

/**
 * 바통의 핵심 흐름을 관리하는 상태 머신 (순수 로직, 안드로이드 의존 없음).
 *
 *   DRIVING ──(P단)──▶ PARKED ──(모두 하차)──▶ AWAY ──(누군가 탑승)──▶ BOARDED ──(P단 해제)──▶ DRIVING
 *
 * - 하차 순간 미해결 항목을 기록(스냅샷)하고, 이후 신호가 정상이 되면 그 항목을 스스로 지운다.
 * - 운전자 신원(얼굴·목소리)은 쓰지 않는다. 착석·기어 신호만 쓴다.
 */
class HandoverMachine(
    private val profile: VehicleProfile,
    private val detector: Detector = Detector(),
) {
    sealed interface State {
        data object Driving : State
        data object Parked : State
        data class Away(val handover: Handover) : State
        data class Boarded(val handover: Handover) : State
    }

    data class Resolved(val item: OpenItem, val atMs: Long)

    data class Handover(
        val parkedAtMs: Long,
        val socAtPark: Int?,
        val open: List<OpenItem>,
        val resolved: List<Resolved> = emptyList(),
        val socAtBoard: Int? = null,
    ) {
        val allClear: Boolean get() = open.isEmpty()
    }

    var state: State = State.Driving
        private set

    /** 운전이 끝난(다음 운전자가 출발한) 인계 기록. 이력 기능의 원천 데이터 */
    val history = mutableListOf<Handover>()

    fun onSnapshot(s: VehicleSnapshot): State {
        val parked = s.gear == Gear.PARK
        state = when (val cur = state) {
            State.Driving -> if (parked) State.Parked else cur
            State.Parked -> when {
                !parked -> State.Driving
                hasOccupancy() && !s.anyoneSeated -> State.Away(
                    Handover(parkedAtMs = s.timestampMs, socAtPark = s.batterySoc, open = detector.detect(profile, s))
                )
                else -> cur
            }
            is State.Away -> {
                val h = refresh(cur.handover, s)
                if (s.anyoneSeated) State.Boarded(h.copy(socAtBoard = s.batterySoc)) else State.Away(h)
            }
            is State.Boarded -> {
                val h = refresh(cur.handover, s)
                if (!parked) {
                    history += h
                    State.Driving
                } else {
                    State.Boarded(h)
                }
            }
        }
        return state
    }

    /** 현재 신호로 다시 점검: 사라진 항목은 해결 처리, 새로 생긴 항목은 추가 */
    private fun refresh(h: Handover, s: VehicleSnapshot): Handover {
        val now = detector.detect(profile, s)
        val nowKeys = now.map { it.key }.toSet()
        val newlyResolved = h.open.filter { it.key !in nowKeys }.map { Resolved(it, s.timestampMs) }
        return h.copy(open = now, resolved = h.resolved + newlyResolved)
    }

    /** 착석 센서가 없는 차는 "모두 하차"를 알 수 없으므로 P단 + 도어 잠금으로 대신 판단할 여지를 남긴다 */
    private fun hasOccupancy() = profile.seats.isNotEmpty()
}
