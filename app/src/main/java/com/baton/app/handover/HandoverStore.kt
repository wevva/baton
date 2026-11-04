package com.baton.app.handover

import android.content.Context
import android.util.Log
import com.baton.app.handover.HandoverMachine.Handover
import com.baton.app.handover.HandoverMachine.Resolved
import com.baton.app.handover.HandoverMachine.State
import com.baton.app.vehicle.Spot
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 인계 상태와 이력을 차량 내부 저장소(앱 전용 폴더)에 JSON으로 저장한다.
 * 서버 없이 차 안에서만 동작(오프라인 우선). 개인 위치·얼굴·음성은 저장하지 않는다.
 */
class HandoverStore(context: Context, private val maxHistory: Int = 50) {

    private val file = File(context.filesDir, "handover.json")

    fun save(state: State, history: List<Handover>) {
        runCatching {
            val root = JSONObject()
            when (state) {
                is State.Away -> root.put("state", "away").put("handover", enc(state.handover))
                is State.Boarded -> root.put("state", "boarded").put("handover", enc(state.handover))
                else -> root.put("state", "none")
            }
            root.put("history", JSONArray(history.takeLast(maxHistory).map { enc(it) }))
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(root.toString())
            tmp.renameTo(file) // 쓰는 도중 전원이 꺼져도 이전 파일은 안전
        }.onFailure { Log.w(TAG, "저장 실패: ${it.message}") }
    }

    fun load(): Pair<State, List<Handover>>? = runCatching {
        if (!file.exists()) return null
        val root = JSONObject(file.readText())
        val history = root.optJSONArray("history")?.let { a -> (0 until a.length()).map { dec(a.getJSONObject(it)) } }.orEmpty()
        val state = when (root.optString("state")) {
            "away" -> State.Away(dec(root.getJSONObject("handover")))
            "boarded" -> State.Boarded(dec(root.getJSONObject("handover")))
            else -> State.Driving
        }
        state to history
    }.onFailure { Log.w(TAG, "불러오기 실패: ${it.message}") }.getOrNull()

    private fun enc(h: Handover) = JSONObject()
        .put("parkedAt", h.parkedAtMs)
        .putOpt("socAtPark", h.socAtPark)
        .putOpt("socAtBoard", h.socAtBoard)
        .putOpt("receivedAt", h.receivedAtMs)
        .put("open", JSONArray(h.open.map { enc(it) }))
        .put("resolved", JSONArray(h.resolved.map { JSONObject().put("item", enc(it.item)).put("at", it.atMs) }))

    private fun enc(i: OpenItem) = JSONObject().put("type", i.type.name).putOpt("spot", i.spot?.code).putOpt("value", i.value)

    private fun dec(o: JSONObject) = Handover(
        parkedAtMs = o.getLong("parkedAt"),
        socAtPark = o.optIntOrNull("socAtPark"),
        socAtBoard = o.optIntOrNull("socAtBoard"),
        receivedAtMs = if (o.has("receivedAt") && !o.isNull("receivedAt")) o.getLong("receivedAt") else null,
        open = o.getJSONArray("open").let { a -> (0 until a.length()).mapNotNull { decItem(a.getJSONObject(it)) } },
        resolved = o.getJSONArray("resolved").let { a ->
            (0 until a.length()).mapNotNull { i ->
                val r = a.getJSONObject(i)
                decItem(r.getJSONObject("item"))?.let { Resolved(it, r.getLong("at")) }
            }
        },
    )

    /** 모르는 항목 종류(새 버전에서 추가된 것 등)는 버리고 나머지는 살린다 */
    private fun decItem(o: JSONObject): OpenItem? {
        val type = runCatching { OpenItem.Type.valueOf(o.getString("type")) }.getOrNull() ?: return null
        val spot = if (o.has("spot")) Spot.fromCode(o.getString("spot")) else null
        return OpenItem(type, spot, o.optIntOrNull("value"))
    }

    private fun JSONObject.optIntOrNull(k: String): Int? = if (has(k) && !isNull(k)) getInt(k) else null

    companion object { private const val TAG = "BatonStore" }
}
