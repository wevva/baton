package com.baton.app.handover

/**
 * 수령 순간의 "바통 터치!" 연출을 할지 정한다.
 *
 * 가볍게 넘겨도 되는 날만 유쾌하게 한다. 아직 남은 안전 항목(브레이크액 부족, ESC 꺼짐 등)이 있는데
 * 신나는 소리를 내면 경고가 가벼워 보이므로, 그때는 차분한 기존 안내만 한다.
 */
object ReceiptCheer {
    fun cheerful(h: HandoverMachine.Handover): Boolean = h.open.none { it.tier == OpenItem.Tier.SAFETY }
}
