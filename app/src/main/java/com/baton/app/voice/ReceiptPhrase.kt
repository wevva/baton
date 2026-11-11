package com.baton.app.voice

/**
 * 음성 인식 결과(중간 결과 포함)가 '받았어요'에 해당하는지 판단한다 (순수 로직 — 단위 테스트).
 *
 * 온디바이스 인식기는 최종 결과 없이 중간 결과만 주는 경우가 있어(2026-11-11 에뮬레이터 실측)
 * 중간 결과마다 이 판단을 돌린다. 오인식으로 수령이 찍히지 않도록 부정어가 섞이면 거절한다.
 */
object ReceiptPhrase {

    private val KO_ACCEPT = listOf("받았", "확인했", "확인 했", "알겠", "오케이", "알았어")
    private val KO_REJECT = listOf("안 받", "못 받", "아니", "안받", "못받", "잠깐", "취소")
    private val EN_ACCEPT = listOf("got it", "received", "okay", "ok", "roger", "understood")
    private val EN_REJECT = listOf("not ", "no ", "don't", "didn't", "wait", "cancel")

    /** 음성 수령을 지원하는 언어 (온디바이스 음성 안내가 되는 언어와 같다) */
    val LANGUAGES = setOf("ko", "en")

    fun matches(text: String, language: String): Boolean {
        val t = " " + text.lowercase().replace(Regex("[.,!?~]"), " ").replace(Regex("\\s+"), " ").trim() + " "
        return when (language) {
            "ko" -> KO_REJECT.none { it in t } && KO_ACCEPT.any { it in t }
            "en" -> EN_REJECT.none { it in t } && EN_ACCEPT.any { Regex("(^|\\s)" + Regex.escape(it) + "(\\s|$)").containsMatchIn(t) }
            else -> false
        }
    }
}
