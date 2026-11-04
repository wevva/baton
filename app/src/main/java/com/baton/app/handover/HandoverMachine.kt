package com.baton.app.handover

import com.baton.app.vehicle.Spot
import com.baton.app.vehicle.VehicleProfile
import com.baton.app.vehicle.VehicleSnapshot
import com.baton.app.vehicle.VehicleSnapshot.Gear

/**
 * 바통의 핵심 흐름을 관리하는 상태 머신 (순수 로직, 안드로이드 의존 없음).
 *
 *   DRIVING ──(P단)──▶ PARKED ──(운전석 비움 2초 유지)──▶ AWAY ──(운전석 착석)──▶ BOARDED ──(P단 해제)──▶ DRIVING
 *                                                          ▲                         │
 *                                                          └──(운전석 다시 비움)──────┘
 *
 * - 하차 순간 미해결 항목을 기록하고, 이후 신호가 정상이 되면 그 항목을 스스로 지운다.
 * - 운전자 신원(얼굴·목소리)은 쓰지 않는다. 운전석 착석·기어 신호만 쓴다.
 *   (동승자가 남아 있어도 운전자가 바뀌면 인계한다: 휴게소 운전 교대)
 * - 착석 센서가 없는 차는 'P단 + 도어 잠김'을 하차로, '잠금 해제'를 탑승으로 본다.
 */
class HandoverMachine(
    private val profile: VehicleProfile,
    private val detector: Detector = Detector(),
    /** 운전석이 이 시간 이상 계속 비어야 하차로 본다 (몸을 들썩이는 센서 흔들림 방지) */
    private val leaveDebounceMs: Long = 2_000,
    private val maxHistory: Int = 50,
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
        /** 다음 운전자가 '받았어요'를 누른 시각 (바통 수령증) */
        val receivedAtMs: Long? = null,
        /** 운전자가 '확인'으로 넘긴 항목 키 (신호로 해결되지 않는 할 일·참고 항목만. 안전 항목은 불가) */
        val acknowledged: Set<String> = emptySet(),
    ) {
        val allClear: Boolean get() = open.isEmpty()
        /** 화면에 보여 줄 남은 항목 (확인 처리한 것 제외) */
        val visible: List<OpenItem> get() = open.filter { it.key !in acknowledged }
    }

    var state: State = State.Driving
        private set

    /** 운전이 끝난(다음 운전자가 출발한) 인계 기록. 이력 기능의 원천 데이터 (최대 maxHistory건) */
    val history = mutableListOf<Handover>()

    private val driverSeat: Spot? =
        profile.seats.firstOrNull { it == profile.driverSeat } ?: profile.seats.firstOrNull()

    private var last: VehicleSnapshot? = null
    private var leftSince: Long? = null

    fun onSnapshot(s: VehicleSnapshot): State {
        last = s
        val parked = s.gear == Gear.PARK
        val leaving = driverLeft(s)
        leftSince = if (leaving) (leftSince ?: s.timestampMs) else null
        val leftLongEnough = leaving && s.timestampMs - (leftSince ?: s.timestampMs) >= leaveDebounceMs

        state = when (val cur = state) {
            State.Driving -> if (parked) State.Parked else cur
            State.Parked -> when {
                !parked -> State.Driving
                leftLongEnough -> State.Away(
                    Handover(parkedAtMs = s.timestampMs, socAtPark = s.batterySoc, open = detector.detect(profile, s))
                )
                else -> cur
            }
            is State.Away -> {
                val h = refresh(cur.handover, s)
                when {
                    // 앱이 꺼져 있는 사이 누군가 이미 출발한 경우: 이력에 남기고 주행 상태로
                    !parked && s.gear != Gear.UNKNOWN -> { archive(h); State.Driving }
                    driverBoarded(s) -> State.Boarded(h.copy(socAtBoard = s.batterySoc))
                    else -> State.Away(h)
                }
            }
            is State.Boarded -> {
                val h = refresh(cur.handover, s)
                when {
                    !parked && s.gear != Gear.UNKNOWN -> { archive(h); State.Driving }
                    // 탔다가 출발하지 않고 다시 내림: 같은 인계를 유지한 채 대기로
                    leftLongEnough -> State.Away(h)
                    else -> State.Boarded(h)
                }
            }
        }
        return state
    }

    /** 신호 변화가 없어도 시간이 흐르면 판단해야 하는 경우(하차 2초 유지)를 위한 주기 호출 */
    fun onTick(nowMs: Long): State {
        val s = last ?: return state
        return if (nowMs > s.timestampMs) onSnapshot(s.copy(timestampMs = nowMs)) else state
    }

    /** 바통 수령증: 탑승한 운전자가 인계를 받았음을 1탭으로 기록 */
    fun receive(atMs: Long): State {
        val cur = state
        if (cur is State.Boarded && cur.handover.receivedAtMs == null) {
            state = State.Boarded(cur.handover.copy(receivedAtMs = atMs))
        }
        return state
    }

    /**
     * 확인 처리: 신호로 해결되지 않는 항목(예: 배터리 부족)을 운전자가 봤다고 표시한다.
     * **안전 항목은 확인으로 숨길 수 없다** — 신호로 해결될 때까지 보이고, 출발 시 경고된다.
     * @return 처리됐으면 true
     */
    fun acknowledge(key: String): Boolean {
        val cur = state as? State.Boarded ?: return false
        val item = cur.handover.open.firstOrNull { it.key == key } ?: return false
        if (!canAcknowledge(item)) return false
        state = State.Boarded(cur.handover.copy(acknowledged = cur.handover.acknowledged + key))
        return true
    }

    fun unacknowledge(key: String): Boolean {
        val cur = state as? State.Boarded ?: return false
        if (key !in cur.handover.acknowledged) return false
        state = State.Boarded(cur.handover.copy(acknowledged = cur.handover.acknowledged - key))
        return true
    }

    /** 저장해 둔 상태로 복원 (앱 재시작·차량 전원 off 후) */
    fun restore(saved: State, savedHistory: List<Handover>) {
        state = saved
        history.clear()
        history += savedHistory.takeLast(maxHistory)
    }

    private fun archive(h: Handover) {
        history += h
        while (history.size > maxHistory) history.removeAt(0)
    }

    /**
     * 현재 신호로 다시 점검한다.
     * - 기록된 항목이 더 이상 감지되지 않으면 해결 처리 (값이 바뀌면 최신 값으로, 예: 창문 40% → 20%)
     * - 기록 이후 새로 생긴 문·잠금·창문·조명 상태는 추가하지 않는다: 다음 운전자가 타면서 만든 상태이기 때문
     *   (예: 잠금을 풀고 타는 순간 "문이 잠기지 않았어요"가 인계로 둔갑하는 오탐 방지)
     * - 주차 중 저절로 생길 수 있는 항목(배터리 부족)만 새로 추가한다
     * - 같은 항목의 해결 기록은 최신 1건만 둔다
     */
    private fun refresh(h: Handover, s: VehicleSnapshot): Handover {
        val detected = detector.detect(profile, s).associateBy { it.key }
        val recordedKeys = h.open.map { it.key }.toSet()
        // 감지되면 최신 값으로, 신호 값을 아직 모르면 기록 그대로 유지 (모름 ≠ 해결)
        val stillOpen = h.open.mapNotNull { detected[it.key] ?: it.takeUnless { _ -> detector.isKnown(it, s) } }
        val appeared = detected.values.filter { it.key !in recordedKeys && it.type in CAN_APPEAR_WHILE_PARKED }
        val open = (stillOpen + appeared).sortedBy { it.type.tier.ordinal }
        val newlyResolved = h.open.filter { it.key !in detected && detector.isKnown(it, s) }.map { Resolved(it, s.timestampMs) }
        val openKeys = open.map { it.key }.toSet()
        val newKeys = newlyResolved.map { it.item.key }.toSet()
        val resolved = h.resolved.filter { it.item.key !in newKeys && it.item.key !in openKeys } + newlyResolved
        // 해결된 항목의 확인 표시는 정리 (같은 항목이 다시 생기면 다시 보이도록)
        return h.copy(open = open, resolved = resolved, acknowledged = h.acknowledged intersect openKeys)
    }

    companion object {
        fun canAcknowledge(item: OpenItem) = item.type.tier != OpenItem.Tier.SAFETY

        private val CAN_APPEAR_WHILE_PARKED = setOf(OpenItem.Type.LOW_BATTERY, OpenItem.Type.BRAKE_FLUID_LOW)
    }

    /** 운전석이 비었는지. 값을 모르면 '비었다'고 단정하지 않는다 */
    private fun driverLeft(s: VehicleSnapshot): Boolean {
        val seat = driverSeat
        return if (seat != null) s.seatOccupied[seat] == false
        else profile.hasDoorLock && s.doorsLocked == true // 착석 센서 없는 차
    }

    private fun driverBoarded(s: VehicleSnapshot): Boolean {
        val seat = driverSeat
        return if (seat != null) s.seatOccupied[seat] == true
        else profile.hasDoorLock && s.doorsLocked == false
    }
}
