package com.baton.app.handover

import com.baton.app.handover.HandoverMachine.State
import com.baton.app.handover.OpenItem.Type
import com.baton.app.vehicle.Spot
import com.baton.app.vehicle.Spot.Side
import com.baton.app.vehicle.VehicleProfile
import com.baton.app.vehicle.VehicleSnapshot
import com.baton.app.vehicle.VehicleSnapshot.Gear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 상태 머신 테스트. 케이스 번호는 docs/TEST_CASES.md 의 ID와 같다.
 */
class HandoverMachineTest {

    private val driver = Spot(1, Side.LEFT)
    private val passenger = Spot(1, Side.RIGHT)
    private val rearLeft = Spot(2, Side.LEFT)
    private val sedan = VehicleProfile(
        doors = listOf(driver, passenger, rearLeft, Spot(2, Side.RIGHT)),
        windows = mapOf(driver to 10, rearLeft to 10),
        seats = listOf(driver, passenger, rearLeft, Spot(2, Side.RIGHT)),
        hasDoorLock = true, hasCabinLight = true, hasBatterySoc = true, hasTrunk = true,
    )

    /** 시간 t(ms)의 차량 상태. 기본값은 '주차 중, 모두 정상, 운전자 착석' */
    private fun snap(
        t: Long, gear: Gear = Gear.PARK, driverSeated: Boolean? = true, passengerSeated: Boolean = false,
        locked: Boolean? = true, rearWindow: Int = 0, cabin: Boolean = false, soc: Int? = 62,
        driverDoorOpen: Boolean = false, trunk: Boolean = false,
    ) = VehicleSnapshot(
        gear = gear, doorsLocked = locked,
        doorOpen = mapOf(driver to driverDoorOpen),
        trunkOpen = trunk,
        windowPos = mapOf(driver to 0, rearLeft to rearWindow),
        seatOccupied = buildMap {
            if (driverSeated != null) put(driver, driverSeated)
            put(passenger, passengerSeated); put(rearLeft, false); put(Spot(2, Side.RIGHT), false)
        },
        cabinLightOn = cabin, batterySoc = soc, timestampMs = t,
    )

    /**
     * 주행 → 주차 → (문제 남기고) 하차 2초 유지 → Away → (settle=true면) 문 닫힌 채 20초 경과해 일시 상태까지 확정
     */
    private fun leave(
        m: HandoverMachine, t0: Long = 0, locked: Boolean = false, rearWindow: Int = 4, cabin: Boolean = true,
        soc: Int = 62, settle: Boolean = true,
    ): State.Away {
        m.onSnapshot(snap(t0, gear = Gear.DRIVE))
        m.onSnapshot(snap(t0 + 100))
        m.onSnapshot(snap(t0 + 200, locked = locked, rearWindow = rearWindow, cabin = cabin, soc = soc))
        m.onSnapshot(snap(t0 + 300, driverSeated = false, locked = locked, rearWindow = rearWindow, cabin = cabin, soc = soc))
        val away = m.onTick(t0 + 2_400) as State.Away
        return if (settle) m.onTick(t0 + 2_400 + SETTLE) as State.Away else away
    }

    private val SETTLE = 20_000L

    // ── EX: 실제 하차 순서 (차를 떠날 때의 마지막 상태만 인계) ─────────────

    @Test fun `EX-01 정상 하차 - 내릴 땐 잠금 해제·실내등 켜짐, 내린 뒤 잠그고 자동 소등 → 인계 없음`() {
        val m = HandoverMachine(sedan)
        m.onSnapshot(snap(0, gear = Gear.DRIVE))
        m.onSnapshot(snap(100))
        // 시동 끄고 문 열고 내림: 잠금 해제·실내등 켜짐·운전석 문 열림
        m.onSnapshot(snap(200, driverSeated = false, locked = false, cabin = true, driverDoorOpen = true))
        val a0 = m.onTick(2_300) as State.Away
        assertTrue("내리는 순간에는 일시 상태를 기록하지 않음", a0.handover.open.isEmpty())
        m.onSnapshot(snap(4_000, driverSeated = false, locked = false, cabin = true)) // 문 닫음
        m.onSnapshot(snap(6_000, driverSeated = false, locked = true, cabin = true))  // 리모컨으로 잠금
        m.onSnapshot(snap(15_000, driverSeated = false, locked = true, cabin = false)) // 자동 소등
        val a = m.onTick(60_000) as State.Away
        assertTrue(a.handover.open.isEmpty())
        assertTrue("앞사람이 스스로 한 것은 '해결됨'으로 치지 않음", a.handover.resolved.isEmpty())
    }

    @Test fun `EX-02 문을 안 잠그고 떠남 - 문 닫힌 뒤 20초 지나면 '문 안 잠김' 확정`() {
        val m = HandoverMachine(sedan)
        m.onSnapshot(snap(0, gear = Gear.DRIVE)); m.onSnapshot(snap(100))
        m.onSnapshot(snap(200, driverSeated = false, locked = false, driverDoorOpen = true))
        m.onSnapshot(snap(3_000, driverSeated = false, locked = false)) // 문 닫음, 잠그지 않음
        assertTrue((m.onTick(22_000) as State.Away).handover.open.isEmpty()) // 아직 19초
        assertEquals(listOf(Type.DOORS_UNLOCKED), (m.onTick(23_100) as State.Away).handover.open.map { it.type })
    }

    @Test fun `EX-03 실내등을 켠 채 떠남 - 자동 소등 시간 지나도 켜져 있으면 확정`() {
        val m = HandoverMachine(sedan)
        m.onSnapshot(snap(0, gear = Gear.DRIVE)); m.onSnapshot(snap(100))
        m.onSnapshot(snap(200, driverSeated = false, cabin = true))
        m.onTick(2_300) // 하차 확정
        assertEquals(listOf(Type.CABIN_LIGHT_ON), (m.onTick(25_000) as State.Away).handover.open.map { it.type })
    }

    @Test fun `EX-04 문을 연 채 떠남 - 20초 넘게 열려 있으면 '문 열림' 확정, 잠깐 연 것은 제외`() {
        val m = HandoverMachine(sedan)
        m.onSnapshot(snap(0, gear = Gear.DRIVE)); m.onSnapshot(snap(100))
        m.onSnapshot(snap(200, driverSeated = false, driverDoorOpen = true))
        m.onTick(2_300) // 하차 확정
        m.onTick(10_000)
        assertTrue((m.state as State.Away).handover.open.none { it.type == Type.DOOR_OPEN })
        assertTrue((m.onTick(23_000) as State.Away).handover.open.any { it.type == Type.DOOR_OPEN })
    }

    @Test fun `EX-05 빠른 운전 교대(확정 전 탑승) - 교대 과정의 잠금 해제·문 열림은 인계하지 않음`() {
        val m = HandoverMachine(sedan)
        m.onSnapshot(snap(0, gear = Gear.DRIVE)); m.onSnapshot(snap(100))
        m.onSnapshot(snap(200, driverSeated = false, locked = false, driverDoorOpen = true, rearWindow = 4))
        m.onTick(2_300)
        val b = m.onSnapshot(snap(8_000, locked = false, driverDoorOpen = true, rearWindow = 4)) as State.Boarded
        assertEquals("창문(바로 확정 항목)만 인계", listOf(Type.WINDOW_OPEN), b.handover.open.map { it.type })
    }

    @Test fun `EX-06 창문은 내리는 순간 바로 인계된다`() {
        val a = leave(HandoverMachine(sedan), locked = true, cabin = false, rearWindow = 4, settle = false)
        assertEquals(listOf(Type.WINDOW_OPEN), a.handover.open.map { it.type })
    }

    @Test fun `EX-07 내린 뒤 앞사람이 창문을 닫으면 조용히 빠지고 '해결됨'으로 치지 않는다`() {
        val m = HandoverMachine(sedan)
        leave(m, locked = true, cabin = false, rearWindow = 4, settle = false)
        val a = m.onSnapshot(snap(5_000, driverSeated = false, rearWindow = 0)) as State.Away
        assertTrue(a.handover.open.isEmpty())
        assertTrue(a.handover.resolved.isEmpty())
    }

    @Test fun `EX-08 시스템 재시작 후 복원된 인계에는 다음 사람의 잠금 해제를 더하지 않는다`() {
        val m = HandoverMachine(sedan)
        m.restore(State.Away(HandoverMachine.Handover(1, 60, emptyList())), emptyList())
        val a = m.onSnapshot(snap(10, driverSeated = false, locked = false, driverDoorOpen = true)) as State.Away
        m.onTick(60_000)
        assertTrue((m.state as State.Away).handover.open.isEmpty())
        assertTrue(a.handover.open.isEmpty())
    }

    private fun boardSame(m: HandoverMachine, t: Long) =
        m.onSnapshot(snap(t, locked = false, rearWindow = 4, cabin = true)) as State.Boarded

    // ── SM: 기본 흐름 ─────────────────────────────────────────────

    @Test fun `SM-01 주행 중엔 아무 일도 없다`() {
        val m = HandoverMachine(sedan)
        assertEquals(State.Driving, m.onSnapshot(snap(0, gear = Gear.DRIVE, locked = false, cabin = true)))
    }

    @Test fun `SM-02 P단이면 주차 상태`() {
        val m = HandoverMachine(sedan)
        m.onSnapshot(snap(0, gear = Gear.DRIVE))
        assertEquals(State.Parked, m.onSnapshot(snap(100)))
    }

    @Test fun `SM-03 하차 2초 유지 시 미해결 항목을 우선순위 순으로 기억한다`() {
        val a = leave(HandoverMachine(sedan))
        assertEquals(listOf(Type.DOORS_UNLOCKED, Type.WINDOW_OPEN, Type.CABIN_LIGHT_ON), a.handover.open.map { it.type })
        assertEquals(62, a.handover.socAtPark)
    }

    @Test fun `SM-04 탑승하면 인계 상태, 탑승 시점 SoC 기록`() {
        val m = HandoverMachine(sedan)
        leave(m)
        val b = m.onSnapshot(snap(5_000, locked = false, rearWindow = 4, cabin = true, soc = 80)) as State.Boarded
        assertEquals(80, b.handover.socAtBoard)
        assertEquals(3, b.handover.open.size)
    }

    @Test fun `SM-05 해결되면 스스로 지우고 해결 시각을 남긴다`() {
        val m = HandoverMachine(sedan)
        leave(m)
        boardSame(m, 5_000)
        val b = m.onSnapshot(snap(6_000, locked = false, rearWindow = 0, cabin = true)) as State.Boarded
        assertEquals(listOf(Type.DOORS_UNLOCKED, Type.CABIN_LIGHT_ON), b.handover.open.map { it.type })
        assertEquals(6_000L, b.handover.resolved.single().atMs)
    }

    @Test fun `SM-06 출발하면 이력에 남기고 주행 상태`() {
        val m = HandoverMachine(sedan)
        leave(m)
        boardSame(m, 5_000)
        assertEquals(State.Driving, m.onSnapshot(snap(6_000, gear = Gear.DRIVE)))
        assertEquals(1, m.history.size)
    }

    @Test fun `SM-07 깨끗하게 주차하면 빈 인계`() {
        val a = leave(HandoverMachine(sedan), locked = true, rearWindow = 0, cabin = false)
        assertTrue(a.handover.allClear)
    }

    // ── SM: 운전자·좌석 경계 ─────────────────────────────────────

    @Test fun `SM-10 동승자가 남아 있어도 운전석이 비면 인계한다 (휴게소 운전 교대)`() {
        val m = HandoverMachine(sedan)
        m.onSnapshot(snap(0, gear = Gear.DRIVE, passengerSeated = true))
        m.onSnapshot(snap(100, passengerSeated = true, cabin = true))
        m.onSnapshot(snap(200, driverSeated = false, passengerSeated = true, cabin = true))
        assertTrue(m.onTick(2_300) is State.Away)
    }

    @Test fun `SM-11 운전석 센서 흔들림(2초 미만 비움)은 하차가 아니다`() {
        val m = HandoverMachine(sedan)
        m.onSnapshot(snap(0, gear = Gear.DRIVE))
        m.onSnapshot(snap(100))
        m.onSnapshot(snap(200, driverSeated = false))
        m.onSnapshot(snap(900, driverSeated = true))
        assertEquals(State.Parked, m.onTick(5_000))
    }

    @Test fun `SM-12 하차 직후(2초 전)에는 아직 주차 상태`() {
        val m = HandoverMachine(sedan)
        m.onSnapshot(snap(0, gear = Gear.DRIVE))
        m.onSnapshot(snap(100))
        m.onSnapshot(snap(200, driverSeated = false))
        assertEquals(State.Parked, m.onTick(1_500))
    }

    @Test fun `SM-13 좌석 값을 모르면 하차로 판단하지 않는다`() {
        val m = HandoverMachine(sedan)
        m.onSnapshot(snap(0, gear = Gear.DRIVE))
        m.onSnapshot(snap(100, driverSeated = null))
        assertEquals(State.Parked, m.onTick(10_000))
    }

    @Test fun `SM-14 탔다가 출발 안 하고 다시 내리면 같은 인계 유지 (수령 기록 포함)`() {
        val m = HandoverMachine(sedan)
        leave(m)
        boardSame(m, 5_000)
        m.receive(5_100)
        m.onSnapshot(snap(6_000, driverSeated = false, locked = false, rearWindow = 4, cabin = true))
        val a = m.onTick(8_500) as State.Away
        assertEquals(5_100L, a.handover.receivedAtMs)
        assertEquals(3, a.handover.open.size)
    }

    @Test fun `SM-15 착석 센서 없는 차는 P단+잠금을 하차로, 잠금 해제를 탑승으로 본다`() {
        val noSeats = sedan.copy(seats = emptyList())
        val m = HandoverMachine(noSeats)
        m.onSnapshot(VehicleSnapshot(gear = Gear.DRIVE, timestampMs = 0))
        m.onSnapshot(VehicleSnapshot(gear = Gear.PARK, doorsLocked = false, cabinLightOn = true, timestampMs = 100))
        m.onSnapshot(VehicleSnapshot(gear = Gear.PARK, doorsLocked = true, cabinLightOn = true, timestampMs = 200))
        assertTrue(m.onTick(2_300) is State.Away)
        val b = m.onSnapshot(VehicleSnapshot(gear = Gear.PARK, doorsLocked = false, cabinLightOn = true, timestampMs = 3_000))
        assertTrue(b is State.Boarded)
        // 다음 사람이 잠금을 푼 것은 인계 항목이 아니다
        assertTrue((b as State.Boarded).handover.open.none { it.type == Type.DOORS_UNLOCKED })
    }

    // ── SM: 오탐 방지 ─────────────────────────────────────────────

    @Test fun `SM-20 다음 운전자가 잠금을 풀고 타도 '문 안 잠김'을 인계로 오인하지 않는다`() {
        val m = HandoverMachine(sedan)
        val a = leave(m, locked = true, rearWindow = 0, cabin = false)
        assertTrue(a.handover.allClear)
        m.onSnapshot(snap(4_000, driverSeated = false, locked = false, driverDoorOpen = true)) // 잠금 해제, 문 열고
        val b = m.onSnapshot(snap(4_500, locked = false, driverDoorOpen = true)) as State.Boarded
        assertTrue(b.handover.allClear)
    }

    @Test fun `SM-21 주차 중 배터리가 떨어지면 새 항목으로 추가된다`() {
        val m = HandoverMachine(sedan)
        leave(m, locked = true, rearWindow = 0, cabin = false, soc = 25)
        val a = m.onSnapshot(snap(10_000, driverSeated = false, soc = 18)) as State.Away
        assertEquals(Type.LOW_BATTERY, a.handover.open.single().type)
        assertEquals(18, a.handover.open.single().value)
    }

    @Test fun `SM-22 창문을 반쯤 닫으면 값만 갱신되고 해결로 치지 않는다`() {
        val m = HandoverMachine(sedan)
        leave(m, locked = true, cabin = false, rearWindow = 8)
        val b = m.onSnapshot(snap(5_000, rearWindow = 4)) as State.Boarded
        assertEquals(40, b.handover.open.single().value)
        assertTrue(b.handover.resolved.isEmpty())
    }

    @Test fun `SM-23 해결 후 다시 열고 다시 해결해도 해결 기록은 1건 (화면 목록 키 중복 방지)`() {
        val m = HandoverMachine(sedan)
        leave(m, locked = true, cabin = false, rearWindow = 4)
        m.onSnapshot(snap(5_000, rearWindow = 4))
        m.onSnapshot(snap(6_000, rearWindow = 0))
        m.onSnapshot(snap(7_000, rearWindow = 4)) // 다음 운전자가 연 것 → 새 항목 아님
        val b = m.onSnapshot(snap(8_000, rearWindow = 0)) as State.Boarded
        assertEquals(1, b.handover.resolved.size)
        assertTrue(b.handover.open.isEmpty())
    }

    @Test fun `SM-24 재시작 직후 신호 값을 모르면 기록된 항목을 해결로 치지 않는다 (모름 ≠ 해결)`() {
        val saved = HandoverMachine.Handover(1, 70, listOf(OpenItem(Type.CABIN_LIGHT_ON), OpenItem(Type.WINDOW_OPEN, rearLeft, 40)))
        val m = HandoverMachine(sedan)
        m.restore(State.Away(saved), emptyList())
        // 기어·좌석만 먼저 도착, 실내등·창문 값은 아직 없음
        val first = m.onSnapshot(VehicleSnapshot(gear = Gear.PARK, seatOccupied = mapOf(driver to true), timestampMs = 10)) as State.Boarded
        assertEquals(2, first.handover.open.size)
        assertTrue(first.handover.resolved.isEmpty())
        // 값이 도착하면 그때 판정
        val later = m.onSnapshot(snap(20, cabin = false, rearWindow = 4)) as State.Boarded
        assertEquals(listOf(Type.WINDOW_OPEN), later.handover.open.map { it.type })
        assertEquals(Type.CABIN_LIGHT_ON, later.handover.resolved.single().item.type)
    }

    // ── SM: 기어·순서 이상 ────────────────────────────────────────

    @Test fun `SM-30 기어를 모르면(UNKNOWN) 출발로 보지 않는다`() {
        val m = HandoverMachine(sedan)
        leave(m)
        boardSame(m, 5_000)
        assertTrue(m.onSnapshot(snap(6_000, gear = Gear.UNKNOWN, locked = false, rearWindow = 4, cabin = true)) is State.Boarded)
    }

    @Test fun `SM-31 후진(R)·중립(N)도 출발로 본다`() {
        for (g in listOf(Gear.REVERSE, Gear.NEUTRAL)) {
            val m = HandoverMachine(sedan)
            leave(m)
            boardSame(m, 5_000)
            assertEquals("gear=$g", State.Driving, m.onSnapshot(snap(6_000, gear = g)))
        }
    }

    @Test fun `SM-32 앱이 꺼진 사이 출발했다면 이력으로 넘긴다`() {
        val m = HandoverMachine(sedan)
        m.restore(State.Away(HandoverMachine.Handover(1, 70, emptyList())), emptyList())
        assertEquals(State.Driving, m.onSnapshot(snap(10, gear = Gear.DRIVE)))
        assertEquals(1, m.history.size)
    }

    @Test fun `SM-33 같은 스냅샷이 반복돼도 상태가 흔들리지 않는다`() {
        val m = HandoverMachine(sedan)
        val a = leave(m)
        repeat(50) { i -> m.onSnapshot(snap(3_000L + i, driverSeated = false, locked = false, rearWindow = 4, cabin = true)) }
        assertEquals(a.handover.open, (m.state as State.Away).handover.open)
    }

    @Test fun `SM-34 시계가 거꾸로 가도(시간 역행) 예외 없이 동작한다`() {
        val m = HandoverMachine(sedan)
        m.onSnapshot(snap(10_000, gear = Gear.DRIVE))
        m.onSnapshot(snap(9_000))
        m.onSnapshot(snap(8_000, driverSeated = false))
        assertEquals(State.Parked, m.onTick(7_000))
    }

    @Test fun `SM-35 SoC 값이 없어도 동작한다`() {
        val m = HandoverMachine(sedan)
        val a = leave(m, soc = 62)
        assertEquals(62, a.handover.socAtPark)
        val b = m.onSnapshot(snap(5_000, locked = false, rearWindow = 4, cabin = true, soc = null)) as State.Boarded
        assertNull(b.handover.socAtBoard)
    }

    // ── SM: 수령증·복원·이력 ──────────────────────────────────────

    @Test fun `SM-40 수령증은 탑승 상태에서 한 번만 기록된다`() {
        val m = HandoverMachine(sedan)
        leave(m)
        boardSame(m, 5_000)
        m.receive(5_500)
        assertEquals(5_500L, (m.receive(9_999) as State.Boarded).handover.receivedAtMs)
    }

    @Test fun `SM-41 탑승 전(Away)에는 수령증을 누를 수 없다`() {
        val m = HandoverMachine(sedan)
        leave(m)
        assertNull((m.receive(4_000) as State.Away).handover.receivedAtMs)
    }

    @Test fun `SM-42 저장된 인계를 복원하면 이어서 동작한다`() {
        val saved = HandoverMachine.Handover(parkedAtMs = 1, socAtPark = 70, open = listOf(OpenItem(Type.CABIN_LIGHT_ON)))
        val m = HandoverMachine(sedan)
        m.restore(State.Away(saved), emptyList())
        val b = m.onSnapshot(snap(10, cabin = true)) as State.Boarded
        assertEquals(70, b.handover.socAtPark)
        assertEquals(1, b.handover.open.size)
    }

    @Test fun `SM-43 이력은 최대 50건만 보관한다`() {
        val m = HandoverMachine(sedan)
        m.restore(State.Driving, List(60) { HandoverMachine.Handover(it.toLong(), null, emptyList()) })
        assertEquals(50, m.history.size)
        assertEquals(10L, m.history.first().parkedAtMs)
    }

    @Test fun `SM-44 할 일 항목은 확인 처리로 숨길 수 있고 되돌릴 수 있다`() {
        val m = HandoverMachine(sedan)
        leave(m)
        boardSame(m, 5_000)
        val light = OpenItem(Type.CABIN_LIGHT_ON).key
        assertTrue(m.acknowledge(light))
        assertTrue((m.state as State.Boarded).handover.visible.none { it.key == light })
        assertTrue(m.unacknowledge(light))
        assertTrue((m.state as State.Boarded).handover.visible.any { it.key == light })
    }

    @Test fun `SM-45 안전 항목은 확인 처리로 숨길 수 없다`() {
        val m = HandoverMachine(sedan)
        leave(m)
        boardSame(m, 5_000)
        assertFalse(m.acknowledge(OpenItem(Type.DOORS_UNLOCKED).key))
        assertTrue((m.state as State.Boarded).handover.visible.any { it.type == Type.DOORS_UNLOCKED })
    }

    @Test fun `SM-46 탑승 전(Away)·없는 항목은 확인 처리 불가`() {
        val m = HandoverMachine(sedan)
        leave(m)
        assertFalse(m.acknowledge(OpenItem(Type.CABIN_LIGHT_ON).key))
        boardSame(m, 5_000)
        assertFalse(m.acknowledge("NOPE:-"))
    }

    @Test fun `SM-47 확인 처리한 항목이 해결되면 확인 표시도 정리된다`() {
        val m = HandoverMachine(sedan)
        leave(m)
        boardSame(m, 5_000)
        m.acknowledge(OpenItem(Type.CABIN_LIGHT_ON).key)
        val b = m.onSnapshot(snap(6_000, locked = false, rearWindow = 4, cabin = false)) as State.Boarded
        assertTrue(b.handover.acknowledged.isEmpty())
    }

    // ── DT: 탐지 ─────────────────────────────────────────────────

    @Test fun `DT-01 프로필에 없는 부위는 보지 않는다`() {
        val items = Detector().detect(sedan.copy(windows = emptyMap(), hasCabinLight = false), snap(0, rearWindow = 10, cabin = true))
        assertTrue(items.none { it.type == Type.WINDOW_OPEN || it.type == Type.CABIN_LIGHT_ON })
    }

    @Test fun `DT-02 창문 10% 미만은 닫힌 것으로 본다 (센서 오차)`() {
        val wide = sedan.copy(windows = mapOf(rearLeft to 100))
        assertTrue(Detector().detect(wide, snap(0, rearWindow = 9)).none { it.type == Type.WINDOW_OPEN })
        assertEquals(10, Detector().detect(wide, snap(0, rearWindow = 10)).single { it.type == Type.WINDOW_OPEN }.value)
    }

    @Test fun `DT-03 배터리 20% 이하만 부족, 21%는 아님`() {
        assertTrue(Detector().detect(sedan, snap(0, soc = 21)).none { it.type == Type.LOW_BATTERY })
        assertEquals(20, Detector().detect(sedan, snap(0, soc = 20)).single { it.type == Type.LOW_BATTERY }.value)
    }

    @Test fun `DT-04 잠금 값을 모르면(null) 잠금 항목을 만들지 않는다`() {
        assertTrue(Detector().detect(sedan, snap(0, locked = null)).none { it.type == Type.DOORS_UNLOCKED })
    }

    @Test fun `DT-05 문·트렁크 열림 감지`() {
        val items = Detector().detect(sedan, snap(0, driverDoorOpen = true, trunk = true))
        assertTrue(items.any { it.type == Type.DOOR_OPEN && it.spot == driver })
        assertTrue(items.any { it.type == Type.TRUNK_OPEN })
    }

    @Test fun `DT-06 안전 항목이 항상 먼저`() {
        val items = Detector().detect(sedan, snap(0, locked = false, rearWindow = 10, cabin = true, soc = 5, trunk = true))
        val tiers = items.map { it.type.tier.ordinal }
        assertEquals(tiers.sorted(), tiers)
    }

    @Test fun `DT-07 최대값이 0인 창문(잘못된 범위)은 무시한다`() {
        val bad = sedan.copy(windows = mapOf(rearLeft to 0))
        assertTrue(Detector().detect(bad, snap(0, rearWindow = 5)).none { it.type == Type.WINDOW_OPEN })
    }

    @Test fun `DT-08 12인승(4열)도 같은 방식으로 처리한다`() {
        val van = VehicleProfile(seats = (1..4).flatMap { r -> Side.entries.map { Spot(r, it) } }, windows = mapOf(Spot(3, Side.RIGHT) to 10))
        assertEquals(12, van.seatCount)
        assertEquals(4, van.rows)
        val items = Detector().detect(van, VehicleSnapshot(windowPos = mapOf(Spot(3, Side.RIGHT) to 5)))
        assertEquals("R3R", items.single().spot?.code)
    }

    @Test fun `DT-09 브레이크액 부족은 안전 항목, 프로필에 없으면 무시`() {
        val withBrake = sedan.copy(hasBrakeFluid = true)
        val s = VehicleSnapshot(brakeFluidLow = true, timestampMs = 0)
        assertEquals(OpenItem.Tier.SAFETY, Detector().detect(withBrake, s).single().type.tier)
        assertTrue(Detector().detect(sedan, s).isEmpty())
    }

    @Test fun `SM-25 주차 중 브레이크액 경고가 생기면 다음 운전자에게 넘긴다`() {
        val m = HandoverMachine(sedan.copy(hasBrakeFluid = true))
        leave(m, locked = true, rearWindow = 0, cabin = false)
        val a = m.onSnapshot(snap(10_000, driverSeated = false).copy(brakeFluidLow = true)) as State.Away
        assertEquals(Type.BRAKE_FLUID_LOW, a.handover.open.single().type)
    }

    @Test fun `SM-16 우핸들 차량(일본·영국)은 1열 오른쪽을 운전석으로 본다`() {
        val rhd = sedan.copy(driverSeat = passenger)
        val m = HandoverMachine(rhd)
        m.onSnapshot(snap(0, gear = Gear.DRIVE, passengerSeated = true))
        // 1열 왼쪽(우핸들의 조수석)이 비어도 하차 아님
        m.onSnapshot(snap(100, driverSeated = false, passengerSeated = true))
        assertEquals(State.Parked, m.onTick(5_000))
        // 1열 오른쪽(운전석)이 비면 하차
        m.onSnapshot(snap(6_000, driverSeated = false, passengerSeated = false))
        assertTrue(m.onTick(8_500) is State.Away)
    }

    // ── SP: 위치 코드 ─────────────────────────────────────────────

    @Test fun `SP-01 위치 코드는 왕복 변환된다`() {
        for (r in 1..4) for (side in Side.entries) assertEquals(Spot(r, side), Spot.fromCode(Spot(r, side).code))
    }

    @Test fun `SP-02 잘못된 위치 코드는 null`() {
        for (bad in listOf("", "R", "RXL", "R1X", "R10L", "r1l", "R1L;DROP")) assertNull(bad, Spot.fromCode(bad))
    }

    @Test fun `SP-03 운전석 기본값과 좌석 정보 유무`() {
        assertFalse(VehicleSnapshot().seatsKnown)
        assertEquals(Spot(1, Side.LEFT), VehicleSnapshot.DRIVER_SEAT)
    }
}
