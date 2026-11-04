package com.baton.app.handover

import com.baton.app.handover.HandoverMachine.Handover
import com.baton.app.handover.HandoverMachine.Resolved
import com.baton.app.handover.HandoverMachine.State
import com.baton.app.vehicle.Spot
import org.json.JSONArray
import org.json.JSONObject

/**
 * 인계 상태 ↔ JSON 변환. 저장소·암호화와 분리해 단위 테스트로 검증한다.
 * 읽을 때는 신뢰하지 않는다: 모르는 항목·잘못된 값은 버리고, 개수·범위를 제한한다.
 */
object HandoverCodec {
    const val VERSION = 1
    private const val MAX_ITEMS = 64
    private const val MAX_HISTORY = 50

    fun encode(state: State, history: List<Handover>): String {
        val root = JSONObject().put("v", VERSION)
        when (state) {
            is State.Away -> root.put("state", "away").put("handover", enc(state.handover))
            is State.Boarded -> root.put("state", "boarded").put("handover", enc(state.handover))
            else -> root.put("state", "none")
        }
        root.put("history", JSONArray(history.takeLast(MAX_HISTORY).map { enc(it) }))
        return root.toString()
    }

    /** 손상·형식 오류면 null (호출자는 '저장된 인계 없음'으로 처리) */
    fun decode(json: String): Pair<State, List<Handover>>? = runCatching {
        val root = JSONObject(json)
        if (root.optInt("v", VERSION) > VERSION) return null // 더 새 버전이 쓴 파일은 해석하지 않음
        val history = root.optJSONArray("history")
            ?.let { a -> (0 until minOf(a.length(), MAX_HISTORY)).mapNotNull { decHandover(a.optJSONObject(it)) } }
            .orEmpty()
        val state = when (root.optString("state")) {
            "away" -> decHandover(root.optJSONObject("handover"))?.let { State.Away(it) } ?: State.Driving
            "boarded" -> decHandover(root.optJSONObject("handover"))?.let { State.Boarded(it) } ?: State.Driving
            else -> State.Driving
        }
        state to history
    }.getOrNull()

    private fun enc(h: Handover) = JSONObject()
        .put("parkedAt", h.parkedAtMs)
        .putOpt("socAtPark", h.socAtPark)
        .putOpt("socAtBoard", h.socAtBoard)
        .putOpt("receivedAt", h.receivedAtMs)
        .put("open", JSONArray(h.open.map { enc(it) }))
        .put("resolved", JSONArray(h.resolved.map { JSONObject().put("item", enc(it.item)).put("at", it.atMs) }))

    private fun enc(i: OpenItem) = JSONObject().put("type", i.type.name).putOpt("spot", i.spot?.code).putOpt("value", i.value)

    private fun decHandover(o: JSONObject?): Handover? {
        if (o == null || !o.has("parkedAt")) return null
        return Handover(
            parkedAtMs = o.getLong("parkedAt"),
            socAtPark = o.percentOrNull("socAtPark"),
            socAtBoard = o.percentOrNull("socAtBoard"),
            receivedAtMs = if (o.has("receivedAt") && !o.isNull("receivedAt")) o.getLong("receivedAt") else null,
            open = o.optJSONArray("open").items { decItem(it) }.distinctBy { it.key },
            resolved = o.optJSONArray("resolved").items { r ->
                decItem(r.optJSONObject("item"))?.let { Resolved(it, r.optLong("at")) }
            }.distinctBy { it.item.key },
        )
    }

    /** 모르는 항목 종류(새 버전에서 추가된 것 등)·잘못된 위치는 버리고 나머지는 살린다 */
    private fun decItem(o: JSONObject?): OpenItem? {
        if (o == null) return null
        val type = runCatching { OpenItem.Type.valueOf(o.getString("type")) }.getOrNull() ?: return null
        val spot = if (o.has("spot")) Spot.fromCode(o.optString("spot")) ?: return null else null
        val value = if (o.has("value") && !o.isNull("value")) o.optInt("value").coerceIn(0, 100) else null
        return OpenItem(type, spot, value)
    }

    private fun <T> JSONArray?.items(map: (JSONObject) -> T?): List<T> {
        if (this == null) return emptyList()
        return (0 until minOf(length(), MAX_ITEMS)).mapNotNull { i -> optJSONObject(i)?.let(map) }
    }

    private fun JSONObject.percentOrNull(k: String): Int? =
        if (has(k) && !isNull(k)) optInt(k, -1).takeIf { it in 0..100 } else null
}
