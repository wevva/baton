package com.baton.app.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptPhraseTest {

    @Test fun `한국어 수령 표현은 인정`() {
        listOf("받았어요", "받았", "네 받았어요", "확인했어요", "알겠어요", "오케이").forEach {
            assertTrue(it, ReceiptPhrase.matches(it, "ko"))
        }
    }

    @Test fun `에뮬레이터에서 실제로 나온 오인식·중간 결과는 거절`() {
        // 2026-11-11 실측: "확인"(중간), "하긴 했어요"(오인식), "재미난 운행"(긴 문장 오인식)
        listOf("확인", "하긴 했어요", "재미난 운행", "", "   ").forEach {
            assertFalse(it, ReceiptPhrase.matches(it, "ko"))
        }
    }

    @Test fun `부정어가 섞이면 수령으로 치지 않음`() {
        listOf("안 받았어요", "못 받았어요", "아니 아직", "잠깐만요 받았", "취소 받았어요").forEach {
            assertFalse(it, ReceiptPhrase.matches(it, "ko"))
        }
    }

    @Test fun `영어 수령 표현`() {
        listOf("Got it", "got it.", "OK", "Okay!", "received", "Roger").forEach {
            assertTrue(it, ReceiptPhrase.matches(it, "en"))
        }
        listOf("not received", "token", "wait", "I didn't get it", "no").forEach {
            assertFalse(it, ReceiptPhrase.matches(it, "en"))
        }
    }

    @Test fun `음성 인식이 없는 언어는 항상 거절`() {
        assertFalse(ReceiptPhrase.matches("受け取りました", "ja"))
        assertFalse(ReceiptPhrase.matches("已接收", "zh"))
    }
}
