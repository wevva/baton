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
    private val light = OpenItem(Type.CABIN_LIGHT_ON)
    private val lock = OpenItem(Type.DOORS_UNLOCKED)
    private val h = Handover(parkedAtMs = 100, socAtPark = 50, open = listOf(lock, light))

    @Test fun `AN-01 탑승 순간 요약 1회`() {
        val e = Announcer().decide(State.Away(h), State.Boarded(h))
        assertEquals(listOf(Event.Intro(listOf(lock, light))), e)
    }

    @Test fun `AN-02 탔다 내렸다 다시 타도 같은 요약을 반복하지 않는다`() {
        val a = Announcer()
        a.decide(State.Away(h), State.Boarded(h))
        assertTrue(a.decide(State.Away(h), State.Boarded(h)).isEmpty())
    }

    @Test fun `AN-03 수령증을 받은 인계는 다시 요약하지 않는다 (앱 재시작 후 복원 포함)`() {
        val received = h.copy(receivedAtMs = 200)
        assertTrue(Announcer().decide(State.Away(received), State.Boarded(received)).isEmpty())
    }

    @Test fun `AN-04 해결 안내는 새로 해결된 것만, 같은 개수로 교체돼도 놓치지 않는다`() {
        val b1 = h.copy(open = listOf(lock), resolved = listOf(Resolved(light, 10)))
        val b2 = h.copy(open = listOf(lock), resolved = listOf(Resolved(light, 20))) // 다시 켜졌다 다시 꺼짐
        assertEquals(listOf(Event.Resolved(light)), Announcer().decide(State.Boarded(b1), State.Boarded(b2)))
    }

    @Test fun `AN-05 변화 없으면 아무 말도 안 한다`() {
        assertTrue(Announcer().decide(State.Boarded(h), State.Boarded(h)).isEmpty())
    }

    @Test fun `AN-06 안전 항목 남긴 채 출발하면 경고 (할 일 확인 처리와 무관)`() {
        val acked = h.copy(acknowledged = setOf(light.key))
        assertEquals(listOf(Event.DepartWithSafety(listOf(lock))), Announcer().decide(State.Boarded(acked), State.Driving))
    }

    @Test fun `AN-07 할 일만 남기고 출발하면 경고 없음`() {
        val onlyTodo = h.copy(open = listOf(light))
        assertTrue(Announcer().decide(State.Boarded(onlyTodo), State.Driving).isEmpty())
    }

    @Test fun `AN-08 요약에는 확인 처리한 항목을 빼고 말한다`() {
        val acked = h.copy(acknowledged = setOf(light.key))
        assertEquals(listOf(Event.Intro(listOf(lock))), Announcer().decide(State.Away(acked), State.Boarded(acked)))
    }
}
