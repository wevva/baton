package com.baton.app

import ai.pleos.playground.vehicle.Vehicle
import android.content.Context
import com.baton.app.security.BatonLog
import com.baton.app.handover.Detector
import com.baton.app.handover.HandoverMachine
import com.baton.app.handover.HandoverMachine.State
import com.baton.app.handover.HandoverStore
import com.baton.app.handover.OpenItem
import com.baton.app.i18n.ItemText
import com.baton.app.vehicle.ProfileDiscovery
import com.baton.app.vehicle.VehicleProfile
import com.baton.app.vehicle.VehicleSignals
import com.baton.app.vehicle.VehicleSnapshot
import com.baton.app.voice.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/** 화면이 그리는 데 필요한 모든 것 */
data class UiState(
    val ready: Boolean = false,
    val profile: VehicleProfile = VehicleProfile(),
    val snapshot: VehicleSnapshot = VehicleSnapshot(),
    val state: State = State.Driving,
    /** 주차 중(아직 하차 전) 미리보기: 지금 내리면 넘겨질 일 */
    val preview: List<OpenItem> = emptyList(),
    val locale: Locale = Locale.getDefault(),
    /** 운전자가 '확인했어요'로 넘긴 항목 키 (신호로 해결되지 않는 항목용) */
    val acknowledged: Set<String> = emptySet(),
    /** 방금 확인 처리한 항목 — 8초간 되돌리기 가능 */
    val undoable: OpenItem? = null,
    /** 출발까지 끝난 인계 기록 (최근 것이 먼저) */
    val history: List<HandoverMachine.Handover> = emptyList(),
)

/**
 * 차량 프로필 탐지 → 신호 구독 → 상태 머신 → 화면/음성까지 바통의 흐름을 묶는다.
 */
class BatonController(
    private val context: Context,
    private val vehicle: Vehicle,
    private val scope: CoroutineScope,
) {
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val speaker = Speaker(context)
    private val detector = Detector()
    private val store = HandoverStore(context)
    private var signals: VehicleSignals? = null
    private var machine: HandoverMachine? = null

    fun start() = scope.launch {
        delay(500) // SDK 서비스 연결 대기
        val profile = ProfileDiscovery(vehicle).discover().profile
        val m = HandoverMachine(profile, detector).also { machine = it }
        store.load()?.let { (saved, history) ->
            m.restore(saved, history)
            BatonLog.i(TAG, "복원: ${saved::class.simpleName}, 이력 ${history.size}건")
        }
        val s = VehicleSignals(vehicle, profile).also { signals = it }
        _ui.value = _ui.value.copy(ready = true, profile = profile, state = m.state, history = m.history.reversed())
        s.start()
        // 신호가 바뀌지 않아도 '하차 2초 유지' 같은 시간 조건을 판단하도록 1초마다 확인
        launch {
            while (true) {
                delay(1_000)
                val before = m.state
                val after = m.onTick(System.currentTimeMillis())
                if (before != after) apply(m, profile, before, after, _ui.value.snapshot)
            }
        }
        s.snapshot.collect { snap ->
            val before = m.state
            val after = m.onSnapshot(snap)
            apply(m, profile, before, after, snap)
        }
    }

    /** 상태 변화를 화면·저장·음성에 반영 (메인 스레드에서만 호출되어 순서가 보장됨) */
    private fun apply(m: HandoverMachine, profile: VehicleProfile, before: State, after: State, snap: VehicleSnapshot) {
        _ui.value = _ui.value.copy(
            snapshot = snap,
            state = after,
            history = m.history.reversed(),
            preview = if (after is State.Parked) detector.detect(profile, snap) else emptyList(),
            // 새 인계가 시작되면(하차) 이전 확인 기록은 비운다
            acknowledged = if (after is State.Away && before !is State.Away) emptySet() else _ui.value.acknowledged,
        )
        if (before::class != after::class) BatonLog.i(TAG, "상태: ${before::class.simpleName} → ${after::class.simpleName} (남은 일=${(after as? State.Away)?.handover?.open?.size ?: (after as? State.Boarded)?.handover?.open?.size ?: "-"})")
        if (before != after) store.save(after, m.history)
        announce(before, after)
    }

    private var undoJob: kotlinx.coroutines.Job? = null

    fun acknowledge(item: OpenItem) {
        _ui.value = _ui.value.copy(acknowledged = _ui.value.acknowledged + item.key, undoable = item)
        undoJob?.cancel()
        undoJob = scope.launch {
            delay(UNDO_WINDOW_MS)
            _ui.value = _ui.value.copy(undoable = null)
        }
    }

    fun undo() {
        val item = _ui.value.undoable ?: return
        undoJob?.cancel()
        _ui.value = _ui.value.copy(acknowledged = _ui.value.acknowledged - item.key, undoable = null)
    }

    /** 바통 수령증 1탭 */
    fun receive() {
        val m = machine ?: return
        val after = m.receive(System.currentTimeMillis())
        _ui.value = _ui.value.copy(state = after)
        store.save(after, m.history)
    }

    fun setLocale(locale: Locale) {
        _ui.value = _ui.value.copy(locale = locale)
    }

    /** 탑승 순간 요약 1회 + 해결될 때마다 짧게. 같은 말 반복 금지 */
    private fun announce(before: State, after: State) {
        val text = ItemText(context, _ui.value.locale)
        val locale = _ui.value.locale
        if (before is State.Away && after is State.Boarded) {
            val h = after.handover
            val msg = if (h.allClear) text.str(R.string.voice_none)
            else text.str(R.string.voice_intro, h.open.size) + " " + h.open.joinToString(". ") { text.item(it) }
            BatonLog.i(TAG, "안내: $msg")
            speaker.say(msg, locale)
        }
        // 출발 순간(D단 진입) 확인 안 된 안전 항목이 남아 있으면 한 번만 알림
        if (before is State.Boarded && after is State.Driving) {
            val safety = before.handover.open.filter {
                it.type.tier == OpenItem.Tier.SAFETY && it.key !in _ui.value.acknowledged
            }
            if (safety.isNotEmpty()) {
                val msg = text.str(R.string.voice_depart_safety, safety.size) + " " + safety.joinToString(". ") { text.item(it) }
                BatonLog.i(TAG, "안내: $msg")
                speaker.say(msg, locale)
            }
        }
        if (before is State.Boarded && after is State.Boarded) {
            val newly = after.handover.resolved.size - before.handover.resolved.size
            if (newly > 0) {
                after.handover.resolved.takeLast(newly).forEach {
                    val msg = text.str(R.string.voice_resolved, text.done(it.item))
                    BatonLog.i(TAG, "안내: $msg")
                    speaker.say(msg, locale)
                }
            }
        }
    }

    fun stop() {
        signals?.stop()
        speaker.release()
    }

    companion object {
        const val TAG = "Baton"
        /** 되돌리기 가능 시간. 차 안에서는 손이 늦을 수 있어 일반 앱(약 5초)보다 길게 */
        const val UNDO_WINDOW_MS = 8_000L
    }
}
