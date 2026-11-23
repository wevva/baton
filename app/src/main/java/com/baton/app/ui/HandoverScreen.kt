package com.baton.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baton.app.R
import com.baton.app.UiState
import com.baton.app.handover.HandoverMachine
import com.baton.app.handover.HandoverMachine.State
import com.baton.app.handover.OpenItem
import com.baton.app.i18n.ItemText
import java.util.Locale

/**
 * 한국어는 기본 설정에서 글자 단위로 줄을 바꿔 "점검하/세요"처럼 단어가 잘린다.
 * 어절(띄어쓰기) 단위로 줄을 바꾸도록 문단 줄바꿈 + 구(phrase) 단위 규칙을 쓴다.
 */
private val WORDS = TextStyle(lineBreak = LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase))

/** 차량 화면 최소 터치 영역 (Android for Cars 디자인 기준 76dp) */
private val TOUCH = 76.dp

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
    /** null이면 진단 버튼을 숨긴다 (출시 버전) */
    onDiagnostics: (() -> Unit)?,
    onAcknowledge: (OpenItem) -> Unit = {},
    onUndo: () -> Unit = {},
    onReceive: () -> Unit = {},
    onHistory: () -> Unit = {},
    onCheer: () -> Unit = {},
) {
    // 화면 언어 = 현재 앱 설정 언어 (LocalConfiguration이 바뀌면 다시 그림)
    val config = LocalConfiguration.current
    val ctx = LocalContext.current
    val t = remember(config, ui.profile.driverSeat) { ItemText(ctx, config.locales[0], ui.profile.driverSeat) }
    // Pleos는 앱을 전체 칸(약 1680dp) 또는 내비 옆 반 칸(약 420~840dp)으로 띄운다. 폭에 맞춰 배치를 바꾼다
    BoxWithConstraints(Modifier.fillMaxSize().background(C.bg)) {
        val wide = maxWidth >= 1100.dp
        val compact = maxWidth < 640.dp
        Column(Modifier.fillMaxSize().padding(horizontal = if (compact) 24.dp else 48.dp, vertical = if (compact) 20.dp else 32.dp)) {
            TopBar(
                t, t.locale, onLocale, onDiagnostics,
                onHistory = if (ui.state is State.Driving) null else onHistory,
                // 연출 설정도 주차 중에만 (주행 중 화면 조작 최소화)
                cheer = if (ui.state is State.Driving) null else ui.cheer,
                onCheer = onCheer,
                compact = compact,
            )
            Spacer(Modifier.height(if (compact) 16.dp else 24.dp))
            when (val s = ui.state) {
                State.Driving -> Driving(t, ui)
                State.Parked -> Parked(t, ui.preview, compact)
                is State.Away -> Handover(t, t.str(R.string.state_away), s.handover, ui, showEnergy = false, onAcknowledge = null, onReceive = null, wide = wide, compact = compact)
                is State.Boarded -> Handover(t, t.str(R.string.state_boarded), s.handover, ui, showEnergy = true, onAcknowledge = onAcknowledge, onReceive = onReceive, wide = wide, compact = compact)
            }
        }
        val undoable = ui.undoable
        if (undoable != null && ui.state is State.Boarded) {
            UndoBar(t, undoable, onUndo, Modifier.align(Alignment.BottomCenter))
        }
        val at = ui.celebrateAt
        if (at != null && ui.state is State.Boarded) {
            BatonTouch(t, at, Modifier.align(Alignment.Center))
        }
    }
}

/**
 * 수령 순간 "바통 터치!" (약 1.8초). 바통이 왼쪽에서 날아와 가운데에서 멈추고 글자가 튀어나온 뒤 사라진다.
 * 화면을 가리는 시간이 짧고 터치를 막지 않는다. 주차(탑승) 상태에서만 나온다.
 */
@Composable
private fun BatonTouch(t: ItemText, key: Long, modifier: Modifier) {
    var visible by remember(key) { mutableStateOf(true) }
    val fly = remember(key) { Animatable(0f) }
    val pop = remember(key) { Animatable(0f) }
    val fade = remember(key) { Animatable(1f) }
    LaunchedEffect(key) {
        fly.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
        pop.animateTo(1f, tween(250, easing = FastOutSlowInEasing))
        kotlinx.coroutines.delay(700)
        fade.animateTo(0f, tween(400, easing = LinearEasing))
        visible = false
    }
    if (!visible) return
    Column(
        modifier.graphicsLayer { alpha = fade.value },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 바통: 노랑-흰-노랑 세 토막의 막대
        Row(
            Modifier
                .graphicsLayer {
                    translationX = (fly.value - 1f) * 900f
                    rotationZ = -18f + 18f * fly.value
                }
                .clip(RoundedCornerShape(28.dp)),
        ) {
            Box(Modifier.width(90.dp).height(56.dp).background(C.todo))
            Box(Modifier.width(90.dp).height(56.dp).background(C.text))
            Box(Modifier.width(90.dp).height(56.dp).background(C.todo))
        }
        Spacer(Modifier.height(24.dp))
        Text(
            t.str(R.string.baton_touch),
            color = C.done, fontSize = 64.sp, fontWeight = FontWeight.Black,
            modifier = Modifier
                .graphicsLayer {
                    val sc = 0.6f + 0.4f * pop.value
                    scaleX = sc; scaleY = sc; alpha = pop.value
                }
                .clip(RoundedCornerShape(24.dp))
                .background(C.bg.copy(alpha = 0.85f))
                .padding(horizontal = 36.dp, vertical = 12.dp),
        )
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
        Box(
            Modifier.heightIn(min = TOUCH).widthIn(min = TOUCH).clip(RoundedCornerShape(12.dp)).clickable { onUndo() }
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center,
        ) { Text(t.str(R.string.undo), color = Color(0xFF1F6FEB), fontSize = 24.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun TopBar(
    t: ItemText, locale: Locale, onLocale: (Locale) -> Unit, onDiagnostics: (() -> Unit)?, onHistory: (() -> Unit)?,
    /** null이면 숨김 (주행 중) */
    cheer: Boolean? = null,
    onCheer: () -> Unit = {},
    compact: Boolean = false,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(t.str(R.string.app_name), color = C.text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        // 이력은 주차 중에만 (주행 중 상세 화면 금지)
        if (onHistory != null) {
            Box(
                Modifier.padding(end = 16.dp).heightIn(min = TOUCH).widthIn(min = TOUCH)
                    .clip(RoundedCornerShape(14.dp)).background(C.card).clickable { onHistory() }
                    .padding(horizontal = 22.dp),
                contentAlignment = Alignment.Center,
            ) { Text(t.str(R.string.history_button), color = C.text, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        }
        if (cheer != null) {
            Box(
                Modifier.padding(end = 16.dp).heightIn(min = TOUCH).widthIn(min = TOUCH)
                    .clip(RoundedCornerShape(14.dp)).background(C.card).clickable { onCheer() }
                    .padding(horizontal = 22.dp),
                contentAlignment = Alignment.Center,
            ) {
                // 반 칸에서는 기호만 (켬 = 밝게, 끔 = 흐리게 + 취소선)
                Text(
                    if (compact) "♪" else t.str(if (cheer) R.string.cheer_on else R.string.cheer_off),
                    color = if (cheer) C.text else C.sub, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    textDecoration = if (compact && !cheer) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                )
            }
        }
        LanguageButton(locale, onLocale)
        if (onDiagnostics != null) {
            Box(
                Modifier.padding(start = 8.dp).heightIn(min = TOUCH).widthIn(min = TOUCH).clickable { onDiagnostics() },
                contentAlignment = Alignment.Center,
            ) { Text("⚙", color = C.sub, fontSize = 26.sp) }
        }
    }
}

/**
 * 언어: 버튼 하나를 누르면 목록이 열린다. 목록에는 각 언어를 그 언어로 적는다.
 * (예전 KO·EN·JA·ZH 네 버튼은 상태바 바로 밑이라 잘못 눌렸고, 반 칸에서는 줄이 넘쳤다)
 */
@Composable
private fun LanguageButton(locale: Locale, onLocale: (Locale) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.heightIn(min = TOUCH).widthIn(min = TOUCH)
                .clip(RoundedCornerShape(14.dp)).background(C.card).clickable { open = true }
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center,
        ) { Text("🌐 " + locale.language.uppercase(), color = C.text, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(C.card)) {
            for (l in ItemText.SUPPORTED) {
                val selected = l.language == locale.language
                DropdownMenuItem(
                    text = {
                        Text(
                            (if (selected) "✓ " else "   ") + l.getDisplayName(l).replaceFirstChar { it.uppercase(l) },
                            color = if (selected) C.done else C.text, fontSize = 24.sp,
                        )
                    },
                    onClick = { open = false; onLocale(l) },
                    modifier = Modifier.heightIn(min = TOUCH).widthIn(min = 220.dp),
                )
            }
        }
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
private fun Parked(t: ItemText, preview: List<OpenItem>, compact: Boolean) {
    Text(t.str(R.string.state_parked), color = C.text, fontSize = 40.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(20.dp))
    if (preview.isEmpty()) {
        // 아직 아무 일도 없었는데 "모두 해결됐어요"라고 하면 어색하다 → 넘길 것이 없다고만 말한다
        Text(t.str(R.string.parked_nothing), color = C.done, fontSize = 28.sp, lineHeight = 38.sp, style = WORDS)
    } else {
        Text(t.str(R.string.parked_hint), color = C.sub, fontSize = 22.sp)
        Spacer(Modifier.height(16.dp))
        ItemList(t, preview, emptyList(), preview = true, compact = compact)
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
    wide: Boolean,
    compact: Boolean,
) {
    val open = h.visible
    val resolved = h.resolved.takeLast(3).reversed().map { it.item }
    if (wide) {
        // 전체 칸: 왼쪽 요약(40%) + 오른쪽 카드(60%). 고정 폭을 쓰지 않는다
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(0.4f)) { HandoverSummary(t, title, h, ui, showEnergy, onAcknowledge, onReceive, compact, emptyInPanel = true) }
            Spacer(Modifier.width(32.dp))
            Column(Modifier.weight(0.6f)) {
                // 넘겨받을 것이 없으면 오른쪽이 비어 보이지 않게 큰 '이상 없음' 표시
                if (open.isEmpty() && resolved.isEmpty()) EmptyPanel(t)
                else ItemList(t, open, resolved, onAcknowledge, compact = compact)
            }
        }
    } else {
        // 반 칸: 요약 → 카드 순서로 한 줄에 쌓고 전체를 함께 스크롤 (카드가 좁은 기둥으로 눌리지 않게)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item(key = "summary") { Column { HandoverSummary(t, title, h, ui, showEnergy, onAcknowledge, onReceive, compact) } }
            itemCards(t, open, resolved, onAcknowledge, preview = false, compact = compact)
        }
    }
}

/** 전체 칸에서 넘겨받을 것이 없을 때 오른쪽 칸 */
@Composable
private fun EmptyPanel(t: ItemText) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 360.dp).clip(RoundedCornerShape(24.dp)).background(C.card),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Box(Modifier.size(96.dp).clip(CircleShape).background(C.done), contentAlignment = Alignment.Center) {
                Text("✓", color = C.bg, fontSize = 52.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(24.dp))
            Text(t.str(R.string.empty_handover), color = C.text, fontSize = 30.sp, lineHeight = 42.sp, style = WORDS,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

/** 제목, 에너지, 수령 버튼, 안내 문구 */
@Composable
private fun HandoverSummary(
    t: ItemText,
    title: String,
    h: com.baton.app.handover.HandoverMachine.Handover,
    ui: UiState,
    showEnergy: Boolean,
    onAcknowledge: ((OpenItem) -> Unit)?,
    onReceive: (() -> Unit)?,
    compact: Boolean,
    /** 전체 칸에서는 '넘겨받을 것 없음'을 오른쪽 큰 칸(EmptyPanel)에만 보인다 */
    emptyInPanel: Boolean = false,
) {
    val open = h.visible
    Text(title, color = C.text, fontSize = if (compact) 32.sp else 40.sp, fontWeight = FontWeight.Bold, lineHeight = if (compact) 40.sp else 50.sp)
    Spacer(Modifier.height(16.dp))
    val socPark = h.socAtPark
    val socNow = ui.snapshot.batterySoc
    if (showEnergy && socPark != null && socNow != null) {
        Text(t.str(R.string.energy_line, socPark, socNow), color = C.sub, fontSize = 24.sp, lineHeight = 32.sp)
        // 주차 중 변화 = 탑승 시점 잔량 - 주차 시점 잔량
        val delta = (h.socAtBoard ?: socNow) - socPark
        Text(
            when {
                delta >= 2 -> t.str(R.string.energy_charged, delta)
                delta <= -2 -> t.str(R.string.energy_used, -delta)
                else -> t.str(R.string.energy_same)
            },
            color = if (delta >= 2) C.done else C.sub, fontSize = 22.sp, lineHeight = 30.sp,
        )
    }
    if (onReceive != null) {
        Spacer(Modifier.height(24.dp))
        val at = h.receivedAtMs
        if (at == null) {
            Box(
                Modifier.heightIn(min = TOUCH).widthIn(min = 200.dp)
                    .clip(RoundedCornerShape(18.dp)).background(C.done).clickable { onReceive() }
                    .padding(horizontal = 40.dp, vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) { Text(t.str(R.string.receive_button), color = C.bg, fontSize = 28.sp, fontWeight = FontWeight.Bold) }
            // 탑승 안내 직후 몇 초만 듣는다 — 마이크가 열려 있다는 것을 항상 화면에 보인다
            if (ui.listening) {
                Spacer(Modifier.height(12.dp))
                Text(t.str(R.string.listening_hint), color = C.done, fontSize = 22.sp, lineHeight = 30.sp)
            }
        } else {
            val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, t.locale).format(java.util.Date(at))
            Text("✓ " + t.str(R.string.received_at, time), color = C.done, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        }
    }
    Spacer(Modifier.height(24.dp))
    when {
        open.isEmpty() && h.resolved.isEmpty() -> if (!emptyInPanel) Text(t.str(R.string.empty_handover), color = C.done, fontSize = 28.sp, lineHeight = 38.sp, style = WORDS)
        open.isEmpty() -> Text(t.str(R.string.all_clear), color = C.done, fontSize = 28.sp, lineHeight = 38.sp, style = WORDS)
        onAcknowledge != null -> Text(t.str(R.string.hint_tap_to_ack), color = C.sub, fontSize = 20.sp, lineHeight = 28.sp, style = WORDS)
    }
}

@Composable
private fun ItemList(t: ItemText, open: List<OpenItem>, resolved: List<OpenItem>, onTap: ((OpenItem) -> Unit)? = null, preview: Boolean = false, compact: Boolean = false) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        itemCards(t, open, resolved, onTap, preview, compact)
    }
}

private fun LazyListScope.itemCards(t: ItemText, open: List<OpenItem>, resolved: List<OpenItem>, onTap: ((OpenItem) -> Unit)?, preview: Boolean, compact: Boolean) {
    // 같은 키가 두 번 들어오면 Compose가 종료되므로 화면에서도 한 번 더 중복 제거 (이중 방어)
    items(open.distinctBy { it.key }, key = { "o" + it.key }) { ItemCard(t, it, done = false, onTap = onTap, preview = preview, compact = compact) }
    items(resolved.distinctBy { it.key }, key = { "r" + it.key }) { ItemCard(t, it, done = true, onTap = null, compact = compact) }
}

@Composable
private fun ItemCard(t: ItemText, item: OpenItem, done: Boolean, onTap: ((OpenItem) -> Unit)?, preview: Boolean = false, compact: Boolean = false) {
    val accent = when {
        done -> C.done
        item.tier == OpenItem.Tier.SAFETY -> C.safety
        item.tier == OpenItem.Tier.TODO -> C.todo
        else -> C.info
    }
    // 색 + 기호 + 문구 3중 표시 (색각 이상 사용자 고려)
    val symbol = when {
        done -> "✓"
        item.tier == OpenItem.Tier.SAFETY -> "!"
        else -> "•"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(C.card)
            .border(2.dp, accent.copy(alpha = if (done) 0.6f else 0.35f), RoundedCornerShape(20.dp))
            // 안전 항목은 확인 처리로 숨길 수 없으므로 누를 수 없다 (신호로 해결될 때까지 표시)
            .then(if (onTap != null && HandoverMachine.canAcknowledge(item)) Modifier.clickable { onTap(item) } else Modifier)
            .padding(horizontal = if (compact) 20.dp else 28.dp, vertical = if (compact) 18.dp else 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(accent),
            contentAlignment = Alignment.Center,
        ) { Text(symbol, color = C.bg, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(if (compact) 16.dp else 24.dp))
        Column(Modifier.weight(1f)) {
            Text(
                when { done -> t.done(item); preview -> t.preview(item); else -> t.item(item) },
                color = if (done) C.done else C.text, fontSize = if (compact) 26.sp else 30.sp, lineHeight = if (compact) 36.sp else 40.sp, fontWeight = FontWeight.SemiBold, style = WORDS,
            )
        }
        Spacer(Modifier.width(12.dp))
        // 등급 꼬리표는 줄바꿈하지 않는다 (좁은 칸에서 '안/전'처럼 세로로 쪼개지지 않게)
        Text(
            if (done) t.str(R.string.resolved) else t.tier(item.tier),
            color = accent, fontSize = 22.sp, fontWeight = FontWeight.Bold, softWrap = false, maxLines = 1,
        )
    }
}
