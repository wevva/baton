package com.baton.app

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import com.baton.app.security.BatonLog
import java.util.Locale

/**
 * Pleos TextToSpeech SDK로 한국어 음성 안내가 가능한지 확인한다.
 * (안드로이드 표준 TextToSpeech는 이 에뮬레이터에서 초기화 실패: status=-1)
 */
class TtsProbe(
    context: Context,
    private val mode: Mode = Mode.ON_DEVICE,
    private val onUpdate: (String, String) -> Unit,
) {

    private val tts = TextToSpeech(context.applicationContext, mode)
    private var ready = false

    private val events = object : EventListener {
        override fun onStart() = report("TTS 발화", "시작")
        override fun onReady() = report("TTS 발화", "준비")
        override fun onDone() = report("TTS 발화", "완료")
        override fun onError(error: ErrorType) = report("TTS 발화", "오류: $error")
        override fun onStop() = report("TTS 발화", "중지")
        override fun onUpdatedRms(rms: Double) = Unit
    }

    init {
        report("TTS 모드", mode.name)
        try {
            tts.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    ready = true
                    // SDK 내부 객체는 initialize 이후에 생성됨 → 리스너는 성공 뒤에 등록
                    runCatching { tts.addEventListener(events) }.onFailure { report("TTS 리스너", "등록 실패: ${it.message}") }
                    runCatching { tts.setLocale(Locale.KOREA) }
                        .onSuccess { report("TTS 초기화", "성공, 한국어 설정") }
                        .onFailure { report("TTS 초기화", "성공, 한국어 설정 실패: ${it.message}") }
                }
                override fun onFailure(error: ErrorType) = report("TTS 초기화", "실패: $error")
            })
        } catch (e: Exception) {
            report("TTS 초기화", "예외: ${e.javaClass.simpleName} ${e.message ?: ""}")
        }
    }

    private val samples = listOf(
        Locale.KOREA to "바통입니다. 이전 운전자가 남긴 일이 두 가지 있어요. 뒷좌석 창문이 조금 열려 있고, 배터리는 육십이 퍼센트입니다.",
        Locale.US to "This is Baton. The previous driver left two open items. A rear window is slightly open, and the battery is at sixty two percent.",
        Locale.JAPAN to "バトンです。前のドライバーから二つの引き継ぎがあります。後部座席の窓が少し開いていて、バッテリーは六十二パーセントです。",
        Locale.CHINA to "这里是接力棒。上一位驾驶员留下了两件事。后排车窗略微打开，电池电量为百分之六十二。",
    )
    private var next = 0

    /** 누를 때마다 한국어 → 영어 → 일본어 → 중국어 순으로 한 문장씩 읽는다. */
    fun speakSample() {
        if (!ready) {
            report("TTS 발화", "아직 준비 안 됨")
            return
        }
        val (locale, text) = samples[next]
        next = (next + 1) % samples.size
        try {
            tts.setLocale(locale)
            tts.speak(text, false)
            report("TTS 요청", "${locale.toLanguageTag()} 전송됨")
        } catch (e: Exception) {
            report("TTS 요청", "${locale.toLanguageTag()} 예외: ${e.javaClass.simpleName} ${e.message ?: ""}")
        }
    }

    fun release() {
        runCatching { tts.removeEventListener(events) }
        runCatching { tts.release() }
    }

    private fun report(name: String, value: String) {
        BatonLog.i(SignalProbe.TAG, "$name = $value")
        onUpdate(name, value)
    }
}
