package com.baton.app.handover

import com.baton.app.handover.HandoverMachine.State
import com.baton.app.vehicle.Spot
import com.baton.app.vehicle.Spot.Side
import com.baton.app.vehicle.VehicleProfile
import com.baton.app.vehicle.VehicleSnapshot
import com.baton.app.vehicle.VehicleSnapshot.Gear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoverMachineTest {

    private val driver = Spot(1, Side.LEFT)
    private val rearLeft = Spot(2, Side.LEFT)
    private val sedan = VehicleProfile(
        doors = listOf(driver, Spot(1, Side.RIGHT), rearLeft, Spot(2, Side.RIGHT)),
        windows = mapOf(driver to 10, rearLeft to 10),
        seats = listOf(driver, Spot(1, Side.RIGHT), rearLeft, Spot(2, Side.RIGHT)),
        hasDoorLock = true, hasCabinLight = true, hasBatterySoc = true,
    )

    private fun snap(
        gear: Gear = Gear.PARK, seated: Boolean = false, locked: Boolean = true,
        rearWindow: Int = 0, cabin: Boolean = false, soc: Int = 62, t: Long = 0,
    ) = VehicleSnapshot(
        gear = gear, doorsLocked = locked, windowPos = mapOf(driver to 0, rearLeft to rearWindow),
        seatOccupied = sedan.seats.associateWith { false } + (driver to seated), cabinLightOn = cabin, batterySoc = soc, timestampMs = t,
    )

    @Test fun 하차하면_미해결_항목을_기억한다() {
        val m = HandoverMachine(sedan)
        m.onSnapshot(snap(gear = Gear.DRIVE, seated = true))
        m.onSnapshot(snap(seated = true))
        val s = m.onSnapshot(snap(seated = false, locked = false, rearWindow = 3, cabin = true, t = 100))
        assertTrue(s is State.Away)
        val types = (s as State.Away).handover.open.map { it.type }
        assertEquals(listOf(OpenItem.Type.DOORS_UNLOCKED, OpenItem.Type.WINDOW_OPEN, OpenItem.Type.CABIN_LIGHT_ON), types)
        assertEquals(62, s.handover.socAtPark)
    }

    @Test fun 해결되면_스스로_지운다() {
        val m = HandoverMachine(sedan)
        m.onSnapshot(snap(seated = true))
        m.onSnapshot(snap(seated = false, rearWindow = 3, cabin = true))
        val boarded = m.onSnapshot(snap(seated = true, rearWindow = 3, cabin = true, t = 200)) as State.Boarded
        assertEquals(2, boarded.handover.open.size)
        val after = m.onSnapshot(snap(seated = true, rearWindow = 0, cabin = true, t = 300)) as State.Boarded
        assertEquals(listOf(OpenItem.Type.CABIN_LIGHT_ON), after.handover.open.map { it.type })
        assertEquals(OpenItem.Type.WINDOW_OPEN, after.handover.resolved.single().item.type)
        assertEquals(300L, after.handover.resolved.single().atMs)
    }

    @Test fun 출발하면_이력에_남기고_주행상태로() {
        val m = HandoverMachine(sedan)
        m.onSnapshot(snap(seated = true))
        m.onSnapshot(snap(seated = false, soc = 62))
        m.onSnapshot(snap(seated = true, soc = 60))
        val s = m.onSnapshot(snap(gear = Gear.DRIVE, seated = true, soc = 60))
        assertEquals(State.Driving, s)
        assertEquals(1, m.history.size)
        assertEquals(62, m.history[0].socAtPark)
        assertEquals(60, m.history[0].socAtBoard)
    }

    @Test fun 깨끗하게_주차하면_빈_인계() {
        val m = HandoverMachine(sedan)
        m.onSnapshot(snap(seated = true))
        val s = m.onSnapshot(snap(seated = false)) as State.Away
        assertTrue(s.handover.allClear)
    }

    @Test fun 프로필에_없는_부위는_보지_않는다() {
        // 창문 정보가 없는 차: 창문 값이 들어와도 항목을 만들지 않음
        val noWindows = sedan.copy(windows = emptyMap())
        val items = Detector().detect(noWindows, snap(rearWindow = 10))
        assertTrue(items.none { it.type == OpenItem.Type.WINDOW_OPEN })
    }

    @Test fun 열두인승도_같은_방식으로_처리한다() {
        val van = VehicleProfile(
            seats = (1..4).flatMap { r -> Side.entries.map { Spot(r, it) } },
            windows = mapOf(Spot(3, Side.RIGHT) to 10),
        )
        assertEquals(12, van.seatCount)
        assertEquals(4, van.rows)
        val items = Detector().detect(van, VehicleSnapshot(windowPos = mapOf(Spot(3, Side.RIGHT) to 5)))
        assertEquals("R3R", items.single().spot?.code)
    }

    @Test fun 좌석_값을_모르면_하차로_판단하지_않는다() {
        val m = HandoverMachine(sedan)
        m.onSnapshot(VehicleSnapshot(gear = Gear.DRIVE))
        val s = m.onSnapshot(VehicleSnapshot(gear = Gear.PARK, doorsLocked = false)) // 착석 값 아직 없음
        assertEquals(State.Parked, s)
    }

    @Test fun 일부_좌석만_알면_하차로_판단하지_않는다() {
        val m = HandoverMachine(sedan)
        m.onSnapshot(VehicleSnapshot(gear = Gear.PARK))
        val s = m.onSnapshot(VehicleSnapshot(gear = Gear.PARK, seatOccupied = mapOf(rearLeft to false)))
        assertEquals(State.Parked, s)
    }

    @Test fun 저장된_인계를_복원하면_이어서_동작한다() {
        val saved = HandoverMachine.Handover(parkedAtMs = 1, socAtPark = 70, open = listOf(OpenItem(OpenItem.Type.CABIN_LIGHT_ON)))
        val m = HandoverMachine(sedan)
        m.restore(State.Away(saved), emptyList())
        val s = m.onSnapshot(snap(seated = true, cabin = true)) as State.Boarded
        assertEquals(70, s.handover.socAtPark)
        assertEquals(1, s.handover.open.size)
    }

    @Test fun 앱이_꺼진_사이_출발했다면_이력으로_넘긴다() {
        val m = HandoverMachine(sedan)
        m.restore(State.Away(HandoverMachine.Handover(1, 70, emptyList())), emptyList())
        assertEquals(State.Driving, m.onSnapshot(snap(gear = Gear.DRIVE, seated = true)))
        assertEquals(1, m.history.size)
    }

    @Test fun 위치코드는_왕복_변환된다() {
        assertEquals(Spot(3, Side.CENTER), Spot.fromCode("R3C"))
    }
}
