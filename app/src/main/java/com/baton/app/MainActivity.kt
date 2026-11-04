package com.baton.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.baton.app.i18n.AppLanguage
import com.baton.app.ui.HandoverScreen
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {

    private val signals = mutableStateMapOf<String, String>()
    private lateinit var probe: SignalProbe
    private lateinit var tts: TtsProbe
    private lateinit var controller: BatonController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as BatonApp
        // 진단 화면용 신호 확인기는 화면마다 새로 (차량 연결은 앱이 유지)
        probe = SignalProbe(this) { name, value -> runOnUiThread { signals[name] = value } }
        probe.start()
        tts = TtsProbe(this) { name, value -> runOnUiThread { signals[name] = value } }
        controller = app.controller
        controller.setLocale(AppLanguage.current(this))

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val ui by controller.ui.collectAsState()
                var diagnostics by remember { mutableStateOf(false) }
                if (diagnostics) {
                    ProbeScreen(
                        signals = signals,
                        onReadAll = { probe.readAll() },
                        onSubscribe = { probe.subscribe() },
                        onSpeak = { tts.speakSample() },
                        onBack = { diagnostics = false },
                    )
                } else {
                    HandoverScreen(
                        ui,
                        // 실제 반영은 onConfigurationChanged에서 (언어 변경은 비동기로 적용됨)
                        onLocale = { l -> AppLanguage.set(this, l) },
                        onDiagnostics = { diagnostics = true },
                    )
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        controller.setLocale(newConfig.locales[0])
    }

    override fun onDestroy() {
        tts.release()
        probe.stop()
        super.onDestroy()
    }
}

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
