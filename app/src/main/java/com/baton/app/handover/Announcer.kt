package com.baton.app.handover

import com.baton.app.handover.HandoverMachine.State

/**
 * 상태 변화에서 '무엇을 말할지'를 정한다 (순수 로직 — 문장·음성 엔진과 분리해 단위 테스트).
 * 원칙: 같은 인계 요약은 한 번만, 해결은 해결된 순간에 한 번씩, 출발 경고는 한 번만.
 */
class Announcer {
    sealed interface Event {
        /** 탑승 순간 인계 요약 (남은 항목이 없으면 '깨끗함') */
        data class Intro(val open: List<OpenItem>) : Event
        /** 신호로 해결이 확인된 항목 */
        data class Resolved(val item: OpenItem) : Event
        /** 안전 항목을 남긴 채 출발 */
        data class DepartWithSafety(val items: List<OpenItem>) : Event
    }

    /** 이미 요약을 들려준 인계 (parkedAtMs로 구분). 탔다 내렸다 다시 타도 반복하지 않는다 */
    private val introduced = LinkedHashSet<Long>()

    fun decide(before: State, after: State): List<Event> {
        val events = mutableListOf<Event>()
        if (after is State.Boarded && before !is State.Boarded) {
            val h = after.handover
            // 수령증까지 받은 인계이거나 이미 들려준 인계면 다시 말하지 않는다
            if (h.receivedAtMs == null && introduced.add(h.parkedAtMs)) {
                events += Event.Intro(h.visible)
                while (introduced.size > 20) introduced.remove(introduced.first())
            }
        }
        if (before is State.Boarded && after is State.Boarded) {
            // 해결 목록에 새로 들어온 것(항목+시각이 이전에 없던 것)만
            val old = before.handover.resolved.map { it.item.key to it.atMs }.toSet()
            after.handover.resolved.filter { (it.item.key to it.atMs) !in old }.forEach { events += Event.Resolved(it.item) }
        }
        if (before is State.Boarded && after is State.Driving) {
            // 지금 켜진 안전 항목은 확인 처리로 숨길 수 없으므로 모두 경고. '지난 운행 중' 기록은 확인했으면 생략
            val h = before.handover
            val safety = h.open.filter {
                it.tier == OpenItem.Tier.SAFETY && (it.type != OpenItem.Type.WARNING_DURING_TRIP || it.key !in h.acknowledged)
            }
            if (safety.isNotEmpty()) events += Event.DepartWithSafety(safety)
        }
        return events
    }
}
