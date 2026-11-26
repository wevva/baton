package com.baton.app.handover

import com.baton.app.handover.HandoverMachine.State
import com.baton.app.vehicle.Spot
import com.baton.app.vehicle.Spot.Side
import com.baton.app.vehicle.VehicleProfile
import com.baton.app.vehicle.VehicleSnapshot
import com.baton.app.vehicle.VehicleSnapshot.Gear
import com.baton.app.voice.ReceiptPhrase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/**
 * 무작위 입력 대량 시험 (퍼징). 케이스 번호는 docs/TEST_CASES.md의 FZ·PF와 같다.
 * 차량 신호가 어떤 순서·조합으로 와도 앱이 죽지 않고, 지켜야 할 규칙(불변식)이 깨지지 않는지 본다.
 * 시드를 고정해 실패하면 같은 입력으로 다시 재현할 수 있다.
 */
class RobustnessTest {

    private val driver = Spot(1, Side.LEFT)
    private val full = VehicleProfile(
        seats = listOf(driver, Spot(1, Side.RIGHT), Spot(2, Side.LEFT), Spot(2, Side.RIGHT)),
        hasDoorLock = true, hasBatterySoc = true, hasBrakeFluid = true, hasEngineOil = true,
        hasTransmissionOil = true, hasWasherFluid = true, hasTelltales = true,
    )
    /** 착석 센서 없는 차 (잠금으로 하차 판단) */
    private val noSeats = full.copy(seats = emptyList())
    /** 우핸들 */
    private val rhd = full.copy(driverSeat = Spot(1, Side.RIGHT))
    /** 아무 신호도 없는 차 */
    private val bare = VehicleProfile()

    /** 분류표 안·밖·기록 금지 신호를 섞어 둔다 */
    private val lampPool = listOf(
        "TPMS", "ABS", "ESC_OFF", "I_PEDAL", "DRIVE_MODE_SPORT", "LOW_VOLT_BATTERY", "AIRBAG",
        "OVER_SPEED", "SEAT_BELT", "TURN_SIGNAL", "PASSENGER_AIRBAG_OFF", "ENGINE",
    )

    private fun Random.tri(): Boolean? = when (nextInt(5)) { 0 -> null; 1 -> true; else -> false }

    private fun Random.snapshot(t: Long, counts: MutableMap<String, Int>, provided: Boolean): VehicleSnapshot {
        val gear = when (nextInt(10)) { in 0..4 -> Gear.PARK; 5, 6 -> Gear.DRIVE; 7 -> Gear.REVERSE; 8 -> Gear.NEUTRAL; else -> Gear.UNKNOWN }
        val lamps = if (nextInt(8) == 0) null else lampPool.filter { nextInt(6) == 0 }.toSet()
        if (provided && lamps != null) lamps.forEach { if (nextInt(3) == 0) counts[it] = (counts[it] ?: 0) + 1 }
        return VehicleSnapshot(
            gear = gear,
            doorsLocked = tri(),
            seatOccupied = buildMap {
                for (s in full.seats) when (nextInt(6)) { 0 -> Unit; 1, 2 -> put(s, true); else -> put(s, false) }
            },
            batterySoc = if (nextInt(6) == 0) null else nextInt(0, 101),
            brakeFluidLow = tri(), engineOilLow = tri(), transmissionOilLow = tri(), washerFluidLow = tri(),
            telltalesOn = lamps,
            lampOnCounts = if (provided) counts.toMap() else emptyMap(),
            lampCountsProvided = provided,
            timestampMs = t,
        )
    }

    /** 인계 한 건이 지켜야 할 규칙 */
    private fun checkHandover(h: HandoverMachine.Handover, where: String) {
        val keys = h.open.map { it.key }
        assertEquals("$where: 남은 항목 키 중복", keys.size, keys.toSet().size)
        assertTrue("$where: 확인 표시가 남은 항목 밖에 있음 ${h.acknowledged - keys.toSet()}", keys.containsAll(h.acknowledged))
        for (k in h.acknowledged) {
            val item = h.open.first { it.key == k }
            assertTrue("$where: 지금 켜진 안전 항목이 확인으로 숨겨짐 $k", HandoverMachine.canAcknowledge(item))
        }
        val rk = h.resolved.map { it.item.key }
        assertEquals("$where: 해결 목록 중복", rk.size, rk.toSet().size)
        assertTrue("$where: 해결됐다면서 남아 있음", rk.none { it in keys })
        assertEquals("$where: 정렬이 깨짐", Detector.sort(h.open), h.open)
        for (i in h.open) {
            assertFalse("$where: 기록 금지 신호가 들어감 ${i.code}", i.code != null && i.code in Telltales.NEVER_RECORDED)
            if (i.code != null) assertTrue("$where: 분류표 밖 코드 ${i.code}", Telltales.spec(i.code!!) != null)
        }
    }

    private fun checkState(m: HandoverMachine, where: String) {
        when (val s = m.state) {
            is State.Away -> checkHandover(s.handover, where)
            is State.Boarded -> checkHandover(s.handover, where)
            else -> Unit
        }
        assertTrue("$where: 이력 50건 초과", m.history.size <= 50)
        m.history.forEach { checkHandover(it, "$where/이력") }
    }

    private fun run(profile: VehicleProfile, seed: Int, steps: Int, provided: Boolean, codecEvery: Int = 7) {
        val r = Random(seed)
        val m = HandoverMachine(profile)
        val a = Announcer()
        val intros = HashMap<Long, Int>()
        val counts = HashMap<String, Int>()
        var t = 1_000_000L
        for (i in 0 until steps) {
            val where = "seed=$seed step=$i"
            val before = m.state
            try {
                // 시간은 대부분 앞으로, 가끔 크게 건너뛰거나 거꾸로 간다 (시계 보정·재부팅)
                t += when (r.nextInt(20)) { 0 -> -r.nextLong(0, 5_000); 1 -> r.nextLong(10_000, 86_400_000); else -> r.nextLong(0, 1_500) }
                when (r.nextInt(12)) {
                    0, 1 -> m.onTick(t)
                    2 -> (m.state as? State.Boarded)?.handover?.open?.randomOrNull(r)?.let { m.acknowledge(it.key) }
                    3 -> (m.state as? State.Boarded)?.handover?.acknowledged?.randomOrNull(r)?.let { m.unacknowledge(it) }
                    4 -> m.receive(t)
                    5 -> m.acknowledge("ESC_OFF") // 엉뚱한 키
                    else -> m.onSnapshot(r.snapshot(t, counts, provided))
                }
            } catch (e: Exception) {
                fail("$where: 예외 ${e.javaClass.simpleName} ${e.message}")
            }
            checkState(m, where)
            for (e in a.decide(before, m.state)) {
                if (e is Announcer.Event.Intro) {
                    val h = (m.state as State.Boarded).handover
                    intros[h.parkedAtMs] = (intros[h.parkedAtMs] ?: 0) + 1
                    assertTrue("$where: 같은 인계 요약을 두 번 말함", intros.getValue(h.parkedAtMs) == 1)
                    assertTrue("$where: 확인 처리한 항목을 요약에 넣음", e.open.none { it.key in h.acknowledged })
                }
                if (e is Announcer.Event.DepartWithSafety) {
                    assertTrue("$where: 출발이 아닌데 출발 경고", before is State.Boarded && m.state == State.Driving)
                    assertTrue("$where: 안전 아닌 항목이 출발 경고에 섞임", e.items.all { it.tier == OpenItem.Tier.SAFETY })
                }
            }
            // 저장 → 복원하면 상태·이력이 그대로 (저장 형식이 상태 머신이 만드는 모든 값을 담는지)
            if (i % codecEvery == 0) {
                val decoded = HandoverCodec.decode(HandoverCodec.encode(m.state, m.history))
                    ?: return fail("$where: 저장본을 못 읽음")
                val expectState = if (m.state == State.Parked) State.Driving else m.state
                assertEquals("$where: 복원 상태 다름", expectState, decoded.first)
                assertEquals("$where: 복원 이력 다름", m.history.toList(), decoded.second)
            }
        }
    }

    @Test fun `FZ-01 무작위 신호 2,000가지 순서 x 300단계에서 예외·규칙 위반이 없다 (센서 있는 차)`() {
        for (seed in 0 until 1_000) run(full, seed, 300, provided = false)
        for (seed in 1_000 until 2_000) run(full, seed, 300, provided = true)
    }

    @Test fun `FZ-02 착석 센서 없는 차·우핸들·신호 없는 차도 같은 규칙을 지킨다`() {
        for (seed in 0 until 400) {
            run(noSeats, seed, 300, provided = seed % 2 == 0)
            run(rhd, seed, 300, provided = seed % 2 == 1)
            run(bare, seed, 100, provided = false)
        }
    }

    @Test fun `FZ-03 저장 파일을 한 글자씩 망가뜨려도 예외 없이 읽거나 버리고, 안전 경고를 숨기지 못한다`() {
        // 정상 인계 하나를 만든다: 지금 켜진 타이어 경고 + 할 일 i-Pedal + 지난 운행 ABS
        val h = HandoverMachine.Handover(
            parkedAtMs = 10, socAtPark = 50,
            open = Detector.sort(listOf(
                OpenItem(OpenItem.Type.WARNING_ON, code = "TPMS", value = 2),
                OpenItem(OpenItem.Type.SETTING_CHANGED, code = "I_PEDAL"),
                OpenItem(OpenItem.Type.WARNING_DURING_TRIP, code = "ABS", value = 1),
            )),
            acknowledged = setOf("SETTING_CHANGED:I_PEDAL"),
        )
        val json = HandoverCodec.encode(State.Boarded(h), listOf(h))
        val r = Random(42)
        val alphabet = "{}[]\":,0123456789-.eEtrufalsnTPMSWARNING_ON:ackopen\\u \n"
        repeat(20_000) { n ->
            val chars = json.toCharArray()
            repeat(1 + r.nextInt(4)) {
                val i = r.nextInt(chars.size)
                chars[i] = alphabet[r.nextInt(alphabet.length)]
            }
            val broken = String(chars)
            val out = try { HandoverCodec.decode(broken) } catch (e: Exception) { fail("변형 $n: 예외 ${e.javaClass.simpleName}"); null }
            if (out != null) {
                (out.first as? State.Boarded)?.let { checkHandover(it.handover, "변형 $n") }
                (out.first as? State.Away)?.let { checkHandover(it.handover, "변형 $n") }
                out.second.forEach { checkHandover(it, "변형 $n/이력") }
            }
        }
        // 일부러 안전 경고를 '확인됨'에 넣은 변조
        val forged = json.replace("\"ack\":[\"SETTING_CHANGED:I_PEDAL\"]", "\"ack\":[\"SETTING_CHANGED:I_PEDAL\",\"WARNING_ON:TPMS\"]")
        val back = HandoverCodec.decode(forged)!!.first as State.Boarded
        assertFalse("WARNING_ON:TPMS" in back.handover.acknowledged)
    }

    @Test fun `FZ-04 아무 문자열이나 넣어도 저장본 해석은 예외가 없다`() {
        val r = Random(7)
        repeat(20_000) {
            val len = r.nextInt(0, 200)
            val s = String(CharArray(len) { (r.nextInt(0, 0x3000)).toChar() })
            try { HandoverCodec.decode(s) } catch (e: Exception) { fail("예외 ${e.javaClass.simpleName}") }
        }
        // 깊게 중첩된 JSON (스택 고갈 공격)
        val deep = "[".repeat(5_000) + "]".repeat(5_000)
        try { HandoverCodec.decode("{\"v\":1,\"state\":\"away\",\"handover\":$deep}") } catch (e: Throwable) { fail("깊은 중첩에서 ${e.javaClass.simpleName}") }
    }

    @Test fun `FZ-05 수령 음성 판별은 어떤 문자열에도 예외가 없고 부정어가 있으면 수령하지 않는다`() {
        val r = Random(3)
        val words = listOf("받았어요", "못 받았어", "아니", "확인했어", "잠깐만", "got it", "not received", "ok", "wait", "okay", "네", "🙂", "", " ")
        repeat(20_000) {
            val text = (0 until r.nextInt(1, 5)).joinToString(" ") { words.random(r) }
            for (lang in listOf("ko", "en", "ja", "")) {
                val ok = try { ReceiptPhrase.matches(text, lang) } catch (e: Exception) { fail("예외: $text"); false }
                if (ok && lang == "ko") assertTrue("부정어가 있는데 수령: $text", listOf("못 받", "아니", "잠깐").none { it in text })
                if (ok && lang == "en") assertTrue("부정어가 있는데 수령: $text", listOf("not ", "wait").none { it in "$text " })
            }
        }
    }

    @Test fun `FZ-06 정렬은 몇 번 해도 같고 중복이 없다`() {
        val r = Random(11)
        repeat(5_000) {
            val items = (0 until r.nextInt(0, 30)).map {
                when (r.nextInt(4)) {
                    0 -> OpenItem(OpenItem.Type.WARNING_ON, code = Telltales.SPECS.keys.filter { k -> Telltales.SPECS.getValue(k).kind == Telltales.Kind.FAULT }.random(r))
                    1 -> OpenItem(OpenItem.Type.SETTING_CHANGED, code = Telltales.SPECS.keys.filter { k -> Telltales.SPECS.getValue(k).kind == Telltales.Kind.SETTING }.random(r))
                    2 -> OpenItem(OpenItem.Type.LOW_BATTERY, value = r.nextInt(0, 21))
                    else -> OpenItem(OpenItem.Type.entries.filter { t -> t.name.endsWith("_LOW") }.random(r))
                }
            }
            val once = Detector.sort(items)
            assertEquals(once, Detector.sort(once))
            // 들어온 순서가 달라도 같은 순서로 나온다 (같은 키의 값만 다를 수 있음)
            assertEquals(once.map { it.key }, Detector.sort(items.shuffled(r)).map { it.key })
            assertEquals(once.size, once.map { it.key }.toSet().size)
            // 안전 → 할 일 → 참고 순서
            assertEquals(once.map { it.tier.ordinal }.sorted(), once.map { it.tier.ordinal })
        }
    }

    @Test fun `SM-36 주차 중 기어 신호가 잠깐 끊겨도(UNKNOWN) 주행으로 보지 않는다`() {
        val m = HandoverMachine(full)
        fun s(t: Long, gear: Gear, seated: Boolean = true) = VehicleSnapshot(
            gear = gear, seatOccupied = mapOf(driver to seated), telltalesOn = emptySet(), timestampMs = t,
        )
        m.onSnapshot(s(0, Gear.DRIVE))
        m.onSnapshot(s(100, Gear.PARK, seated = false))
        assertEquals(State.Parked, m.onSnapshot(s(500, Gear.UNKNOWN, seated = false)))
        // 끊긴 사이에도 하차 2초는 이어서 잰다
        assertTrue(m.onSnapshot(s(2_200, Gear.PARK, seated = false)) is State.Away)
    }

    // ── PF: 성능 (차량 기기는 노트북보다 느리므로 넉넉한 기준의 1/10 수준을 목표로 잰다) ──

    @Test fun `PF-01 신호 20만 건을 3초 안에 처리한다 (초당 수십 건 들어와도 여유)`() {
        val r = Random(5)
        val m = HandoverMachine(full)
        val counts = HashMap<String, Int>()
        val snaps = (0 until 200_000).map { r.snapshot(it * 50L, counts, provided = it % 2 == 0) }
        val start = System.nanoTime()
        for (s in snaps) m.onSnapshot(s)
        val ms = (System.nanoTime() - start) / 1_000_000
        println("PF-01 200,000건 ${ms}ms")
        assertTrue("너무 느림: ${ms}ms", ms < 3_000)
    }

    @Test fun `PF-02 가장 큰 저장본(인계 50건 x 항목 다수) 저장·복원 1회가 20ms 안`() {
        val items = Detector.sort(Telltales.SPECS.entries.map { (code, spec) ->
            if (spec.kind == Telltales.Kind.SETTING) OpenItem(OpenItem.Type.SETTING_CHANGED, code = code)
            else OpenItem(OpenItem.Type.WARNING_DURING_TRIP, code = code, value = 3)
        })
        val h = HandoverMachine.Handover(1, 50, items, socAtBoard = 60, receivedAtMs = 5)
        val history = List(50) { h.copy(parkedAtMs = it.toLong()) }
        repeat(20) { HandoverCodec.decode(HandoverCodec.encode(State.Boarded(h), history)) } // 데우기
        val n = 100
        val start = System.nanoTime()
        var size = 0
        repeat(n) {
            val json = HandoverCodec.encode(State.Boarded(h), history)
            size = json.length
            HandoverCodec.decode(json)!!
        }
        val each = (System.nanoTime() - start) / 1_000_000.0 / n
        println("PF-02 저장본 ${size}자, 1회 ${"%.2f".format(each)}ms")
        assertTrue("너무 느림: ${each}ms", each < 20)
        assertTrue("저장본이 상한(1MB)에 가까움: $size", size < 500_000)
    }
}
