package com.baton.app

import ai.pleos.playground.vehicle.Vehicle
import android.content.Context
import com.baton.app.handover.Announcer
import com.baton.app.handover.Detector
import com.baton.app.handover.HandoverMachine
import com.baton.app.handover.HandoverMachine.State
import com.baton.app.handover.HandoverStore
import com.baton.app.handover.OpenItem
import com.baton.app.i18n.ItemText
import com.baton.app.security.BatonLog
import com.baton.app.vehicle.ProfileDiscovery
import com.baton.app.vehicle.VehicleProfile
import com.baton.app.vehicle.VehicleSignals
import com.baton.app.vehicle.VehicleSnapshot
import com.baton.app.voice.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
    /** 방금 확인 처리한 항목 — 잠시 되돌리기 가능 */
    val undoable: OpenItem? = null,
    /** 출발까지 끝난 인계 기록 (최근 것이 먼저) */
    val history: List<HandoverMachine.Handover> = emptyList(),
)

/**
 * 차량 프로필 탐지 → 신호 구독 → 상태 머신 → 화면/저장/음성까지 바통의 흐름을 묶는다.
 * 모든 상태 변경은 메인 스레드에서만 일어난다 (scope = Dispatchers.Main).
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
    private val announcer = Announcer()
    private val store = HandoverStore(context)
    private var signals: VehicleSignals? = null
    private var machine: HandoverMachine? = null
    private var profile = VehicleProfile()
    private var undoJob: Job? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            val p = discoverWithRetry()
            profile = p
            val m = HandoverMachine(p, detector).also { machine = it }
            store.load()?.let { (saved, history) ->
                m.restore(saved, history)
                BatonLog.i(TAG, "복원: ${saved::class.simpleName}, 이력 ${history.size}건")
            }
            val s = VehicleSignals(vehicle, p).also { signals = it }
            _ui.value = _ui.value.copy(ready = true, profile = p, state = m.state, history = m.history.reversed())
            s.start()
            // 신호가 바뀌지 않아도 '하차 2초 유지' 같은 시간 조건을 판단하도록 1초마다 확인
            launch {
                while (true) {
                    delay(1_000)
                    val before = m.state
                    val after = m.onTick(System.currentTimeMillis())
                    if (before != after) apply(before, after, _ui.value.snapshot)
                }
            }
            s.snapshot.collect { snap ->
                val before = m.state
                val after = m.onSnapshot(snap)
                apply(before, after, snap)
            }
        }
    }

    /**
     * 차량 서비스가 늦게 뜨면(시동 직후) 기능 확인이 모두 실패해 '빈 차'로 인식될 수 있다.
     * 아무 부위도 못 찾으면 2초 간격으로 다시 묻는다 (최대 약 1분). 그래도 없으면 그대로 진행.
     */
    private suspend fun discoverWithRetry(): VehicleProfile {
        repeat(MAX_DISCOVERY_TRIES) { attempt ->
            val p = ProfileDiscovery(vehicle, context).discover().profile
            val found = p.seats.isNotEmpty() || p.doors.isNotEmpty() || p.hasDoorLock || p.windows.isNotEmpty()
            if (found) return p
            BatonLog.w(TAG, "차량 구성 인식 실패 (${attempt + 1}/$MAX_DISCOVERY_TRIES) → 재시도")
            delay(2_000)
        }
        return ProfileDiscovery(vehicle, context).discover().profile
    }

    /** 상태 변화를 화면·저장·음성에 반영 */
    private fun apply(before: State, after: State, snap: VehicleSnapshot) {
        val m = machine ?: return
        _ui.value = _ui.value.copy(
            snapshot = snap,
            state = after,
            history = m.history.reversed(),
            // 주차 중 미리보기: 앉아 있을 때 정상인 '잠금 해제·문 열림'은 빼고 보여 준다
            preview = if (after is State.Parked) detector.detect(profile, snap).filter { it.type !in PREVIEW_HIDDEN } else emptyList(),
            undoable = if (after is State.Boarded) _ui.value.undoable else null,
        )
        if (before::class != after::class) {
            BatonLog.i(TAG, "상태: ${before::class.simpleName} → ${after::class.simpleName} (남은 일=${openCount(after)})")
        }
        if (before != after) store.save(after, m.history)
        speak(announcer.decide(before, after))
    }

    private fun openCount(s: State) = when (s) {
        is State.Away -> s.handover.open.size.toString()
        is State.Boarded -> s.handover.open.size.toString()
        else -> "-"
    }

    /** 확인 처리 (할 일·참고 항목만. 안전 항목은 상태 머신이 거부한다) */
    fun acknowledge(item: OpenItem) {
        val m = machine ?: return
        if (!m.acknowledge(item.key)) return
        commit(m)
        _ui.value = _ui.value.copy(undoable = item)
        undoJob?.cancel()
        undoJob = scope.launch {
            delay(UNDO_WINDOW_MS)
            _ui.value = _ui.value.copy(undoable = null)
        }
    }

    fun undo() {
        val m = machine ?: return
        val item = _ui.value.undoable ?: return
        undoJob?.cancel()
        m.unacknowledge(item.key)
        commit(m)
        _ui.value = _ui.value.copy(undoable = null)
    }

    /** 바통 수령증 1탭 */
    fun receive() {
        val m = machine ?: return
        m.receive(System.currentTimeMillis())
        commit(m)
    }

    private fun commit(m: HandoverMachine) {
        _ui.value = _ui.value.copy(state = m.state)
        store.save(m.state, m.history)
    }

    fun setLocale(locale: Locale) {
        _ui.value = _ui.value.copy(locale = locale)
    }

    private fun speak(events: List<Announcer.Event>) {
        if (events.isEmpty()) return
        val locale = _ui.value.locale
        val text = ItemText(context, locale, profile.driverSeat)
        for (e in events) {
            val msg = when (e) {
                is Announcer.Event.Intro ->
                    if (e.open.isEmpty()) text.str(R.string.voice_none)
                    else text.plural(R.plurals.voice_intro, e.open.size, e.open.size) + " " + e.open.joinToString(". ") { text.item(it) }
                is Announcer.Event.Resolved -> text.str(R.string.voice_resolved, text.done(e.item))
                is Announcer.Event.DepartWithSafety ->
                    text.plural(R.plurals.voice_depart_safety, e.items.size, e.items.size) + " " + e.items.joinToString(". ") { text.item(it) }
            }
            BatonLog.i(TAG, "안내: $msg")
            speaker.say(msg, locale)
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
        private const val MAX_DISCOVERY_TRIES = 30
        private val PREVIEW_HIDDEN = setOf(OpenItem.Type.DOORS_UNLOCKED, OpenItem.Type.DOOR_OPEN)
    }
}
