package com.awais.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

val BgDark = Color(0xFF05070F)
val PanelDark = Color(0xFF0E1424)
val NeonCyan = Color(0xFF00E5FF)
val NeonPurple = Color(0xFF7C4DFF)

object Ui {
    val tick = mutableIntStateOf(0)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Assistant.load(this)
        Speaker.init(this)
        setContent { AwaisTheme { AwaisApp() } }
    }

    override fun onResume() {
        super.onResume()
        Ui.tick.intValue++
    }
}

@Composable
fun AwaisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = NeonCyan, secondary = NeonPurple, background = BgDark,
            surface = PanelDark, onPrimary = Color.Black
        ),
        content = content
    )
}

fun triggerVoice(ctx: Context, scope: CoroutineScope) {
    if (!Prefs.micEnabled(ctx)) return
    if (WakeWordService.running) {
        ctx.startService(Intent(ctx, WakeWordService::class.java).setAction(WakeWordService.ACTION_MANUAL))
        return
    }
    if (VoiceEngine.state.value != "idle") return
    VoiceEngine.converse(ctx, scope) {}
}

@Composable
fun AwaisApp() {
    var tab by remember { mutableIntStateOf(0) }
    val labels = listOf("Home", "Chat", "Settings")
    val icons = listOf("🎙", "💬", "⚙")
    Scaffold(
        bottomBar = {
            NavigationBar {
                labels.forEachIndexed { i, l ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { Text(icons[i]) }, label = { Text(l) }
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> HomeScreen()
                1 -> ChatScreen()
                else -> SettingsScreen()
            }
        }
    }
}

// ------------------------------------------------------------------ Home

@Composable
fun HomeScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by VoiceEngine.state
    val tick = Ui.tick.intValue
    var wake by remember { mutableStateOf(WakeWordService.running) }
    var micOn by remember { mutableStateOf(Prefs.micEnabled(ctx)) }
    LaunchedEffect(tick) { wake = WakeWordService.running }

    val needed = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        Ui.tick.intValue++
    }
    val micGranted = hasPerm(ctx, Manifest.permission.RECORD_AUDIO)
    val last = Assistant.history.lastOrNull { !it.fromUser }
    val active = state != "idle"

    val inf = rememberInfiniteTransition(label = "pulse")
    val pulse by inf.animateFloat(
        initialValue = 0.7f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "p"
    )

    Column(
        Modifier.fillMaxSize().background(BgDark).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(12.dp))
        Text("AWAIS AI", color = NeonCyan, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 6.sp)
        Text("Personal Voice Assistant", color = Color.Gray, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))

        Box(
            Modifier.size(230.dp).clip(CircleShape).clickable {
                if (!micGranted) launcher.launch(needed) else triggerVoice(ctx, scope)
            },
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2
                if (active) {
                    drawCircle(NeonCyan.copy(alpha = 0.12f), r * pulse)
                    drawCircle(NeonPurple.copy(alpha = 0.25f), r * 0.8f * pulse)
                }
                drawCircle(Brush.radialGradient(listOf(NeonPurple, NeonCyan)), r * 0.5f)
            }
            Text("🎙", fontSize = 46.sp)
        }

        Spacer(Modifier.height(14.dp))
        val status = when {
            !micOn -> "Microphone band hai"
            !micGranted -> "Mic permission chahiye (mic dabayein)"
            state == "listening" -> "Sun raha hoon..."
            state == "thinking" -> "Soch raha hoon..."
            state == "speaking" -> "Bol raha hoon..."
            wake -> "Wake-word on: \"Awais\" kahein"
            else -> "Mic dabayein aur bolein"
        }
        Text(status, color = NeonCyan, fontSize = 16.sp)
        Spacer(Modifier.weight(1f))

        if (last != null) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = PanelDark)
            ) { Text(last.text, Modifier.padding(14.dp), color = Color.White) }
            Spacer(Modifier.height(10.dp))
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Microphone listening", Modifier.weight(1f))
            Switch(checked = micOn, onCheckedChange = {
                micOn = it
                Prefs.setMicEnabled(ctx, it)
                if (!it) {
                    ctx.stopService(Intent(ctx, WakeWordService::class.java))
                    Prefs.setWake(ctx, false)
                    wake = false
                }
            })
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Background wake-word (\"Awais\")", Modifier.weight(1f))
            Switch(checked = wake, enabled = micOn, onCheckedChange = { on ->
                if (on) {
                    if (!micGranted) {
                        launcher.launch(needed)
                    } else {
                        Prefs.setWake(ctx, true)
                        WakeWordService.start(ctx)
                        wake = true
                    }
                } else {
                    ctx.stopService(Intent(ctx, WakeWordService::class.java))
                    Prefs.setWake(ctx, false)
                    wake = false
                }
            })
        }
    }
}

// ------------------------------------------------------------------ Chat

@Composable
fun ChatScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val size = Assistant.history.size
    LaunchedEffect(size) { if (size > 0) listState.animateScrollToItem(size - 1) }

    Column(Modifier.fillMaxSize().background(BgDark).padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Conversation", Modifier.weight(1f), color = NeonCyan, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = { Assistant.clear(ctx) }) { Text("Clear") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Assistant.history) { m ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start
                ) {
                    Surface(
                        color = if (m.fromUser) NeonPurple else PanelDark,
                        shape = RoundedCornerShape(16.dp)
                    ) { Text(m.text, Modifier.padding(12.dp), color = Color.White) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                modifier = Modifier.weight(1f), placeholder = { Text("Command ya sawal likhein") }
            )
            Spacer(Modifier.size(8.dp))
            Button(onClick = {
                val t = input.trim()
                if (t.isNotEmpty()) {
                    input = ""
                    scope.launch {
                        val r = Assistant.handle(ctx, t)
                        Speaker.speak(r.text)
                    }
                }
            }) { Text("Send") }
        }
    }
}

// ------------------------------------------------------------------ Settings

@Composable
fun PermRow(label: String, ok: Boolean, onGrant: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text((if (ok) "✅ " else "❌ ") + label, Modifier.weight(1f))
        if (!ok) OutlinedButton(onClick = onGrant) { Text("Allow") }
    }
}

@Composable
fun SectionTitle(t: String) {
    Spacer(Modifier.height(18.dp))
    Text(t, color = NeonCyan, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
}

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val tick = Ui.tick.intValue
    var key by remember { mutableStateOf(Prefs.apiKey(ctx)) }
    var model by remember { mutableStateOf(Prefs.model(ctx)) }
    var lang by remember { mutableStateOf(Prefs.lang(ctx)) }
    var deep by remember { mutableStateOf(Prefs.deep(ctx)) }
    var sel by remember { mutableStateOf(Prefs.voice(ctx)) }
    var words by remember { mutableStateOf(Prefs.wakeWords(ctx)) }
    var vtick by remember { mutableIntStateOf(0) }
    var saved by remember { mutableStateOf("") }
    val voices = remember(vtick) { Speaker.voices() }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        Ui.tick.intValue++
    }
    fun ask(vararg p: String) = launcher.launch(arrayOf(*p))

    Column(Modifier.fillMaxSize().background(BgDark).verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Settings", color = NeonCyan, fontSize = 24.sp, fontWeight = FontWeight.Bold)

        SectionTitle("AI (Gemini)")
        Text("Free key: aistudio.google.com/apikey. Key sirf is phone mein encrypted save hoti hai.", fontSize = 12.sp, color = Color.Gray)
        OutlinedTextField(
            value = key, onValueChange = { key = it }, label = { Text("Gemini API key") },
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = model, onValueChange = { model = it }, label = { Text("Model name") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = {
            Prefs.setApiKey(ctx, key); Prefs.setModel(ctx, model); saved = "Save ho gaya"
        }) { Text("Save") }
        if (saved.isNotEmpty()) Text(saved, color = NeonCyan, fontSize = 12.sp)

        SectionTitle("Language (speech recognition)")
        listOf(
            "en-IN" to "Roman Urdu / Hinglish (en-IN)",
            "ur-PK" to "Urdu (ur-PK)",
            "en-US" to "English (en-US)"
        ).forEach { (code, name) ->
            Row(Modifier.fillMaxWidth().clickable { lang = code; Prefs.setLang(ctx, code) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = lang == code, onClick = { lang = code; Prefs.setLang(ctx, code) })
                Text(name)
            }
        }

        SectionTitle("Voice")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Deep (male-like) pitch", Modifier.weight(1f))
            Switch(checked = deep, onCheckedChange = { deep = it; Prefs.setDeep(ctx, it) })
        }
        Row {
            OutlinedButton(onClick = { vtick++ }) { Text("Refresh voices") }
            Spacer(Modifier.size(8.dp))
            OutlinedButton(onClick = { Speaker.speak("Salam Awais, main AWAIS hoon.") }) { Text("Test") }
        }
        Text(
            "Voice list mein male/female ka label nahi hota. Ek ek voice chun kar sunein aur male wali select karein.",
            fontSize = 12.sp, color = Color.Gray
        )
        Row(Modifier.fillMaxWidth().clickable { sel = ""; Prefs.setVoice(ctx, "") }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = sel == "", onClick = { sel = ""; Prefs.setVoice(ctx, "") })
            Text("Default")
        }
        voices.take(40).forEach { v ->
            val pick = {
                sel = v.name; Prefs.setVoice(ctx, v.name)
                Speaker.speak("Salam Awais, main AWAIS hoon.")
            }
            Row(Modifier.fillMaxWidth().clickable { pick() }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = sel == v.name, onClick = { pick() })
                Text("${v.name} (${v.locale})", fontSize = 13.sp)
            }
        }

        SectionTitle("Wake words")
        Text("Comma se alag karein. Agar \"Awais\" detect na ho to is mein milti-julti awazein add karein.", fontSize = 12.sp, color = Color.Gray)
        OutlinedTextField(value = words, onValueChange = { words = it }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { Prefs.setWakeWords(ctx, words); saved = "Wake words save ho gaye (service restart karein)" }) { Text("Save") }

        SectionTitle("Permissions")
        PermRow("Microphone", hasPerm(ctx, Manifest.permission.RECORD_AUDIO)) { ask(Manifest.permission.RECORD_AUDIO) }
        if (Build.VERSION.SDK_INT >= 33) {
            PermRow("Notifications", hasPerm(ctx, Manifest.permission.POST_NOTIFICATIONS)) { ask(Manifest.permission.POST_NOTIFICATIONS) }
        }
        PermRow("Contacts", hasPerm(ctx, Manifest.permission.READ_CONTACTS)) { ask(Manifest.permission.READ_CONTACTS) }
        PermRow("Phone calls", hasPerm(ctx, Manifest.permission.CALL_PHONE)) { ask(Manifest.permission.CALL_PHONE) }
        PermRow("Send SMS", hasPerm(ctx, Manifest.permission.SEND_SMS)) { ask(Manifest.permission.SEND_SMS) }
        PermRow("Display over other apps (background mein apps kholne ke liye zaroori)", Settings.canDrawOverlays(ctx)) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
        }
        OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) {
            Text("Battery optimization band karein")
        }
        Spacer(Modifier.height(30.dp))
    }
}
