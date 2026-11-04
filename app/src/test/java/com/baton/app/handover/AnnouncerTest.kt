package com.baton.app.handover

import com.baton.app.handover.Announcer.Event
import com.baton.app.handover.HandoverMachine.Handover
import com.baton.app.handover.HandoverMachine.Resolved
import com.baton.app.handover.HandoverMachine.State
import com.baton.app.handover.OpenItem.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 음성 안내 판단 (AN-xx): 알림 피로 없이, 빠짐없이 */
class AnnouncerTest {
    private val tpms = OpenItem(Type.WARNING_ON, code = "TPMS")            // 안전, 지금 켜짐
    private val ipedal = OpenItem(Type.SETTING_CHANGED, code = "I_PEDAL")  // 할 일
    private val absTrip = OpenItem(Type.WARNING_DURING_TRIP, value = 2, code = "ABS") // 안전 등급, 지난 운행 기록
    private val h = Handover(parkedAtMs = 100, socAtPark = 50, open = listOf(tpms, ipedal))

    @Test fun `AN-01 탑승 순간 요약 1회`() {
        assertEquals(listOf(Event.Intro(listOf(tpms, ipedal))), Announcer().decide(State.Away(h), State.Boarded(h)))
    }

    @Test fun `AN-02 탔다 내렸다 다시 타도 같은 요약을 반복하지 않는다`() {
        val a = Announcer()
        a.decide(State.Away(h), State.Boarded(h))
        assertTrue(a.decide(State.Away(h), State.Boarded(h)).isEmpty())
    }

    @Test fun `AN-03 수령증을 받은 인계는 다시 요약하지 않는다`() {
        val received = h.copy(receivedAtMs = 200)
        assertTrue(Announcer().decide(State.Away(received), State.Boarded(received)).isEmpty())
    }

    @Test fun `AN-04 해결 안내는 새로 해결된 것만, 같은 개수로 교체돼도 놓치지 않는다`() {
        val b1 = h.copy(open = listOf(tpms), resolved = listOf(Resolved(ipedal, 10)))
        val b2 = h.copy(open = listOf(tpms), resolved = listOf(Resolved(ipedal, 20)))
        assertEquals(listOf(Event.Resolved(ipedal)), Announcer().decide(State.Boarded(b1), State.Boarded(b2)))
    }

    @Test fun `AN-05 변화 없으면 아무 말도 안 한다`() {
        assertTrue(Announcer().decide(State.Boarded(h), State.Boarded(h)).isEmpty())
    }

    @Test fun `AN-06 지금 켜진 안전 경고를 남긴 채 출발하면 경고`() {
        assertEquals(listOf(Event.DepartWithSafety(listOf(tpms))), Announcer().decide(State.Boarded(h), State.Driving))
    }

    @Test fun `AN-07 할 일만 남기고 출발하면 경고 없음`() {
        assertTrue(Announcer().decide(State.Boarded(h.copy(open = listOf(ipedal))), State.Driving).isEmpty())
    }

    @Test fun `AN-08 요약에는 확인 처리한 항목을 빼고 말한다`() {
        val acked = h.copy(acknowledged = setOf(ipedal.key))
        assertEquals(listOf(Event.Intro(listOf(tpms))), Announcer().decide(State.Away(acked), State.Boarded(acked)))
    }

    @Test fun `AN-09 지난 운행 중 안전 경고는 확인했으면 출발 경고 생략, 안 했으면 경고`() {
        val withTrip = h.copy(open = listOf(absTrip))
        assertEquals(listOf(Event.DepartWithSafety(listOf(absTrip))), Announcer().decide(State.Boarded(withTrip), State.Driving))
        val acked = withTrip.copy(acknowledged = setOf(absTrip.key))
        assertTrue(Announcer().decide(State.Boarded(acked), State.Driving).isEmpty())
    }
}
