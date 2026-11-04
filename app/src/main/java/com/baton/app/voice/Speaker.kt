package com.baton.app.voice

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import com.baton.app.security.BatonLog
import java.util.Locale

/**
 * Pleos TTS(온디바이스)로 안내 음성을 낸다.
 * 음성이 없는 언어는 조용히 건너뛰고 화면 표시만 한다 (2026-11-04 실측: ko·en만 음성 생성).
 */
class Speaker(context: Context) {

    private val tts = TextToSpeech(context.applicationContext, Mode.ON_DEVICE)
    @Volatile private var ready = false

    init {
        runCatching {
            tts.initialize(object : OnInitializeListener {
                override fun onSuccess() { ready = true }
                override fun onFailure(error: ErrorType) { BatonLog.w(TAG, "TTS 초기화 실패: $error") }
            })
        }.onFailure { BatonLog.w(TAG, "TTS 초기화 예외: ${it.message}") }
    }

    fun canSpeak(locale: Locale) = ready && locale.language in VOICE_LANGUAGES

    fun say(text: String, locale: Locale) {
        if (!canSpeak(locale)) return
        runCatching {
            tts.setLocale(if (locale.language == "ko") Locale.KOREA else Locale.US)
            tts.speak(text, false)
        }.onFailure { BatonLog.w(TAG, "발화 실패: ${it.message}") }
    }

    fun release() { runCatching { tts.release() } }

    companion object {
        private const val TAG = "BatonVoice"
        val VOICE_LANGUAGES = setOf("ko", "en")
    }
}
