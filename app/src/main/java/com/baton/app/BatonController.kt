package com.baton.app

import ai.pleos.playground.vehicle.Vehicle
import android.content.Context
import android.util.Log
import com.baton.app.handover.Detector
import com.baton.app.handover.HandoverMachine
import com.baton.app.handover.HandoverMachine.State
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
    private var signals: VehicleSignals? = null
    private var machine: HandoverMachine? = null

    fun start() = scope.launch {
        delay(500) // SDK 서비스 연결 대기
        val profile = ProfileDiscovery(vehicle).discover().profile
        val m = HandoverMachine(profile, detector).also { machine = it }
        val s = VehicleSignals(vehicle, profile).also { signals = it }
        _ui.value = _ui.value.copy(ready = true, profile = profile)
        s.start()
        s.snapshot.collect { snap ->
            val before = m.state
            val after = m.onSnapshot(snap)
            _ui.value = _ui.value.copy(
                snapshot = snap,
                state = after,
                preview = if (after is State.Parked) detector.detect(profile, snap) else emptyList(),
            )
            if (before::class != after::class) Log.i(TAG, "상태: ${before::class.simpleName} → ${after::class.simpleName} (좌석=${snap.seatOccupied.mapKeys { it.key.code }}, 남은 일=${(after as? State.Away)?.handover?.open?.size ?: (after as? State.Boarded)?.handover?.open?.size ?: "-"})")
            announce(before, after)
        }
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
            Log.i(TAG, "안내: $msg")
            speaker.say(msg, locale)
        }
        if (before is State.Boarded && after is State.Boarded) {
            val newly = after.handover.resolved.size - before.handover.resolved.size
            if (newly > 0) {
                after.handover.resolved.takeLast(newly).forEach {
                    val msg = text.str(R.string.voice_resolved, text.done(it.item))
                    Log.i(TAG, "안내: $msg")
                    speaker.say(msg, locale)
                }
            }
        }
    }

    fun stop() {
        signals?.stop()
        speaker.release()
    }

    companion object { const val TAG = "Baton" }
}
