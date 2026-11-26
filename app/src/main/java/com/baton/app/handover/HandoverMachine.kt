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
 * 무엇을 넘기나 (미국 상용차 운행 전 점검 DVIR, 49 CFR 396.11·396.13 의 원칙을 차량 신호로 자동화):
 *  - 지금 켜져 있는 고장 경고등, 앞 운전자가 바꿔 둔 설정(ESC 꺼짐·i-Pedal 등), 소모품 부족, 배터리 부족
 *  - **지난 운행 중 켜졌다 꺼진 경고등과 횟수** — 시동을 걸어도 다음 운전자는 알 수 없는 정보
 * 무엇을 넘기지 않나:
 *  - 문 잠금·창문·실내등 (차량 순정 경고·블루링크 영역), 운전 습관 신호(과속·주의 경고 등, 감시 방지)
 * 운전자 신원(얼굴·목소리)은 쓰지 않는다. 운전석 착석·기어 신호만 쓴다.
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
        /** 다음 운전자가 '받았어요'를 누른 시각 (바통 수령증 — DVIR '다음 운전자 검토·서명'에 해당) */
        val receivedAtMs: Long? = null,
        /** 운전자가 '확인'으로 넘긴 항목 키 (지금 켜진 안전 경고는 불가) */
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

    // ── 운행 기록: 이번 운행 중 켜졌던 고장 경고등과 횟수 ──
    private val tripWarnings = LinkedHashMap<String, Int>()
    private var lampsBefore: Set<String> = emptySet()
    private var countsBefore: Map<String, Int> = emptyMap()

    fun onSnapshot(s: VehicleSnapshot): State {
        last = s
        recordTrip(s)
        // 주차 상태에서 기어를 잠깐 모르게 되면 P단이 유지된 것으로 본다 (신호 끊김 ≠ 출발)
        val parked = s.gear == Gear.PARK || (s.gear == Gear.UNKNOWN && state != State.Driving)
        val leaving = driverLeft(s)
        leftSince = if (leaving) (leftSince ?: s.timestampMs) else null
        val leftLongEnough = leaving && s.timestampMs - (leftSince ?: s.timestampMs) >= leaveDebounceMs

        state = when (val cur = state) {
            State.Driving -> if (parked) State.Parked else cur
            State.Parked -> when {
                // 기어 값을 잠깐 모르게 된 것(UNKNOWN)은 출발이 아니다 — 하차 판단을 이어 간다 (SM-36)
                !parked && s.gear != Gear.UNKNOWN -> State.Driving
                leftLongEnough -> {
                    val h = Handover(s.timestampMs, s.batterySoc, handoverItems(s))
                    tripWarnings.clear() // 이번 운행 기록은 인계로 넘어갔다
                    State.Away(h)
                }
                else -> cur
            }
            is State.Away -> {
                val h = refresh(cur.handover, s, whileAway = true)
                when {
                    // 앱이 꺼져 있는 사이 누군가 이미 출발한 경우: 이력에 남기고 주행 상태로
                    !parked && s.gear != Gear.UNKNOWN -> { archive(h); State.Driving }
                    // 바꿔 둔 설정은 다음 운전자가 앉는 순간 차가 '아직 그 상태'라고 알려 줄 때만 넘긴다.
                    // 시동을 켠 채 교대(휴게소·택시·대리)하면 그대로 넘어가고, 차가 꺼졌다 켜져 신호를 아직 모르면 넘기지 않는다.
                    // (현대·기아 ESC 꺼짐·SPORT 모드 등은 재시동하면 차가 스스로 되돌린다 — 취급설명서 확인 2026-11-11)
                    driverBoarded(s) -> State.Boarded(h.copy(
                        socAtBoard = s.batterySoc,
                        open = h.open.filter { it.type != OpenItem.Type.SETTING_CHANGED || detector.isKnown(it, s) },
                    ))
                    else -> State.Away(h)
                }
            }
            is State.Boarded -> {
                val h = refresh(cur.handover, s, whileAway = false)
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

    /**
     * 운행 중(주행·주차, 하차 전) 고장 경고등이 꺼짐 → 켜짐으로 바뀔 때마다 센다.
     * 다음 운전자가 탄 뒤(Boarded)나 차를 떠난 뒤(Away)의 경고는 그 사람의 운행이므로 세지 않는다.
     */
    private fun recordTrip(s: VehicleSnapshot) {
        val lamps = s.telltalesOn ?: return
        val counting = state == State.Driving || state == State.Parked
        // 누적 횟수가 있으면 그 증가분으로 센다 (중간값을 건너뛰어도 빠지지 않음). 없으면 켜짐 집합의 변화로 센다
        val newly: Map<String, Int> = if (s.lampCountsProvided || s.lampOnCounts.isNotEmpty()) {
            s.lampOnCounts.mapValues { (n, c) -> c - (countsBefore[n] ?: 0) }.filterValues { it > 0 }
        } else {
            (lamps - lampsBefore).associateWith { 1 }
        }
        if (counting && profile.hasTelltales) {
            for ((name, d) in newly) {
                if (Telltales.spec(name)?.kind == Telltales.Kind.FAULT) tripWarnings[name] = (tripWarnings[name] ?: 0) + d
            }
        }
        lampsBefore = lamps
        if (s.lampCountsProvided || s.lampOnCounts.isNotEmpty()) countsBefore = s.lampOnCounts
    }

    /** 차를 떠나는 순간 넘길 항목: 지금 상태 + 이번 운행 중 켜졌다 꺼진 경고 */
    private fun handoverItems(s: VehicleSnapshot): List<OpenItem> {
        val now = detector.detect(profile, s).map { item ->
            // 지금 켜진 경고에는 이번 운행 중 켜진 횟수를 함께 기록
            if (item.type == OpenItem.Type.WARNING_ON) item.copy(value = tripWarnings[item.code]) else item
        }
        val activeCodes = now.mapNotNull { it.code }.toSet()
        val past = tripWarnings.filterKeys { it !in activeCodes }
            .map { (code, count) -> OpenItem(OpenItem.Type.WARNING_DURING_TRIP, value = count, code = code) }
        return Detector.sort(now + past)
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
     * 확인 처리: 운전자가 봤다고 표시해 목록에서 내린다.
     * **지금 켜져 있는 안전 경고·안전 설정(ESC 꺼짐 등)은 확인으로 숨길 수 없다** — 신호로 해결될 때까지 보이고 출발 시 경고된다.
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
        tripWarnings.clear()
        history.clear()
        history += savedHistory.takeLast(maxHistory)
    }

    private fun archive(h: Handover) {
        history += h
        while (history.size > maxHistory) history.removeAt(0)
    }

    /**
     * 현재 신호로 다시 점검한다.
     * - 지금 상태 항목이 더 이상 감지되지 않으면 해결 처리 (탑승 후에만 '해결됨'으로 기록, 내린 뒤 앞사람이 고친 것은 조용히 빠짐)
     * - '지난 운행 중' 기록은 신호로 해결되지 않는다 (확인 처리로만 내림)
     * - 차를 떠나 있는 동안 저절로 생긴 고장·소모품·배터리 문제는 추가한다 (예: 며칠 주차 후 12V 배터리 경고)
     * - 다음 운전자가 탄 뒤 생긴 것은 추가하지 않는다 (그 사람의 운행)
     * - 신호 값을 모르면 기록 그대로 유지한다 (모름 ≠ 해결)
     */
    private fun refresh(h: Handover, s: VehicleSnapshot, whileAway: Boolean): Handover {
        val detected = detector.detect(profile, s).associateBy { it.key }
        val recordedKeys = h.open.map { it.key }.toSet()
        val stillOpen = h.open.mapNotNull { rec ->
            when {
                rec.type == OpenItem.Type.WARNING_DURING_TRIP -> rec
                detected[rec.key] != null -> detected.getValue(rec.key).let { if (it.type == OpenItem.Type.WARNING_ON) it.copy(value = rec.value) else it }
                !detector.isKnown(rec, s) -> rec
                else -> null
            }
        }
        val appeared = if (!whileAway) emptyList()
            else detected.values.filter { it.key !in recordedKeys && it.type != OpenItem.Type.SETTING_CHANGED }
        val open = Detector.sort(stillOpen + appeared)
        val openKeys = open.map { it.key }.toSet()
        val newlyResolved = if (whileAway) emptyList()
            else h.open.filter { it.key !in openKeys }.map { Resolved(it, s.timestampMs) }
        val newKeys = newlyResolved.map { it.item.key }.toSet()
        val resolved = h.resolved.filter { it.item.key !in newKeys && it.item.key !in openKeys } + newlyResolved
        // 해결된 항목의 확인 표시는 정리 (같은 항목이 다시 생기면 다시 보이도록)
        return h.copy(open = open, resolved = resolved, acknowledged = h.acknowledged intersect openKeys)
    }

    companion object {
        /** 지금 켜져 있는 안전 등급 항목은 숨길 수 없다. '지난 운행 중' 기록은 확인 가능 */
        fun canAcknowledge(item: OpenItem) =
            item.type == OpenItem.Type.WARNING_DURING_TRIP || item.tier != OpenItem.Tier.SAFETY
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
