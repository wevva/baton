package com.baton.app.handover

import com.baton.app.handover.HandoverMachine.Handover
import com.baton.app.handover.HandoverMachine.Resolved
import com.baton.app.handover.HandoverMachine.State
import com.baton.app.handover.OpenItem.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 저장 형식 테스트 (CD-xx). 저장 파일은 신뢰하지 않는다는 전제로 손상·변조·악의적 입력을 검증한다. */
class HandoverCodecTest {

    private val h = Handover(
        parkedAtMs = 1_700_000_000_000, socAtPark = 35, socAtBoard = 80, receivedAtMs = 1_700_000_100_000,
        open = listOf(
            OpenItem(Type.WARNING_ON, value = 2, code = "TPMS"),
            OpenItem(Type.SETTING_CHANGED, code = "I_PEDAL"),
            OpenItem(Type.WARNING_DURING_TRIP, value = 3, code = "ABS"),
            OpenItem(Type.LOW_BATTERY, value = 18),
        ),
        resolved = listOf(Resolved(OpenItem(Type.WASHER_FLUID_LOW), 1_700_000_050_000)),
    )

    private fun handoverJson(open: String, extra: String = "") =
        """{"v":1,"state":"boarded","handover":{"parkedAt":1,"open":[$open],"resolved":[]$extra}}"""

    @Test fun `CD-01 왕복 변환 시 모든 값이 보존된다`() {
        for (state in listOf(State.Away(h), State.Boarded(h), State.Driving)) {
            val (s, hist) = HandoverCodec.decode(HandoverCodec.encode(state, listOf(h, h)))!!
            assertEquals(state, s)
            assertEquals(listOf(h, h), hist)
        }
    }

    @Test fun `CD-02 깨진 JSON·빈 파일·바이너리 쓰레기는 null (앱이 죽지 않음)`() {
        for (bad in listOf("", "{", "null", "[]", "{\"state\":", "\u0000\u0001", "not json at all")) {
            assertNull(bad, HandoverCodec.decode(bad))
        }
    }

    @Test fun `CD-03 더 새 버전이 쓴 파일은 해석하지 않는다`() {
        assertNull(HandoverCodec.decode("""{"v":99,"state":"away","handover":{"parkedAt":1,"open":[]}}"""))
    }

    @Test fun `CD-04 모르는 항목 종류는 버리고 나머지는 살린다`() {
        val b = HandoverCodec.decode(handoverJson("""{"type":"SELF_DESTRUCT"},{"type":"BRAKE_FLUID_LOW"}"""))!!.first as State.Boarded
        assertEquals(listOf(Type.BRAKE_FLUID_LOW), b.handover.open.map { it.type })
    }

    @Test fun `CD-05 경고등 코드는 분류표에 있는 것만 (모르는 코드·기록 금지 신호·종류 불일치는 버림)`() {
        val json = handoverJson(
            """{"type":"WARNING_ON","code":"TPMS"},{"type":"WARNING_ON","code":"HACKED"},
               {"type":"WARNING_ON","code":"OVER_SPEED"},{"type":"SETTING_CHANGED","code":"TPMS"},
               {"type":"WARNING_ON","code":"ESC_OFF"},{"type":"WARNING_ON"},{"type":"LOW_BATTERY","code":"TPMS"}"""
        )
        val b = HandoverCodec.decode(json)!!.first as State.Boarded
        assertEquals(listOf("TPMS"), b.handover.open.map { it.code })
    }

    @Test fun `CD-06 값 범위를 강제한다 (0~100)`() {
        val json = """{"v":1,"state":"away","handover":{"parkedAt":1,"socAtPark":999,"socAtBoard":-5,
            "open":[{"type":"LOW_BATTERY","value":100000}],"resolved":[]}}"""
        val a = HandoverCodec.decode(json)!!.first as State.Away
        assertNull(a.handover.socAtPark)
        assertNull(a.handover.socAtBoard)
        assertEquals(100, a.handover.open.single().value)
    }

    @Test fun `CD-07 항목 개수 상한 (변조로 수만 개를 넣어도 64개까지만)`() {
        val many = (1..10_000).joinToString(",") { """{"type":"WASHER_FLUID_LOW"}""" }
        val b = HandoverCodec.decode(handoverJson(many))!!.first as State.Boarded
        assertTrue(b.handover.open.size <= 64)
    }

    @Test fun `CD-08 이력 상한 50건`() {
        val (_, hist) = HandoverCodec.decode(HandoverCodec.encode(State.Driving, List(80) { h }))!!
        assertEquals(50, hist.size)
    }

    @Test fun `CD-09 중복 항목은 하나로 (화면 목록 키 중복 방지)`() {
        val b = HandoverCodec.decode(handoverJson("""{"type":"WARNING_ON","code":"TPMS"},{"type":"WARNING_ON","code":"TPMS"}"""))!!.first as State.Boarded
        assertEquals(1, b.handover.open.size)
    }

    @Test fun `CD-10 필수 값이 빠진 인계는 '인계 없음'으로 처리`() {
        assertEquals(State.Driving, HandoverCodec.decode("""{"v":1,"state":"away","handover":{"open":[]}}""")!!.first)
    }

    @Test fun `CD-11 타입이 엉뚱한 값도 예외 없이 처리`() {
        val r = HandoverCodec.decode("""{"v":"x","state":123,"handover":"oops","history":[1,"a",null,{"parkedAt":"zz"}]}""")
        assertTrue(r == null || r.first == State.Driving)
    }

    @Test fun `CD-12 저장 형식에 개인정보 필드가 없다 (위치·이름·차대번호)`() {
        val json = HandoverCodec.encode(State.Boarded(h), listOf(h)).lowercase()
        for (k in listOf("lat", "lon", "gps", "name", "vin", "phone", "email", "face", "voice", "speed")) {
            assertTrue("필드 '$k' 가 저장되면 안 됨", !Regex("\"$k").containsMatchIn(json))
        }
    }

    @Test fun `CD-13 확인 기록이 저장·복원된다`() {
        val acked = h.copy(acknowledged = setOf(OpenItem(Type.SETTING_CHANGED, code = "I_PEDAL").key))
        val b = HandoverCodec.decode(HandoverCodec.encode(State.Boarded(acked), emptyList()))!!.first as State.Boarded
        assertEquals(acked.acknowledged, b.handover.acknowledged)
    }

    @Test fun `CD-14 변조로 지금 켜진 안전 경고를 '확인됨'에 넣어 숨길 수 없다`() {
        val json = handoverJson(
            """{"type":"WARNING_ON","code":"TPMS"},{"type":"SETTING_CHANGED","code":"ESC_OFF"},{"type":"SETTING_CHANGED","code":"I_PEDAL"}""",
            ""","ack":["WARNING_ON:TPMS","SETTING_CHANGED:ESC_OFF","SETTING_CHANGED:I_PEDAL","GHOST:-"]"""
        )
        val b = HandoverCodec.decode(json)!!.first as State.Boarded
        assertEquals(setOf("SETTING_CHANGED:I_PEDAL"), b.handover.acknowledged)
    }
}
