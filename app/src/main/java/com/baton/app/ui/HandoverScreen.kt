package com.baton.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baton.app.R
import com.baton.app.UiState
import com.baton.app.handover.HandoverMachine.State
import com.baton.app.handover.OpenItem
import com.baton.app.i18n.ItemText
import java.util.Locale

private object C {
    val bg = Color(0xFF0E1116)
    val card = Color(0xFF1A1F27)
    val text = Color(0xFFF2F4F7)
    val sub = Color(0xFF9AA4B2)
    val safety = Color(0xFFFF6B5B)
    val todo = Color(0xFFF2B84B)
    val info = Color(0xFF7CB7FF)
    val done = Color(0xFF4CC38A)
}

@Composable
fun HandoverScreen(
    ui: UiState,
    onLocale: (Locale) -> Unit,
    onDiagnostics: () -> Unit,
    onAcknowledge: (OpenItem) -> Unit = {},
    onUndo: () -> Unit = {},
    onReceive: () -> Unit = {},
) {
    // 화면 언어 = 현재 앱 설정 언어 (LocalConfiguration이 바뀌면 다시 그림)
    val config = LocalConfiguration.current
    val ctx = LocalContext.current
    val t = remember(config) { ItemText(ctx, config.locales[0]) }
    Box(Modifier.fillMaxSize().background(C.bg)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp)) {
            TopBar(t, t.locale, onLocale, onDiagnostics)
            Spacer(Modifier.height(24.dp))
            when (val s = ui.state) {
                State.Driving -> Driving(t, ui)
                State.Parked -> Parked(t, ui.preview)
                is State.Away -> Handover(t, t.str(R.string.state_away), s.handover, ui, showEnergy = false, onAcknowledge = null, onReceive = null)
                is State.Boarded -> Handover(t, t.str(R.string.state_boarded), s.handover, ui, showEnergy = true, onAcknowledge = onAcknowledge, onReceive = onReceive)
            }
        }
        val undoable = ui.undoable
        if (undoable != null && ui.state is State.Boarded) {
            UndoBar(t, undoable, onUndo, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** 실수에 관대하게: 확인 처리 후 8초간 되돌리기 */
@Composable
private fun UndoBar(t: ItemText, item: OpenItem, onUndo: () -> Unit, modifier: Modifier) {
    Row(
        modifier
            .padding(bottom = 32.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(C.text)
            .padding(start = 28.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(t.str(R.string.acknowledged, t.item(item)), color = C.bg, fontSize = 22.sp)
        Spacer(Modifier.width(24.dp))
        Text(
            t.str(R.string.undo), color = Color(0xFF1F6FEB), fontSize = 22.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onUndo() }.padding(horizontal = 18.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun TopBar(t: ItemText, locale: Locale, onLocale: (Locale) -> Unit, onDiagnostics: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(t.str(R.string.app_name), color = C.text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        for (l in ItemText.SUPPORTED) {
            val selected = l.language == locale.language
            Text(
                l.language.uppercase(),
                color = if (selected) C.bg else C.sub,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) C.text else C.card)
                    .clickable { onLocale(l) }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }
        Text(
            "⚙", color = C.sub, fontSize = 22.sp,
            modifier = Modifier.padding(start = 16.dp).clickable { onDiagnostics() }.padding(8.dp)
        )
    }
}

/** 주행 중: 한 줄만. 상세는 주차 후 (NHTSA 2초 원칙, Android for Cars 주행 제한) */
@Composable
private fun Driving(t: ItemText, ui: UiState) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(t.str(R.string.state_driving), color = C.sub, fontSize = 44.sp, fontWeight = FontWeight.Bold)
    }
}

/** 주차 중(아직 하차 전): 지금 고치면 다음 사람에게 넘어가지 않는다 */
@Composable
private fun Parked(t: ItemText, preview: List<OpenItem>) {
    Text(t.str(R.string.state_parked), color = C.text, fontSize = 40.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(20.dp))
    if (preview.isEmpty()) {
        Text(t.str(R.string.all_clear), color = C.done, fontSize = 28.sp)
    } else {
        ItemList(t, preview, emptyList())
    }
}

@Composable
private fun Handover(
    t: ItemText,
    title: String,
    h: com.baton.app.handover.HandoverMachine.Handover,
    ui: UiState,
    showEnergy: Boolean,
    onAcknowledge: ((OpenItem) -> Unit)?,
    onReceive: (() -> Unit)?,
) {
    val open = h.open.filter { it.key !in ui.acknowledged }
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(560.dp)) {
            Text(title, color = C.text, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            val socPark = h.socAtPark
            val socNow = ui.snapshot.batterySoc
            if (showEnergy && socPark != null && socNow != null) {
                Text(t.str(R.string.energy_line, socPark, socNow), color = C.sub, fontSize = 24.sp)
                // 주차 중 변화 = 탑승 시점 잔량 - 주차 시점 잔량
                val delta = (h.socAtBoard ?: socNow) - socPark
                Text(
                    when {
                        delta >= 2 -> t.str(R.string.energy_charged, delta)
                        delta <= -2 -> t.str(R.string.energy_used, -delta)
                        else -> t.str(R.string.energy_same)
                    },
                    color = if (delta >= 2) C.done else C.sub, fontSize = 22.sp,
                )
            }
            if (onReceive != null) {
                Spacer(Modifier.height(24.dp))
                val at = h.receivedAtMs
                if (at == null) {
                    Text(
                        t.str(R.string.receive_button), color = C.bg, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(C.done)
                            .clickable { onReceive() }
                            .padding(horizontal = 40.dp, vertical = 20.dp),
                    )
                } else {
                    val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, t.locale).format(java.util.Date(at))
                    Text("✓ " + t.str(R.string.received_at, time), color = C.done, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(24.dp))
            when {
                open.isEmpty() && h.resolved.isEmpty() -> Text(t.str(R.string.empty_handover), color = C.done, fontSize = 28.sp)
                open.isEmpty() -> Text(t.str(R.string.all_clear), color = C.done, fontSize = 28.sp)
                onAcknowledge != null -> Text(t.str(R.string.hint_tap_to_ack), color = C.sub, fontSize = 20.sp)
            }
        }
        Spacer(Modifier.width(32.dp))
        Column(Modifier.weight(1f)) {
            ItemList(t, open, h.resolved.takeLast(3).reversed().map { it.item }, onAcknowledge)
        }
    }
}

@Composable
private fun ItemList(t: ItemText, open: List<OpenItem>, resolved: List<OpenItem>, onTap: ((OpenItem) -> Unit)? = null) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        items(open, key = { "o" + it.key }) { ItemCard(t, it, done = false, onTap = onTap) }
        items(resolved, key = { "r" + it.key }) { ItemCard(t, it, done = true, onTap = null) }
    }
}

@Composable
private fun ItemCard(t: ItemText, item: OpenItem, done: Boolean, onTap: ((OpenItem) -> Unit)?) {
    val accent = when {
        done -> C.done
        item.type.tier == OpenItem.Tier.SAFETY -> C.safety
        item.type.tier == OpenItem.Tier.TODO -> C.todo
        else -> C.info
    }
    // 색 + 기호 + 문구 3중 표시 (색각 이상 사용자 고려)
    val symbol = when {
        done -> "✓"
        item.type.tier == OpenItem.Tier.SAFETY -> "!"
        else -> "•"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(C.card)
            .border(2.dp, accent.copy(alpha = if (done) 0.6f else 0.35f), RoundedCornerShape(20.dp))
            .then(if (onTap != null) Modifier.clickable { onTap(item) } else Modifier)
            .padding(horizontal = 28.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(accent),
            contentAlignment = Alignment.Center,
        ) { Text(symbol, color = C.bg, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (done) t.done(item) else t.item(item),
                color = if (done) C.done else C.text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            if (done) t.str(R.string.resolved) else t.tier(item.type.tier),
            color = accent, fontSize = 20.sp, fontWeight = FontWeight.Bold,
        )
    }
}
