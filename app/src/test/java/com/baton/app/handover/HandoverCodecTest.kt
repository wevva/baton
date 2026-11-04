package com.baton.app.handover

import com.baton.app.handover.HandoverMachine.Handover
import com.baton.app.handover.HandoverMachine.Resolved
import com.baton.app.handover.HandoverMachine.State
import com.baton.app.handover.OpenItem.Type
import com.baton.app.vehicle.Spot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 저장 형식 테스트 (ST-xx). 저장 파일은 신뢰하지 않는다는 전제로 손상·변조·악의적 입력을 검증한다. */
class HandoverCodecTest {

    private val h = Handover(
        parkedAtMs = 1_700_000_000_000, socAtPark = 35, socAtBoard = 80, receivedAtMs = 1_700_000_100_000,
        open = listOf(OpenItem(Type.DOORS_UNLOCKED), OpenItem(Type.WINDOW_OPEN, Spot(2, Spot.Side.LEFT), 40)),
        resolved = listOf(Resolved(OpenItem(Type.CABIN_LIGHT_ON), 1_700_000_050_000)),
    )

    @Test fun `ST-01 왕복 변환 시 모든 값이 보존된다`() {
        for (state in listOf(State.Away(h), State.Boarded(h), State.Driving)) {
            val (s, hist) = HandoverCodec.decode(HandoverCodec.encode(state, listOf(h, h)))!!
            assertEquals(state, s)
            assertEquals(listOf(h, h), hist)
        }
    }

    @Test fun `ST-02 깨진 JSON은 null (앱이 죽지 않음)`() {
        for (bad in listOf("", "{", "null", "[]", "{\"state\":", "\u0000\u0001", "not json at all")) {
            assertNull(bad, HandoverCodec.decode(bad))
        }
    }

    @Test fun `ST-03 더 새 버전이 쓴 파일은 해석하지 않는다`() {
        assertNull(HandoverCodec.decode("""{"v":99,"state":"away","handover":{"parkedAt":1,"open":[]}}"""))
    }

    @Test fun `ST-04 모르는 항목 종류·잘못된 위치는 버리고 나머지는 살린다`() {
        val json = """{"v":1,"state":"away","handover":{"parkedAt":1,"open":[
            {"type":"SELF_DESTRUCT"},{"type":"WINDOW_OPEN","spot":"R9Z","value":50},
            {"type":"WINDOW_OPEN","spot":"R2L","value":40},{"type":"CABIN_LIGHT_ON"}],"resolved":[]}}"""
        val a = HandoverCodec.decode(json)!!.first as State.Away
        assertEquals(listOf(Type.WINDOW_OPEN, Type.CABIN_LIGHT_ON), a.handover.open.map { it.type })
    }

    @Test fun `ST-05 값 범위를 강제한다 (퍼센트 0~100)`() {
        val json = """{"v":1,"state":"away","handover":{"parkedAt":1,"socAtPark":999,"socAtBoard":-5,
            "open":[{"type":"WINDOW_OPEN","spot":"R2L","value":100000}],"resolved":[]}}"""
        val a = HandoverCodec.decode(json)!!.first as State.Away
        assertNull(a.handover.socAtPark)
        assertNull(a.handover.socAtBoard)
        assertEquals(100, a.handover.open.single().value)
    }

    @Test fun `ST-06 항목 개수 상한 (변조로 수만 개를 넣어도 64개까지만)`() {
        val many = (1..10_000).joinToString(",") { """{"type":"CABIN_LIGHT_ON"}""" }
        val a = HandoverCodec.decode("""{"v":1,"state":"away","handover":{"parkedAt":1,"open":[$many],"resolved":[]}}""")!!.first as State.Away
        assertTrue(a.handover.open.size <= 64)
    }

    @Test fun `ST-07 이력 상한 50건`() {
        val (_, hist) = HandoverCodec.decode(HandoverCodec.encode(State.Driving, List(80) { h }))!!
        assertEquals(50, hist.size)
    }

    @Test fun `ST-08 중복 항목은 하나로 (화면 목록 키 중복 방지)`() {
        val json = """{"v":1,"state":"boarded","handover":{"parkedAt":1,
            "open":[{"type":"CABIN_LIGHT_ON"},{"type":"CABIN_LIGHT_ON"}],
            "resolved":[{"item":{"type":"DOORS_UNLOCKED"},"at":1},{"item":{"type":"DOORS_UNLOCKED"},"at":2}]}}"""
        val b = HandoverCodec.decode(json)!!.first as State.Boarded
        assertEquals(1, b.handover.open.size)
        assertEquals(1, b.handover.resolved.size)
    }

    @Test fun `ST-09 필수 값이 빠진 인계는 '인계 없음'으로 처리`() {
        val (s, _) = HandoverCodec.decode("""{"v":1,"state":"away","handover":{"open":[]}}""")!!
        assertEquals(State.Driving, s)
    }

    @Test fun `ST-10 타입이 엉뚱한 값(문자열 대신 숫자 등)도 예외 없이 처리`() {
        val json = """{"v":"x","state":123,"handover":"oops","history":[1,"a",null,{"parkedAt":"zz"}]}"""
        val r = HandoverCodec.decode(json)
        assertTrue(r == null || r.first == State.Driving)
    }

    @Test fun `ST-11 저장 형식에 개인정보 필드가 없다 (위치·이름·차대번호)`() {
        val json = HandoverCodec.encode(State.Boarded(h), listOf(h)).lowercase()
        for (k in listOf("lat", "lon", "gps", "name", "vin", "phone", "email", "face", "voice")) {
            assertTrue("필드 '$k' 가 저장되면 안 됨", !Regex("\"$k").containsMatchIn(json))
        }
    }

    @Test fun `ST-14 확인 기록이 저장·복원된다`() {
        val windowKey = OpenItem(Type.WINDOW_OPEN, Spot(2, Spot.Side.LEFT), 40).key
        val acked = h.copy(acknowledged = setOf(windowKey))
        val b = HandoverCodec.decode(HandoverCodec.encode(State.Boarded(acked), emptyList()))!!.first as State.Boarded
        assertEquals(setOf(windowKey), b.handover.acknowledged)
    }

    @Test fun `ST-15 변조로 안전 항목을 '확인됨'에 넣어 경고를 숨길 수 없다`() {
        val json = """{"v":1,"state":"boarded","handover":{"parkedAt":1,
            "open":[{"type":"DOORS_UNLOCKED"},{"type":"CABIN_LIGHT_ON"}],"resolved":[],
            "ack":["DOORS_UNLOCKED:-","CABIN_LIGHT_ON:-","GHOST:-"]}}"""
        val b = HandoverCodec.decode(json)!!.first as State.Boarded
        assertEquals(setOf("CABIN_LIGHT_ON:-"), b.handover.acknowledged)
    }
}
