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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {

    private val signals = mutableStateMapOf<String, String>()
    private lateinit var probe: SignalProbe
    private lateinit var tts: TtsProbe

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        probe = SignalProbe(this) { name, value -> runOnUiThread { signals[name] = value } }
        probe.start()
        tts = TtsProbe(this) { name, value -> runOnUiThread { signals[name] = value } }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ProbeScreen(
                    signals = signals,
                    onReadAll = { probe.readAll() },
                    onSubscribe = { probe.subscribe() },
                    onSpeak = { tts.speakSample() },
                )
            }
        }
    }

    override fun onDestroy() {
        tts.release()
        probe.stop()
        super.onDestroy()
    }
}

@Composable
private fun ProbeScreen(signals: Map<String, String>, onReadAll: () -> Unit, onSubscribe: () -> Unit, onSpeak: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF101418))
            .padding(32.dp)
    ) {
        Text("바통 · 차량 신호 확인", fontSize = 32.sp, color = Color.White)
        Spacer(Modifier.padding(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = onReadAll) { Text("전체 읽기", fontSize = 22.sp) }
            Button(onClick = onSubscribe) { Text("실시간 구독", fontSize = 22.sp) }
            Button(onClick = onSpeak) { Text("음성 테스트", fontSize = 22.sp) }
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
