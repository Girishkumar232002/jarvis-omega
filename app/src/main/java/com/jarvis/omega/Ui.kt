package com.jarvis.omega

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF38BDF8),
            onPrimary = Color(0xFF00131F),
            secondary = Color(0xFFF59E0B),
            background = Color(0xFF0B1220),
            surface = Color(0xFF111A2E),
            onBackground = Color(0xFFE6EDF7),
            onSurface = Color(0xFFE6EDF7),
            surfaceVariant = Color(0xFF1A2540),
            onSurfaceVariant = Color(0xFFB6C2D9),
        ),
        content = content,
    )
}

@Composable
fun App(vm: JarvisVM, withMic: (() -> Unit) -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Chat" to "💬", "Notes" to "📝", "Reminders" to "⏰", "Settings" to "⚙️")
    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, (label, emoji) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Text(emoji) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> ChatScreen(vm, withMic)
                1 -> NotesScreen(vm)
                2 -> RemindersScreen(vm)
                else -> SettingsScreen(vm)
            }
        }
    }
}

@Composable
fun ChatScreen(vm: JarvisVM, withMic: (() -> Unit) -> Unit) {
    val ctx = LocalContext.current
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val lastLen = vm.messages.lastOrNull()?.text?.length ?: 0

    LaunchedEffect(vm.messages.size, lastLen) {
        if (vm.messages.isNotEmpty()) listState.animateScrollToItem(vm.messages.lastIndex)
    }

    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "JARVIS",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                val i = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, vm.exportText())
                }
                ctx.startActivity(Intent.createChooser(i, "Export chat"))
            }) { Text("Export") }
            TextButton(onClick = { vm.clearChat() }) { Text("Clear") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = vm.handsFree,
                onCheckedChange = { on ->
                    if (on) withMic { vm.setHandsFree(true) } else vm.setHandsFree(false)
                },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Hands-free: say “${vm.settings.wakeWord}” (only while the app is open)",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (vm.messages.isEmpty()) {
                item {
                    Text(
                        "Hi, I'm JARVIS. To give me a brain with no API key, open Settings and download an on-device model once. Reminders and notes already work offline.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
            }
            items(vm.messages) { m -> Bubble(m) }
        }
        val line = when {
            vm.partial.isNotEmpty() -> "🎤 ${vm.partial}"
            vm.listening -> "🎤 ${vm.status.ifEmpty { "Listening…" }}"
            else -> vm.status
        }
        if (line.isNotEmpty()) {
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                color = if (vm.listening) MaterialTheme.colorScheme.secondary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message JARVIS") },
                maxLines = 4,
            )
            Spacer(Modifier.width(6.dp))
            FilledTonalButton(
                onClick = { withMic { vm.micTap() } },
                contentPadding = PaddingValues(12.dp),
            ) { Text(if (vm.listening) "⏹" else "🎤") }
            Spacer(Modifier.width(6.dp))
            Button(onClick = {
                if (vm.busy) {
                    vm.stopGeneration()
                } else {
                    vm.send(input)
                    input = ""
                }
            }) { Text(if (vm.busy) "Stop" else "Send") }
        }
    }
}

@Composable
fun Bubble(m: ChatMsg) {
    val mine = m.role == "user"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    if (m.text.isEmpty()) "…" else m.text,
                    color = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                )
                if (m.provider.isNotEmpty()) {
                    Text(
                        "via ${m.provider}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun NotesScreen(vm: JarvisVM) {
    var input by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Notes", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        Text(
            "Tip: in chat, say or type “note: buy milk”.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("New note") },
            )
            Spacer(Modifier.width(6.dp))
            Button(onClick = {
                vm.addNote(input)
                input = ""
            }) { Text("Add") }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(vm.notes) { n ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(n.text, Modifier.weight(1f))
                    TextButton(onClick = { vm.deleteNote(n) }) { Text("Delete") }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun RemindersScreen(vm: JarvisVM) {
    var text by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("30") }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Reminders", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        Text(
            "Tip: in chat, say or type “remind me in 10 minutes to call mom”.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            placeholder = { Text("Remind me to…") },
        )
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = minutes,
                onValueChange = { minutes = it.filter { c -> c.isDigit() }.take(5) },
                modifier = Modifier.width(120.dp),
                label = { Text("Minutes") },
                singleLine = true,
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                vm.addReminder(text, minutes.toLongOrNull() ?: 0L)
                text = ""
            }) { Text("Add reminder") }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(vm.reminders.sortedBy { it.atMillis }) { r ->
                val past = r.atMillis < System.currentTimeMillis()
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.text)
                        Text(
                            fmt.format(Date(r.atMillis)) + if (past) " · done" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { vm.deleteReminder(r) }) { Text("Delete") }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    secret: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = !label.startsWith("System"),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
fun ModelCard(vm: JarvisVM, m: LocalModel) {
    val st = vm.modelStates[m.id] ?: ModelState.Missing
    val selected = vm.settings.localModel == m.id
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = { vm.useModel(m) })
                Column(Modifier.weight(1f)) {
                    Text(m.name, fontWeight = FontWeight.Bold)
                    Text(
                        "${m.size} · ${m.note}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                val label = when (st) {
                    is ModelState.Installed -> "Installed ✓"
                    is ModelState.Downloading -> "Downloading… ${st.percent}%"
                    is ModelState.Failed -> "Download failed (${st.reason})"
                    else -> "Not downloaded"
                }
                Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                when (st) {
                    is ModelState.Installed -> TextButton(onClick = { vm.deleteModel(m) }) { Text("Delete") }
                    is ModelState.Downloading -> TextButton(onClick = { vm.deleteModel(m) }) { Text("Cancel") }
                    else -> Button(onClick = { vm.downloadModel(m) }) { Text("Download") }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(vm: JarvisVM) {
    var s by remember { mutableStateOf(vm.settings) }
    LaunchedEffect(Unit) { vm.refreshModels() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)

        Text("On-device AI (free, offline, no key)", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        Text(
            "Download one model once over Wi-Fi. After that JARVIS thinks on your phone, even with no internet. The first reply after opening the app takes a few seconds while the model loads.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        LocalModels.all.forEach { m -> ModelCard(vm, m) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = s.useGpu, onCheckedChange = { s = s.copy(useGpu = it) })
            Spacer(Modifier.width(8.dp))
            Text("Use GPU (faster on many phones; turn off if replies fail)", style = MaterialTheme.typography.bodySmall)
        }

        Text("Which brain to use", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
        listOf(
            "auto" to "Auto (phone first, then PC, then cloud)",
            "local" to "On-device only",
            "ollama" to "My PC (Ollama) only",
            "anthropic" to "Claude only (needs key)",
            "openai" to "OpenAI only (needs key)",
            "gemini" to "Gemini only (needs key)",
        ).forEach { (id, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = s.mode == id, onClick = { s = s.copy(mode = id) })
                Text(label)
            }
        }

        Text("My PC (optional, no key)", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
        Text(
            "If Ollama runs on your PC, enter the PC's Wi-Fi address, e.g. 192.168.1.5. On the PC, set OLLAMA_HOST=0.0.0.0 so the phone can reach it. Phone and PC must be on the same Wi-Fi.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Field("PC address", s.ollamaUrl) { s = s.copy(ollamaUrl = it.trim()) }
        Field("Ollama model name", s.ollamaModel) { s = s.copy(ollamaModel = it.trim()) }

        Text("Cloud (optional, needs keys)", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
        Text(
            "Not needed. Only fill these if you want a cloud AI as an extra. Keys stay in this app's private storage.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Field("Anthropic (Claude) API key", s.anthropicKey, secret = true) { s = s.copy(anthropicKey = it.trim()) }
        Field("Claude model", s.anthropicModel) { s = s.copy(anthropicModel = it.trim()) }
        Field("OpenAI API key", s.openaiKey, secret = true) { s = s.copy(openaiKey = it.trim()) }
        Field("OpenAI model", s.openaiModel) { s = s.copy(openaiModel = it.trim()) }
        Field("Gemini API key", s.geminiKey, secret = true) { s = s.copy(geminiKey = it.trim()) }
        Field("Gemini model", s.geminiModel) { s = s.copy(geminiModel = it.trim()) }

        Text("Voice", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = s.speak, onCheckedChange = { s = s.copy(speak = it) })
            Spacer(Modifier.width(8.dp))
            Text("Speak replies aloud")
        }
        Text("Speaking speed: ${"%.1f".format(s.speechRate)}×")
        Slider(
            value = s.speechRate,
            onValueChange = { s = s.copy(speechRate = it) },
            valueRange = 0.5f..2f,
        )
        Field("Wake word", s.wakeWord) { s = s.copy(wakeWord = it.trim()) }

        Text("Personality", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
        Field("System prompt", s.systemPrompt) { s = s.copy(systemPrompt = it) }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { vm.saveSettings(s.copy(localModel = vm.settings.localModel)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save settings") }
        if (vm.status == "Settings saved") {
            Text("Saved ✓", color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}
