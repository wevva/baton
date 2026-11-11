package com.baton.app.voice

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.baton.app.security.BatonLog
import java.io.File
import java.util.Locale

/**
 * 탑승 안내가 끝난 뒤 잠깐만 들으며 "받았어요"를 기다린다 (Pleos SpeechToText, 온디바이스).
 *
 * - 듣는 시간은 [LISTEN_MS]뿐이고, 소리·문장은 저장하지 않는다. 인식은 차 안에서만 일어난다(서버 모드 미사용).
 * - 마이크는 Pleos 음성 서비스가 연다. 바통은 녹음 권한을 갖지 않는다.
 * - 개발용(디버그 빌드): files/stt_inject.pcm(16kHz·16bit·모노)이 있으면 마이크 대신 그 소리를 넣고 지운다 — E2E용.
 */
class ReceiptListener(context: Context) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val stt = runCatching { SpeechToText(appContext, Mode.ON_DEVICE) }.getOrNull()
    @Volatile private var ready = false
    @Volatile private var session: Session? = null

    private class Session(
        val language: String, val onMatch: () -> Unit, val onEnd: () -> Unit,
        /** 개발용 주입 소리 (디버그 빌드, 없으면 마이크) */
        val injected: ByteArray?,
    ) {
        var done = false
        var startedAt = 0L
        var retried = false
    }

    init {
        val s = stt
        if (s != null) runCatching {
            s.addListener(object : ResultListener {
                override fun onUpdated(text: String, isFinal: Boolean) { main.post { heard(text) } }
                override fun onUpdatedEpdData(on: Long, off: Long) {}
                override fun onStartedRecognition() {}
                override fun onEndedRecognition() {}
                override fun onError(error: ErrorType) { BatonLog.w(TAG, "인식 오류 $error"); main.post { onEngineError() } }
                override fun onReady() {}
                override fun onUpdatedRms(rms: Double) {}
            })
            s.initialize(object : OnInitializeListener {
                override fun onSuccess() { ready = true }
                override fun onFailure(error: ErrorType) { BatonLog.w(TAG, "음성 인식 초기화 실패: $error") }
            })
        }.onFailure { BatonLog.w(TAG, "음성 인식 사용 불가: ${it.message}") }
    }

    fun canListen(locale: Locale) = ready && locale.language in ReceiptPhrase.LANGUAGES

    /** 메인 스레드에서 호출. 들리면 onMatch, 시간이 다 되거나 오류면 onEnd (둘 중 하나만, 한 번) */
    fun listen(locale: Locale, onMatch: () -> Unit, onEnd: () -> Unit) {
        val s = stt
        if (s == null || !canListen(locale)) { onEnd(); return }
        stop()
        // 개발용 주입 파일이 있으면 마이크를 열지 않고 그 소리만 넣는다 (에뮬레이터 마이크 오류와 섞이지 않게)
        val injected = injectFile()?.takeIf { it.exists() }?.let { f -> runCatching { f.readBytes().also { f.delete() } }.getOrNull() }
        val sess = Session(locale.language, onMatch, onEnd, injected).also { session = it }
        runCatching { s.setLocale(if (locale.language == "ko") Locale.KOREA else Locale.US) }
        // 앞서 앱이 듣는 중에 종료됐다면 음성 서비스에 이전 세션이 남아 새 세션과 충돌한다(2026-11-11 실측) → 먼저 닫는다
        runCatching { s.stop() }
        main.postDelayed({ begin(sess) }, 300)
        main.postDelayed({ if (session === sess) finish(false) }, LISTEN_MS)
        BatonLog.i(TAG, "수령 음성 듣기 시작 (${LISTEN_MS / 1000}초)")
    }

    private fun begin(sess: Session) {
        if (session !== sess || sess.done) return
        val s = stt ?: return
        sess.startedAt = System.currentTimeMillis()
        runCatching { if (sess.injected != null) inject(sess) else s.request() }
            .onFailure { BatonLog.w(TAG, "듣기 시작 실패: ${it.message}"); finish(false) }
    }

    /** 시작 직후 엔진 오류는 남은 세션 충돌일 수 있어 한 번만 다시 연다. 그 밖에는 듣기를 끝낸다 */
    private fun onEngineError() {
        val sess = session ?: return
        if (sess.done) return
        if (!sess.retried && System.currentTimeMillis() - sess.startedAt < 3_000) {
            sess.retried = true
            BatonLog.i(TAG, "음성 인식 다시 시작")
            runCatching { stt?.stop() }
            main.postDelayed({ begin(sess) }, 500)
            return
        }
        finish(false)
    }

    private fun heard(text: String) {
        val sess = session ?: return
        if (sess.done) return
        if (ReceiptPhrase.matches(text, sess.language)) {
            BatonLog.i(TAG, "수령 음성 인식: \"$text\"")
            finish(true)
        }
    }

    private fun finish(matched: Boolean) {
        val sess = session ?: return
        if (sess.done) return
        sess.done = true
        session = null
        runCatching { stt?.stop() }
        if (matched) sess.onMatch() else { BatonLog.i(TAG, "수령 음성 듣기 종료(인식 없음)"); sess.onEnd() }
    }

    /** 듣는 중이면 멈춘다 (출발·하차·화면 터치 수령 시) */
    fun stop() {
        val sess = session ?: return
        sess.done = true
        session = null
        runCatching { stt?.stop() }
        sess.onEnd()
    }

    private fun injectFile(): File? = if (BatonLog.enabled) File(appContext.filesDir, "stt_inject.pcm") else null

    private fun inject(sess: Session) {
        val bytes = sess.injected ?: return
        val s = stt ?: return
        Thread {
            runCatching {
                var i = 0
                while (i < bytes.size && session === sess) {
                    val end = minOf(i + 3200, bytes.size)
                    s.sendAudio(bytes.copyOfRange(i, end))
                    i = end
                    // 실시간(100ms)보다 빠르게 넣어도 인식된다 (시험 시간 단축)
                    Thread.sleep(20)
                }
                s.completeAudioSend()
            }.onFailure { BatonLog.w(TAG, "주입 실패: ${it.message}") }
        }.start()
    }

    fun release() { runCatching { stt?.release() } }

    companion object {
        private const val TAG = "BatonListen"
        const val LISTEN_MS = 8_000L
    }
}
