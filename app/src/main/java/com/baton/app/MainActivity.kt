package com.baton.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baton.app.handover.HandoverMachine
import com.baton.app.i18n.AppLanguage
import com.baton.app.security.BatonLog
import com.baton.app.ui.HandoverScreen
import com.baton.app.ui.HistoryScreen

class MainActivity : ComponentActivity() {

    private lateinit var controller: BatonController

    // 진단 화면(개발용)은 디버그 빌드에서, 열 때만 만든다 (차량 연결·음성 엔진을 쓸데없이 늘리지 않음)
    private val signals = mutableStateMapOf<String, String>()
    private var probe: SignalProbe? = null
    private var tts: TtsProbe? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = (application as BatonApp).controller
        controller.setLocale(AppLanguage.current(this))
        // 화면을 닫아도 주차·하차·탑승을 계속 감지하도록 감시 서비스 시작
        HandoverService.start(this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val ui by controller.ui.collectAsState()
                var diagnostics by remember { mutableStateOf(false) }
                var history by remember { mutableStateOf(false) }
                // 주행 중이면 상세 화면(이력·진단)을 닫는다
                if (ui.state is HandoverMachine.State.Driving) {
                    history = false
                    diagnostics = false
                }
                when {
                    history -> HistoryScreen(ui.history, ui.profile.driverSeat, onBack = { history = false })
                    diagnostics -> ProbeScreen(
                        signals = signals,
                        onReadAll = { diagnosticsProbe().readAll() },
                        onSubscribe = { diagnosticsProbe().subscribe() },
                        onSpeak = { diagnosticsTts().speakSample() },
                        onBack = { diagnostics = false },
                    )
                    else -> HandoverScreen(
                        ui,
                        // 실제 반영은 onConfigurationChanged에서 (언어 변경은 비동기로 적용됨)
                        onLocale = { l -> AppLanguage.set(this, l) },
                        onDiagnostics = if (BatonLog.enabled) ({ diagnostics = true }) else null,
                        onAcknowledge = controller::acknowledge,
                        onUndo = controller::undo,
                        onReceive = controller::receive,
                        onHistory = { history = true },
                        onCheer = controller::toggleCheer,
                    )
                }
            }
        }
    }

    private fun diagnosticsProbe(): SignalProbe = probe ?: SignalProbe(this) { name, value ->
        runOnUiThread { signals[name] = value }
    }.also { it.start(); probe = it }

    private fun diagnosticsTts(): TtsProbe = tts ?: TtsProbe(this) { name, value ->
        runOnUiThread { signals[name] = value }
    }.also { tts = it }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        controller.setLocale(newConfig.locales[0])
    }

    override fun onDestroy() {
        tts?.release()
        probe?.stop()
        super.onDestroy()
    }
}

/** 개발용 진단 화면 (디버그 빌드에서만 진입 가능) */
@Composable
private fun ProbeScreen(signals: Map<String, String>, onReadAll: () -> Unit, onSubscribe: () -> Unit, onSpeak: () -> Unit, onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF101418))
            .padding(32.dp)
    ) {
        Text("진단 · 차량 신호 확인", fontSize = 32.sp, color = Color.White)
        Spacer(Modifier.padding(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = onReadAll) { Text("전체 읽기", fontSize = 22.sp) }
            Button(onClick = onSubscribe) { Text("실시간 구독", fontSize = 22.sp) }
            Button(onClick = onSpeak) { Text("음성 테스트", fontSize = 22.sp) }
            Button(onClick = onBack) { Text("닫기", fontSize = 22.sp) }
        }
        Spacer(Modifier.padding(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(signals.entries.sortedBy { it.key }.toList(), key = { it.key }) { (name, value) ->
                Row(Modifier.fillMaxWidth()) {
                    Text(name, fontSize = 20.sp, color = Color(0xFF9FB3C8), modifier = Modifier.width(320.dp))
                    Text(
                        value,
                        fontSize = 20.sp,
                        color = if (value.startsWith("실패")) Color(0xFFFF8A80) else Color.White,
                    )
                }
            }
        }
    }
}
