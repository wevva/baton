package com.baton.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baton.app.R
import com.baton.app.handover.HandoverMachine.Handover
import com.baton.app.i18n.ItemText
import com.baton.app.vehicle.Spot
import java.text.DateFormat
import java.util.Date

/** 인계 이력 (구독 '플러스' 등급의 핵심 화면). 주차 중에만 연다. */
@Composable
fun HistoryScreen(history: List<Handover>, driverSeat: Spot, onBack: () -> Unit) {
    val config = LocalConfiguration.current
    val ctx = LocalContext.current
    val t = remember(config, driverSeat) { ItemText(ctx, config.locales[0], driverSeat) }
    val dt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, t.locale)
    val time = DateFormat.getTimeInstance(DateFormat.SHORT, t.locale)
    Column(
        Modifier.fillMaxSize().background(Color(0xFF0E1116)).padding(horizontal = 48.dp, vertical = 32.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t.str(R.string.history_title), color = Color(0xFFF2F4F7), fontSize = 36.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            // 차량 화면 최소 터치 영역 76dp
            Box(
                Modifier.heightIn(min = 76.dp).widthIn(min = 76.dp).clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFFF2F4F7)).clickable { onBack() }.padding(horizontal = 32.dp),
                contentAlignment = Alignment.Center,
            ) { Text(t.str(R.string.back), color = Color(0xFF0E1116), fontSize = 24.sp, fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.height(24.dp))
        if (history.isEmpty()) {
            Text(t.str(R.string.history_empty), color = Color(0xFF9AA4B2), fontSize = 26.sp)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(history) { h ->
                val left = h.open.size + h.resolved.size
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFF1A1F27))
                        .padding(horizontal = 28.dp, vertical = 20.dp)
                ) {
                    Text(
                        t.str(R.string.history_row, dt.format(Date(h.parkedAtMs)), left, h.resolved.size),
                        color = Color(0xFFF2F4F7), fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
                    )
                    val received = h.receivedAtMs
                    val soc = if (h.socAtPark != null && h.socAtBoard != null) " · " + t.str(R.string.history_energy, h.socAtPark, h.socAtBoard) else ""
                    Text(
                        (if (received != null) t.str(R.string.history_received, time.format(Date(received))) else t.str(R.string.history_not_received)) + soc,
                        color = if (received != null) Color(0xFF4CC38A) else Color(0xFF9AA4B2), fontSize = 20.sp,
                    )
                    if (h.open.isNotEmpty()) {
                        Text(h.open.joinToString(" · ") { t.item(it) }, color = Color(0xFFF2B84B), fontSize = 18.sp)
                    }
                }
            }
        }
    }
}
