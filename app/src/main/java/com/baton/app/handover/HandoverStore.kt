package com.baton.app.handover

import android.content.Context
import com.baton.app.security.BatonLog
import com.baton.app.handover.HandoverMachine.Handover
import com.baton.app.handover.HandoverMachine.State
import com.baton.app.security.SecureFile
import java.io.File
import java.util.concurrent.Executors

/**
 * 인계 상태와 이력을 차량 내부 앱 전용 폴더에 **암호화해서** 저장한다 (AES-256-GCM, 키는 Android Keystore).
 * 서버로 보내지 않는다(오프라인 우선). 위치·얼굴·음성·차대번호는 저장하지 않는다.
 */
class HandoverStore(context: Context) {

    private val secure = SecureFile(File(context.filesDir, "handover.bin"))
    private val legacyPlain = File(context.filesDir, "handover.json") // v0 평문 형식

    /** 암호화·파일 쓰기는 이 전용 스레드 하나에서 순서대로 (메인 스레드 멈춤 방지, 쓰기 순서 보장) */
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "baton-store").apply { isDaemon = true } }

    /** 내용은 지금 만들고(호출 시점의 상태 그대로), 암호화해 쓰는 일은 뒤에서 한다 */
    fun save(state: State, history: List<Handover>) {
        val json = HandoverCodec.encode(state, history)
        writer.execute {
            runCatching { secure.write(json.toByteArray()) }
                .onFailure { BatonLog.w(TAG, "저장 실패: ${it.javaClass.simpleName}") }
        }
    }

    fun load(): Pair<State, List<Handover>>? {
        // 예전 평문 파일이 있으면 한 번 읽어 암호화 저장으로 옮기고 평문은 지운다
        if (legacyPlain.exists()) {
            val migrated = runCatching { HandoverCodec.decode(legacyPlain.readText()) }.getOrNull()
            legacyPlain.delete()
            if (migrated != null) {
                save(migrated.first, migrated.second) // 평문을 지운 뒤라 바로 써 둔다(뒤 스레드)
                return migrated
            }
        }
        val bytes = runCatching { secure.read() }.getOrNull() ?: return null
        val decoded = HandoverCodec.decode(String(bytes))
        if (decoded == null) {
            BatonLog.w(TAG, "저장 파일 해석 실패 → 무시")
            secure.delete()
        }
        return decoded
    }

    companion object {
        private const val TAG = "BatonStore"
    }
}
