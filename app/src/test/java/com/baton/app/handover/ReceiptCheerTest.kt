package com.baton.app.handover

import com.baton.app.handover.OpenItem.Type
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 수령 연출(CHEER-xx): 안전 항목이 남아 있으면 유쾌한 연출을 하지 않는다 */
class ReceiptCheerTest {

    private fun handover(vararg items: OpenItem) = HandoverMachine.Handover(parkedAtMs = 1, socAtPark = 60, open = items.toList())

    @Test fun `CHEER-01 넘겨받을 것이 없으면 바통 터치`() {
        assertTrue(ReceiptCheer.cheerful(handover()))
    }

    @Test fun `CHEER-02 할 일·참고만 남았으면 바통 터치`() {
        assertTrue(ReceiptCheer.cheerful(handover(
            OpenItem(Type.SETTING_CHANGED, code = "I_PEDAL"),
            OpenItem(Type.WASHER_FLUID_LOW),
        )))
    }

    @Test fun `CHEER-03 지금 켜진 고장 경고가 남았으면 차분하게`() {
        assertFalse(ReceiptCheer.cheerful(handover(OpenItem(Type.WARNING_ON, code = "TPMS"))))
    }

    @Test fun `CHEER-04 ESC 꺼짐이 남았으면 차분하게`() {
        assertFalse(ReceiptCheer.cheerful(handover(OpenItem(Type.SETTING_CHANGED, code = "ESC_OFF"))))
    }

    @Test fun `CHEER-06 지난 운행 중 켜졌던 타이어 경고(안전 등급)가 있으면 차분하게`() {
        assertFalse(ReceiptCheer.cheerful(handover(OpenItem(Type.WARNING_DURING_TRIP, value = 2, code = "TPMS"))))
    }

    @Test fun `CHEER-05 브레이크액 부족이 남았으면 차분하게`() {
        assertFalse(ReceiptCheer.cheerful(handover(OpenItem(Type.BRAKE_FLUID_LOW))))
    }
}
