package com.baton.app.handover

import com.baton.app.handover.HandoverMachine.State
import com.baton.app.handover.OpenItem.Tier
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
 * 넘기는 것: 지금 켜진 고장 경고, 지난 운행 중 켜졌던 경고(횟수), 바꿔 둔 설정, 소모품, 배터리.
 */
class HandoverMachineTest {

    private val driver = Spot(1, Side.LEFT)
    private val passenger = Spot(1, Side.RIGHT)
    private val car = VehicleProfile(
        seats = listOf(driver, passenger, Spot(2, Side.LEFT), Spot(2, Side.RIGHT)),
        hasDoorLock = true, hasBatterySoc = true, hasBrakeFluid = true, hasEngineOil = true,
        hasTransmissionOil = true, hasWasherFluid = true, hasTelltales = true,
    )

    /** 시간 t(ms)의 차량 상태. 기본값은 '주차 중, 경고 없음, 운전자 착석' */
    private fun snap(
        t: Long, gear: Gear = Gear.PARK, driverSeated: Boolean? = true, passengerSeated: Boolean = false,
        lamps: Set<String>? = emptySet(), soc: Int? = 62, brake: Boolean? = false, oil: Boolean? = false,
        washer: Boolean? = false, locked: Boolean? = true,
    ) = VehicleSnapshot(
        gear = gear, doorsLocked = locked,
        seatOccupied = buildMap {
            if (driverSeated != null) put(driver, driverSeated)
            put(passenger, passengerSeated); put(Spot(2, Side.LEFT), false); put(Spot(2, Side.RIGHT), false)
        },
        batterySoc = soc, brakeFluidLow = brake, engineOilLow = oil, transmissionOilLow = false, washerFluidLow = washer,
        telltalesOn = lamps, timestampMs = t,
    )

    /** 운전석 비움 → 2초 유지 → Away */
    private fun exit(m: HandoverMachine, t: Long, lamps: Set<String> = emptySet(), soc: Int = 62, brake: Boolean = false, oil: Boolean = false, washer: Boolean = false): State.Away {
        m.onSnapshot(snap(t, driverSeated = false, lamps = lamps, soc = soc, brake = brake, oil = oil, washer = washer))
        return m.onTick(t + 2_100) as State.Away
    }

    private fun board(m: HandoverMachine, t: Long, lamps: Set<String> = emptySet(), soc: Int = 62) =
        m.onSnapshot(snap(t, lamps = lamps, soc = soc)) as State.Boarded

    /** 주행 중 경고등이 켜졌다 꺼지는 운행 (lampsSeq: 순서대로 켜진 경고 집합) */
    private fun drive(m: HandoverMachine, t0: Long, vararg lampsSeq: Set<String>) {
        var t = t0
        m.onSnapshot(snap(t, gear = Gear.DRIVE)); t += 100
        for (l in lampsSeq) { m.onSnapshot(snap(t, gear = Gear.DRIVE, lamps = l)); t += 100 }
        m.onSnapshot(snap(t, gear = Gear.DRIVE)); t += 100
        m.onSnapshot(snap(t)) // P단
    }

    // ── SM: 기본 흐름 ─────────────────────────────────────────────

    @Test fun `SM-01 주행 중엔 인계가 생기지 않는다`() {
        val m = HandoverMachine(car)
        assertEquals(State.Driving, m.onSnapshot(snap(0, gear = Gear.DRIVE, lamps = setOf("TPMS"))))
    }

    @Test fun `SM-02 P단이면 주차 상태`() {
        val m = HandoverMachine(car)
        m.onSnapshot(snap(0, gear = Gear.DRIVE))
        assertEquals(State.Parked, m.onSnapshot(snap(100)))
    }

    @Test fun `SM-03 아무 문제 없으면 빈 인계 (DVIR 원칙 - 결함 없으면 보고서 불필요)`() {
        val m = HandoverMachine(car)
        drive(m, 0)
        assertTrue(exit(m, 1_000).handover.allClear)
    }

    @Test fun `SM-04 탑승하면 인계 상태, 주차·탑승 시점 SoC 기록`() {
        val m = HandoverMachine(car)
        drive(m, 0)
        exit(m, 1_000, soc = 35)
        val b = board(m, 9_000, soc = 80)
        assertEquals(35, b.handover.socAtPark)
        assertEquals(80, b.handover.socAtBoard)
    }

    @Test fun `SM-06 출발하면 이력에 남기고 주행 상태`() {
        val m = HandoverMachine(car)
        drive(m, 0, setOf("TPMS"))
        exit(m, 1_000); board(m, 9_000)
        assertEquals(State.Driving, m.onSnapshot(snap(10_000, gear = Gear.DRIVE)))
        assertEquals(1, m.history.size)
    }

    // ── TR: 지난 운행 중 켜졌던 경고 (핵심 차별점) ─────────────────

    @Test fun `TR-10 1초 안에 켜졌다 꺼져 중간 상태를 못 봐도 누적 횟수로 빠짐없이 센다`() {
        val m = HandoverMachine(car)
        m.onSnapshot(snap(0, gear = Gear.DRIVE))
        // 켜짐 상태는 한 번도 전달되지 않고, 누적 횟수만 2로 올라간 채 전달됨 (상태 흐름이 값을 합친 경우)
        m.onSnapshot(snap(100, gear = Gear.DRIVE, lamps = emptySet()).copy(lampOnCounts = mapOf("TPMS" to 2)))
        m.onSnapshot(snap(200))
        assertEquals(2, exit(m, 1_000).handover.open.single().value)
    }


    @Test fun `TR-01 주행 중 켜졌다 꺼진 타이어 경고는 '지난 운행 중 1번'으로 넘어간다`() {
        val m = HandoverMachine(car)
        drive(m, 0, setOf("TPMS"), emptySet())
        val item = exit(m, 1_000).handover.open.single()
        assertEquals(Type.WARNING_DURING_TRIP, item.type)
        assertEquals("TPMS", item.code)
        assertEquals(1, item.value)
    }

    @Test fun `TR-02 켜졌다 꺼지기를 반복하면 횟수를 센다`() {
        val m = HandoverMachine(car)
        drive(m, 0, setOf("TPMS"), emptySet(), setOf("TPMS"), emptySet(), setOf("TPMS"), emptySet())
        assertEquals(3, exit(m, 2_000).handover.open.single().value)
    }

    @Test fun `TR-03 내릴 때도 켜져 있으면 '지금 켜진 경고'로, 운행 중 횟수와 함께`() {
        val m = HandoverMachine(car)
        drive(m, 0, setOf("LOW_VOLT_BATTERY"), emptySet())
        val item = exit(m, 1_000, lamps = setOf("LOW_VOLT_BATTERY")).handover.open.single()
        assertEquals(Type.WARNING_ON, item.type)
        assertEquals(2, item.value) // 주행 중 1번 + 주차 상태에서 다시 켜짐 1번
        assertEquals(Tier.SAFETY, item.tier)
    }

    @Test fun `TR-04 운전 습관 신호(과속·주의 경고·안전벨트)는 기록하지 않는다 (감시 방지)`() {
        val m = HandoverMachine(car)
        drive(m, 0, setOf("OVER_SPEED", "DRIVER_ATTENTION", "SEAT_BELT", "ICY_ROAD"), emptySet())
        assertTrue(exit(m, 1_000, lamps = setOf("SEAT_BELT", "OVER_SPEED")).handover.allClear)
    }

    @Test fun `TR-05 분류표에 없는 표시등(방향지시등·전조등 켜짐 표시)은 무시`() {
        val m = HandoverMachine(car)
        drive(m, 0, setOf("TURN_SIGNAL_LEFT", "HEADLAMP_LOW", "AUTO_HOLD"), emptySet())
        assertTrue(exit(m, 1_000, lamps = setOf("HEADLAMP_LOW")).handover.allClear)
    }

    @Test fun `TR-06 다음 운전자가 탄 뒤 켜진 경고는 이번 인계에 넣지 않고, 다음 운행 기록은 새로 시작`() {
        val m = HandoverMachine(car)
        drive(m, 0, setOf("TPMS"), emptySet())
        exit(m, 1_000); board(m, 9_000)
        val b = m.onSnapshot(snap(9_500, lamps = setOf("ABS"))) as State.Boarded
        assertTrue(b.handover.open.none { it.code == "ABS" })
        // 출발 후 새 운행: TPMS 기록은 이미 넘어갔으므로 다시 나오지 않는다
        m.onSnapshot(snap(10_000, gear = Gear.DRIVE))
        drive(m, 11_000)
        assertTrue(exit(m, 20_000).handover.open.none { it.code == "TPMS" })
    }

    @Test fun `TR-07 경고등 값을 아직 모르면(null) 아무것도 만들지 않는다`() {
        val m = HandoverMachine(car)
        m.onSnapshot(snap(0, gear = Gear.DRIVE, lamps = null)); m.onSnapshot(snap(100, lamps = null))
        m.onSnapshot(snap(200, driverSeated = false, lamps = null))
        assertTrue((m.onTick(2_300) as State.Away).handover.allClear)
    }

    @Test fun `TR-08 경고등을 지원하지 않는 차는 경고 항목이 없다`() {
        val m = HandoverMachine(car.copy(hasTelltales = false))
        drive(m, 0, setOf("TPMS"), emptySet())
        assertTrue(exit(m, 1_000, lamps = setOf("ABS")).handover.allClear)
    }

    @Test fun `TR-09 주차 중(하차 전) 켜진 경고도 운행 기록에 포함`() {
        val m = HandoverMachine(car)
        drive(m, 0)
        m.onSnapshot(snap(800, lamps = setOf("EV_SYSTEM"))); m.onSnapshot(snap(900))
        assertEquals("EV_SYSTEM", exit(m, 1_000).handover.open.single().code)
    }

    // ── ST: 바꿔 둔 설정 ─────────────────────────────────────────

    @Test fun `ST-01 ESC 꺼짐은 안전 등급 설정 항목으로 넘어간다`() {
        val m = HandoverMachine(car)
        drive(m, 0, setOf("ESC_OFF"))
        val item = exit(m, 1_000, lamps = setOf("ESC_OFF")).handover.open.single()
        assertEquals(Type.SETTING_CHANGED, item.type)
        assertEquals(Tier.SAFETY, item.tier)
    }

    @Test fun `ST-02 i-Pedal은 할 일, 스포츠 모드는 참고 등급`() {
        val m = HandoverMachine(car)
        drive(m, 0)
        val open = exit(m, 1_000, lamps = setOf("I_PEDAL", "DRIVE_MODE_SPORT")).handover.open
        assertEquals(Tier.TODO, open.first { it.code == "I_PEDAL" }.tier)
        assertEquals(Tier.INFO, open.first { it.code == "DRIVE_MODE_SPORT" }.tier)
    }

    @Test fun `ST-03 설정을 되돌리면 탑승 후 '해제 확인'으로 해결된다`() {
        val m = HandoverMachine(car)
        drive(m, 0)
        exit(m, 1_000, lamps = setOf("I_PEDAL")); board(m, 9_000, lamps = setOf("I_PEDAL"))
        val b = board(m, 9_500, lamps = emptySet())
        assertTrue(b.handover.open.isEmpty())
        assertEquals("I_PEDAL", b.handover.resolved.single().item.code)
    }

    @Test fun `ST-04 설정은 운행 횟수를 세지 않는다 (켜고 끄는 것은 결함이 아님)`() {
        val m = HandoverMachine(car)
        drive(m, 0, setOf("ESC_OFF"), emptySet(), setOf("ESC_OFF"), emptySet())
        assertTrue(exit(m, 1_000).handover.allClear)
    }

    // ── MT: 소모품·배터리 ─────────────────────────────────────────

    @Test fun `MT-01 브레이크액·엔진오일은 안전, 워셔액은 참고`() {
        val m = HandoverMachine(car)
        drive(m, 0)
        val open = exit(m, 1_000, brake = true, oil = true, washer = true).handover.open
        assertEquals(listOf(Type.BRAKE_FLUID_LOW, Type.ENGINE_OIL_LOW, Type.WASHER_FLUID_LOW), open.map { it.type })
        assertEquals(listOf(Tier.SAFETY, Tier.SAFETY, Tier.INFO), open.map { it.tier })
    }

    @Test fun `MT-02 배터리 20% 이하만 부족, 21%는 아님`() {
        assertTrue(Detector().detect(car, snap(0, soc = 21)).none { it.type == Type.LOW_BATTERY })
        assertEquals(20, Detector().detect(car, snap(0, soc = 20)).single { it.type == Type.LOW_BATTERY }.value)
    }

    @Test fun `MT-03 차를 떠나 있는 동안 생긴 고장(12V 배터리 경고)·배터리 하락은 추가된다`() {
        val m = HandoverMachine(car)
        drive(m, 0)
        exit(m, 1_000, soc = 25)
        val a = m.onSnapshot(snap(50_000, driverSeated = false, lamps = setOf("LOW_VOLT_BATTERY"), soc = 18)) as State.Away
        assertEquals(setOf(Type.WARNING_ON, Type.LOW_BATTERY), a.handover.open.map { it.type }.toSet())
    }

    @Test fun `MT-04 차를 떠나 있는 동안 설정 변경은 추가하지 않는다`() {
        val m = HandoverMachine(car)
        drive(m, 0)
        exit(m, 1_000)
        val a = m.onSnapshot(snap(5_000, driverSeated = false, lamps = setOf("I_PEDAL"))) as State.Away
        assertTrue(a.handover.allClear)
    }

    @Test fun `MT-05 내린 뒤 앞사람이 해결한 것은 조용히 빠지고 '해결됨'으로 치지 않는다`() {
        val m = HandoverMachine(car)
        drive(m, 0)
        exit(m, 1_000, lamps = setOf("ESC_OFF"))
        val a = m.onSnapshot(snap(5_000, driverSeated = false, lamps = emptySet())) as State.Away
        assertTrue(a.handover.open.isEmpty())
        assertTrue(a.handover.resolved.isEmpty())
    }

    // ── UK: 모름 ≠ 해결 ──────────────────────────────────────────

    @Test fun `UK-01 재시작 직후 경고등 값을 모르면 기록을 유지하고, 값이 오면 판정`() {
        val m = HandoverMachine(car)
        m.restore(State.Away(HandoverMachine.Handover(1, 60, listOf(OpenItem(Type.WARNING_ON, code = "TPMS")))), emptyList())
        val b = m.onSnapshot(VehicleSnapshot(gear = Gear.PARK, seatOccupied = mapOf(driver to true), timestampMs = 10)) as State.Boarded
        assertEquals(1, b.handover.open.size)
        val later = board(m, 20, lamps = emptySet())
        assertTrue(later.handover.open.isEmpty())
        assertEquals("TPMS", later.handover.resolved.single().item.code)
    }

    @Test fun `UK-02 '지난 운행 중' 기록은 신호로 사라지지 않는다 (확인 처리로만)`() {
        val m = HandoverMachine(car)
        drive(m, 0, setOf("ABS"), emptySet())
        exit(m, 1_000)
        val b = board(m, 9_000)
        assertEquals(Type.WARNING_DURING_TRIP, b.handover.open.single().type)
        assertTrue(m.acknowledge(b.handover.open.single().key))
    }

    // ── SM-1x: 사람·좌석 경계 ────────────────────────────────────

    @Test fun `SM-10 동승자가 남아 있어도 운전석이 비면 인계한다 (휴게소 운전 교대)`() {
        val m = HandoverMachine(car)
        m.onSnapshot(snap(0, gear = Gear.DRIVE, passengerSeated = true))
        m.onSnapshot(snap(100, passengerSeated = true))
        m.onSnapshot(snap(200, driverSeated = false, passengerSeated = true))
        assertTrue(m.onTick(2_300) is State.Away)
    }

    @Test fun `SM-11 운전석 센서 흔들림(2초 미만 비움)은 하차가 아니다`() {
        val m = HandoverMachine(car)
        m.onSnapshot(snap(0, gear = Gear.DRIVE)); m.onSnapshot(snap(100))
        m.onSnapshot(snap(200, driverSeated = false)); m.onSnapshot(snap(900, driverSeated = true))
        assertEquals(State.Parked, m.onTick(5_000))
    }

    @Test fun `SM-13 좌석 값을 모르면 하차로 판단하지 않는다`() {
        val m = HandoverMachine(car)
        m.onSnapshot(snap(0, gear = Gear.DRIVE)); m.onSnapshot(snap(100, driverSeated = null))
        assertEquals(State.Parked, m.onTick(10_000))
    }

    @Test fun `SM-14 탔다가 출발 안 하고 다시 내리면 같은 인계 유지 (수령 기록 포함)`() {
        val m = HandoverMachine(car)
        drive(m, 0, setOf("TPMS"), emptySet())
        exit(m, 1_000); board(m, 9_000); m.receive(9_100)
        m.onSnapshot(snap(10_000, driverSeated = false))
        val a = m.onTick(12_500) as State.Away
        assertEquals(9_100L, a.handover.receivedAtMs)
        assertEquals("TPMS", a.handover.open.single().code)
    }

    @Test fun `SM-15 착석 센서 없는 차는 P단+잠금을 하차로, 잠금 해제를 탑승으로 본다`() {
        val m = HandoverMachine(car.copy(seats = emptyList()))
        m.onSnapshot(VehicleSnapshot(gear = Gear.DRIVE, timestampMs = 0))
        m.onSnapshot(VehicleSnapshot(gear = Gear.PARK, doorsLocked = false, timestampMs = 100))
        m.onSnapshot(VehicleSnapshot(gear = Gear.PARK, doorsLocked = true, timestampMs = 200))
        assertTrue(m.onTick(2_300) is State.Away)
        assertTrue(m.onSnapshot(VehicleSnapshot(gear = Gear.PARK, doorsLocked = false, timestampMs = 3_000)) is State.Boarded)
    }

    @Test fun `SM-16 우핸들 차량(일본·영국)은 1열 오른쪽을 운전석으로 본다`() {
        val m = HandoverMachine(car.copy(driverSeat = passenger))
        m.onSnapshot(snap(0, gear = Gear.DRIVE, passengerSeated = true))
        m.onSnapshot(snap(100, driverSeated = false, passengerSeated = true))
        assertEquals(State.Parked, m.onTick(5_000))
        m.onSnapshot(snap(6_000, driverSeated = false, passengerSeated = false))
        assertTrue(m.onTick(8_500) is State.Away)
    }

    // ── SM-3x: 기어·시간·순서 이상 ───────────────────────────────

    @Test fun `SM-30 기어를 모르면(UNKNOWN) 출발로 보지 않는다`() {
        val m = HandoverMachine(car)
        drive(m, 0); exit(m, 1_000); board(m, 9_000)
        assertTrue(m.onSnapshot(snap(10_000, gear = Gear.UNKNOWN)) is State.Boarded)
    }

    @Test fun `SM-31 후진(R)·중립(N)도 출발로 본다`() {
        for (g in listOf(Gear.REVERSE, Gear.NEUTRAL)) {
            val m = HandoverMachine(car)
            drive(m, 0); exit(m, 1_000); board(m, 9_000)
            assertEquals("gear=$g", State.Driving, m.onSnapshot(snap(10_000, gear = g)))
        }
    }

    @Test fun `SM-32 앱이 꺼진 사이 출발했다면 이력으로 넘긴다`() {
        val m = HandoverMachine(car)
        m.restore(State.Away(HandoverMachine.Handover(1, 70, emptyList())), emptyList())
        assertEquals(State.Driving, m.onSnapshot(snap(10, gear = Gear.DRIVE)))
        assertEquals(1, m.history.size)
    }

    @Test fun `SM-33 같은 스냅샷이 반복돼도 횟수가 늘지 않는다`() {
        val m = HandoverMachine(car)
        m.onSnapshot(snap(0, gear = Gear.DRIVE))
        repeat(50) { i -> m.onSnapshot(snap(100L + i, gear = Gear.DRIVE, lamps = setOf("TPMS"))) }
        m.onSnapshot(snap(500, gear = Gear.DRIVE)); m.onSnapshot(snap(600))
        assertEquals(1, exit(m, 1_000).handover.open.single().value)
    }

    @Test fun `SM-34 시계가 거꾸로 가도(시간 역행) 예외 없이 동작한다`() {
        val m = HandoverMachine(car)
        m.onSnapshot(snap(10_000, gear = Gear.DRIVE)); m.onSnapshot(snap(9_000))
        m.onSnapshot(snap(8_000, driverSeated = false))
        assertEquals(State.Parked, m.onTick(7_000))
    }

    @Test fun `SM-35 SoC 값이 없어도 동작한다`() {
        val m = HandoverMachine(car)
        drive(m, 0); exit(m, 1_000)
        assertNull((m.onSnapshot(snap(9_000, soc = null)) as State.Boarded).handover.socAtBoard)
    }

    // ── SM-4x: 수령증·확인 처리·복원·이력 ────────────────────────

    @Test fun `SM-40 수령증은 탑승 상태에서 한 번만 기록된다`() {
        val m = HandoverMachine(car)
        drive(m, 0); exit(m, 1_000); board(m, 9_000)
        m.receive(9_500)
        assertEquals(9_500L, (m.receive(99_999) as State.Boarded).handover.receivedAtMs)
    }

    @Test fun `SM-41 탑승 전(Away)에는 수령증을 누를 수 없다`() {
        val m = HandoverMachine(car)
        drive(m, 0); exit(m, 1_000)
        assertNull((m.receive(4_000) as State.Away).handover.receivedAtMs)
    }

    @Test fun `SM-43 이력은 최대 50건만 보관한다`() {
        val m = HandoverMachine(car)
        m.restore(State.Driving, List(60) { HandoverMachine.Handover(it.toLong(), null, emptyList()) })
        assertEquals(50, m.history.size)
        assertEquals(10L, m.history.first().parkedAtMs)
    }

    @Test fun `SM-44 할 일 설정(i-Pedal)은 확인 처리로 숨기고 되돌릴 수 있다`() {
        val m = HandoverMachine(car)
        drive(m, 0); exit(m, 1_000, lamps = setOf("I_PEDAL")); board(m, 9_000, lamps = setOf("I_PEDAL"))
        val key = OpenItem(Type.SETTING_CHANGED, code = "I_PEDAL").key
        assertTrue(m.acknowledge(key))
        assertTrue((m.state as State.Boarded).handover.visible.isEmpty())
        assertTrue(m.unacknowledge(key))
        assertEquals(1, (m.state as State.Boarded).handover.visible.size)
    }

    @Test fun `SM-45 지금 켜진 안전 경고·안전 설정(ESC 꺼짐)은 확인 처리로 숨길 수 없다`() {
        val m = HandoverMachine(car)
        drive(m, 0); exit(m, 1_000, lamps = setOf("ESC_OFF", "TPMS")); board(m, 9_000, lamps = setOf("ESC_OFF", "TPMS"))
        assertFalse(m.acknowledge(OpenItem(Type.SETTING_CHANGED, code = "ESC_OFF").key))
        assertFalse(m.acknowledge(OpenItem(Type.WARNING_ON, code = "TPMS").key))
        assertEquals(2, (m.state as State.Boarded).handover.visible.size)
    }

    @Test fun `SM-46 탑승 전·없는 항목은 확인 처리 불가`() {
        val m = HandoverMachine(car)
        drive(m, 0, setOf("ABS"), emptySet()); exit(m, 1_000)
        assertFalse(m.acknowledge(OpenItem(Type.WARNING_DURING_TRIP, code = "ABS").key))
        board(m, 9_000)
        assertFalse(m.acknowledge("NOPE:-"))
    }

    // ── DT: 탐지·정렬 ────────────────────────────────────────────

    @Test fun `DT-01 프로필에 없는 신호는 보지 않는다`() {
        val bare = VehicleProfile(seats = listOf(driver))
        assertTrue(Detector().detect(bare, snap(0, lamps = setOf("TPMS"), soc = 5, brake = true, oil = true)).isEmpty())
    }

    @Test fun `DT-02 안전 항목이 항상 먼저`() {
        val items = Detector().detect(car, snap(0, lamps = setOf("DRIVE_MODE_SPORT", "I_PEDAL", "ABS"), soc = 5, washer = true, brake = true))
        val tiers = items.map { it.tier.ordinal }
        assertEquals(tiers.sorted(), tiers)
    }

    @Test fun `DT-03 값을 모르면(null) 소모품 항목을 만들지 않는다`() {
        assertTrue(Detector().detect(car, snap(0, brake = null, oil = null, washer = null, soc = null, lamps = null)).isEmpty())
    }

    @Test fun `DT-04 12인승(4열)도 운전석 기준으로 같은 방식`() {
        val van = VehicleProfile(seats = (1..4).flatMap { r -> Side.entries.map { Spot(r, it) } }, hasTelltales = true)
        assertEquals(12, van.seatCount)
        assertEquals(4, van.rows)
        val m = HandoverMachine(van)
        m.onSnapshot(VehicleSnapshot(gear = Gear.DRIVE, telltalesOn = setOf("TPMS"), timestampMs = 0))
        m.onSnapshot(VehicleSnapshot(gear = Gear.PARK, telltalesOn = emptySet(), timestampMs = 100))
        m.onSnapshot(VehicleSnapshot(gear = Gear.PARK, telltalesOn = emptySet(), seatOccupied = mapOf(driver to false), timestampMs = 200))
        assertEquals("TPMS", (m.onTick(2_300) as State.Away).handover.open.single().code)
    }

    // ── TT: 경고등 분류표 ────────────────────────────────────────

    @Test fun `TT-01 기록 금지 신호는 분류표에 없다`() {
        for (n in Telltales.NEVER_RECORDED) assertNull(n, Telltales.spec(n))
    }

    @Test fun `TT-02 DVIR 점검 범주(제동·주차 브레이크·조향·등화·타이어·비상 장비)를 모두 덮는다`() {
        val covered = Telltales.SPECS.values.map { it.dvir }.toSet()
        for (d in listOf(Telltales.Dvir.BRAKES, Telltales.Dvir.PARKING_BRAKE, Telltales.Dvir.STEERING,
            Telltales.Dvir.LIGHTING, Telltales.Dvir.TIRES, Telltales.Dvir.EMERGENCY)) assertTrue("$d", d in covered)
    }

    @Test fun `TT-03 모든 분류표 코드에 4개 언어 이름이 있다`() {
        assertEquals(Telltales.SPECS.keys, com.baton.app.i18n.TelltaleNames.CODES)
    }

    // ── SP: 위치 코드 ─────────────────────────────────────────────

    @Test fun `SP-01 위치 코드는 왕복 변환된다`() {
        for (r in 1..4) for (side in Side.entries) assertEquals(Spot(r, side), Spot.fromCode(Spot(r, side).code))
    }

    @Test fun `SP-02 잘못된 위치 코드는 null`() {
        for (bad in listOf("", "R", "RXL", "R1X", "R10L", "r1l", "R1L;DROP")) assertNull(bad, Spot.fromCode(bad))
    }
}
