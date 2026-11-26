package com.gravity.ide.ui.screens

import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.gravity.ide.core.EmotionClassifier
import com.gravity.ide.core.Expressions
import com.gravity.ide.core.VoiceStyles
import com.gravity.ide.files.VoicePicker
import com.gravity.ide.files.VoicePrefs
import com.gravity.ide.ui.theme.GravityColors

/**
 * Settings → Voice & speech: Yamini's voice (female or male: also its Hindi grammar), feelings in
 * the voice (Normal, Expressive, Professional; each mood with what it really changes), speaking
 * styles, the phone's voices for Hindi and English, speed and pitch, each with a ▶ to hear it.
 * Used by live mode, the speaker button and audio files. The voices are the phone's own
 * text-to-speech voices (Google's on most phones); an offline voice pack is optional.
 */
@Composable
internal fun VoiceSettingsPanel() {
    val context = LocalContext.current
    var ready by remember { mutableStateOf<Boolean?>(null) }
    val tts = remember { TextToSpeech(context.applicationContext) { status -> ready = status == TextToSpeech.SUCCESS } }
    DisposableEffect(Unit) { onDispose { runCatching { tts.stop(); tts.shutdown() } } }

    var styleId by remember { mutableStateOf(VoicePrefs.style(context).id) }
    var hindiTab by remember { mutableStateOf(true) }
    var gender by remember { mutableStateOf("all") }
    var rate by remember { mutableStateOf(VoicePrefs.rate(context)) }
    var pitch by remember { mutableStateOf(VoicePrefs.pitch(context)) }
    var hindiVoice by remember { mutableStateOf(VoicePrefs.voiceName(context, true)) }
    var englishVoice by remember { mutableStateOf(VoicePrefs.voiceName(context, false)) }
    var accent by remember { mutableStateOf(VoicePrefs.accent(context)) }
    var address by remember { mutableStateOf(VoicePrefs.address(context)) }
    var greet by remember { mutableStateOf(VoicePrefs.greet(context)) }
    var natural by remember { mutableStateOf(VoicePrefs.natural(context)) }
    var mode by remember { mutableStateOf(VoicePrefs.mode(context)) }
    var breath by remember { mutableStateOf(VoicePrefs.breath(context)) }
    var filler by remember { mutableStateOf(VoicePrefs.filler(context)) }
    var fixedMood by remember { mutableStateOf(VoicePrefs.fixedMood(context)) }
    var yaminiGender by remember { mutableStateOf(VoicePrefs.gender(context)) }
    var showMoods by remember { mutableStateOf(false) }
    // Yamini's natural voice (offline): its own engine, for previews of its voices.
    var neuralReady by remember { mutableStateOf<Boolean?>(null) }
    val neuralTts = remember { VoicePrefs.create(context, context.packageName) { status -> neuralReady = status == TextToSpeech.SUCCESS } }
    DisposableEffect(Unit) { onDispose { runCatching { neuralTts.stop(); neuralTts.shutdown() } } }
    val voices = remember(ready, hindiTab, accent) { if (ready == true) VoicePicker.voices(tts, VoicePrefs.locale(context, hindiTab)) else emptyList() }
    val shown = voices.filter { gender == "all" || VoicePicker.gender(it) == gender }
    val chosen = if (hindiTab) hindiVoice else englishVoice

    fun preview(style: VoiceStyles.Style, hindi: Boolean, voice: Voice? = null, mood: EmotionClassifier.Emotion? = null) {
        // The offline voice when it is in use for this language (and no phone voice was tapped).
        val useNatural = voice == null && neuralReady == true && VoicePrefs.engine(context, hindi) != null
        val speaker = if (useNatural) neuralTts else tts
        if (!useNatural && ready != true) return
        runCatching { (if (useNatural) tts else neuralTts).stop() }
        VoicePrefs.apply(context, speaker, hindi, style, voice)
        val line = if (mood != null) Expressions.sample(mood, hindi) else VoicePrefs.sample(style, hindi)
        VoicePrefs.speak(speaker, line, style, "preview", "preview-end", flush = true, keepMood = false, mood = mood)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("voice-settings")) {
        if (ready == false) {
            Text(
                "This phone's text-to-speech is not working. Install or turn on \"Speech Services by Google\" (Android Settings → Text-to-speech).",
                color = GravityColors.Red, style = MaterialTheme.typography.bodySmall,
            )
        }
        VoiceCard(
            "Yamini's voice",
            "A woman's or a man's voice, for Hindi and English alike. It also sets how Yamini talks about itself in Hindi: " +
                "female \"मैं कर सकती हूँ, सुन रही थी\", male \"मैं कर सकता हूँ, सुन रहा था\" (in chat and live mode).",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("female" to "Female · महिला", "male" to "Male · पुरुष").forEach { (key, label) ->
                    FilterChip(
                        selected = yaminiGender == key,
                        onClick = {
                            yaminiGender = key
                            VoicePrefs.setGender(context, key)
                            hindiVoice = VoicePrefs.voiceName(context, true); englishVoice = VoicePrefs.voiceName(context, false)
                            gender = key
                            preview(VoiceStyles.byId(styleId), hindiTab)
                        },
                        label = { Text(label) }, modifier = Modifier.testTag("yamini-gender-$key"),
                    )
                }
            }
            if (ready == true && voices.isNotEmpty() && voices.none { VoicePicker.gender(it) == yaminiGender }) {
                Text(
                    "This phone has no ${if (yaminiGender == "male") "male" else "female"} ${if (hindiTab) "Hindi" else "English"} voice that Yamini can tell apart, " +
                        "so the clearest voice is used. Get more voices below.",
                    style = MaterialTheme.typography.bodySmall, color = GravityColors.Amber,
                )
            }
        }

        VoiceCard(
            "Feelings in the voice",
            "Yamini reads the mood of each reply (and of what you said): serious topics slower and calmer, happy ones brighter, sad ones " +
                "soft and slow. Whole sentences are spoken as the voice says them best, with pauses between them.",
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Speak with feeling", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                androidx.compose.material3.Switch(
                    checked = natural,
                    onCheckedChange = { natural = it; VoicePrefs.setNatural(context, it); preview(VoiceStyles.byId(styleId), hindiTab) },
                    modifier = Modifier.testTag("voice-natural"),
                )
            }
            if (natural) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Expressions.Mode.entries.forEach { m ->
                        FilterChip(
                            selected = mode == m,
                            onClick = {
                                mode = m; VoicePrefs.setMode(context, m)
                                preview(VoiceStyles.byId(styleId), hindiTab, mood = fixedMood ?: EmotionClassifier.Emotion.HAPPY)
                            },
                            label = { Text(m.label) }, modifier = Modifier.testTag("voice-mode-${m.name}"),
                        )
                    }
                }
                Text(mode.hindi, style = MaterialTheme.typography.bodySmall, color = GravityColors.Muted)
                if (mode == Expressions.Mode.EXPRESSIVE) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Breath sound", style = MaterialTheme.typography.bodyMedium)
                            Text("A soft breath before some longer sentences (a sound added by the app, not the voice itself).", style = MaterialTheme.typography.bodySmall, color = GravityColors.Muted)
                        }
                        androidx.compose.material3.Switch(
                            checked = breath, onCheckedChange = { breath = it; VoicePrefs.setBreath(context, it) },
                            modifier = Modifier.testTag("voice-breath"),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("\"हम्म…\" while thinking", style = MaterialTheme.typography.bodyMedium)
                        Text("In live mode, a short \"हम्म…\" when the answer takes a moment, so you know Yamini heard you.", style = MaterialTheme.typography.bodySmall, color = GravityColors.Muted)
                    }
                    androidx.compose.material3.Switch(
                        checked = filler, onCheckedChange = { filler = it; VoicePrefs.setFiller(context, it) },
                        modifier = Modifier.testTag("voice-filler"),
                    )
                }
                TextButton(onClick = { showMoods = !showMoods }, modifier = Modifier.testTag("voice-moods")) {
                    Text(if (showMoods) "Hide the moods" else "Hear each mood, or fix one (${fixedMood?.label ?: "automatic"})")
                }
                if (showMoods) {
                    Text(
                        "A phone voice can change speed, pitch, loudness and pauses; Yamini adds a spoken \"हा हा\" where a reply laughs, " +
                            "and in Expressive mode a breath sound. It cannot really cry, whisper or laugh: those moods are as close as the voice allows.",
                        style = MaterialTheme.typography.bodySmall, color = GravityColors.Amber,
                    )
                    Row(
                        Modifier.fillMaxWidth().clickable { fixedMood = null; VoicePrefs.setFixedMood(context, null) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = fixedMood == null, onClick = { fixedMood = null; VoicePrefs.setFixedMood(context, null) })
                        Text("Automatic (from each reply's words)", style = MaterialTheme.typography.bodyMedium)
                    }
                    EmotionClassifier.Emotion.entries.forEach { e ->
                        val p = Expressions.profile(e, 1f, mode)
                        Row(
                            Modifier.fillMaxWidth().clickable { fixedMood = e; VoicePrefs.setFixedMood(context, e); preview(VoiceStyles.byId(styleId), hindiTab, mood = e) }
                                .testTag("voice-mood-${e.name}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = fixedMood == e, onClick = { fixedMood = e; VoicePrefs.setFixedMood(context, e); preview(VoiceStyles.byId(styleId), hindiTab, mood = e) })
                            Column(Modifier.weight(1f)) {
                                Text("${e.label} · ${e.hindi}", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "Speed ×%.2f · pitch ×%.2f · loudness %d%% · pause %d ms".format(p.rate, p.pitch, (p.volume * 100).toInt(), p.sentencePauseMs) +
                                        (if (p.limit.isNotEmpty()) " · ${p.limit}" else ""),
                                    style = MaterialTheme.typography.labelSmall, color = GravityColors.Muted,
                                )
                            }
                            IconButton(onClick = { preview(VoiceStyles.byId(styleId), hindiTab, mood = e) }) {
                                Icon(Icons.Filled.PlayArrow, "Hear ${e.label}", tint = GravityColors.Accent)
                            }
                        }
                    }
                }
            }
        }

        OnlineVoiceCard(yaminiGender, hindiTab)

        VoiceCard("Speaking style", "How Yamini talks in live mode and when it reads replies: its words and mood, and the voice's speed, pitch and pauses.") {
            VoiceStyles.all.forEachIndexed { i, st ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    Modifier.fillMaxWidth().clickable { styleId = st.id; VoicePrefs.setStyle(context, st.id); preview(st, hindiTab) }
                        .padding(vertical = 2.dp).testTag("voice-style-${st.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = styleId == st.id, onClick = { styleId = st.id; VoicePrefs.setStyle(context, st.id); preview(st, hindiTab) })
                    Column(Modifier.weight(1f)) {
                        Text(st.title, style = MaterialTheme.typography.bodyLarge)
                        Text(st.hindi, style = MaterialTheme.typography.bodySmall, color = GravityColors.Muted)
                    }
                    IconButton(onClick = { preview(st, hindiTab) }) { Icon(Icons.Filled.PlayArrow, "Hear ${st.title}", tint = GravityColors.Accent) }
                }
            }
        }

        VoiceCard("Live mode", "How Yamini talks to you when live mode opens and during the conversation.") {
            androidx.compose.material3.OutlinedTextField(
                value = address, onValueChange = { address = it.take(40); VoicePrefs.setAddress(context, address) },
                label = { Text("Call me…") }, placeholder = { Text("Sir, Boss, your name… (empty = the style decides)") },
                singleLine = true, modifier = Modifier.fillMaxWidth().testTag("voice-address"),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Greet me when live mode opens", style = MaterialTheme.typography.bodyMedium)
                    Text("e.g. AI Butler: \"Good evening, Sir. All systems are ready.\"", style = MaterialTheme.typography.bodySmall, color = GravityColors.Muted)
                }
                androidx.compose.material3.Switch(checked = greet, onCheckedChange = { greet = it; VoicePrefs.setGreet(context, it) }, modifier = Modifier.testTag("voice-greet"))
            }
        }

        VoiceCard("Voice", "The phone's own voices. Hindi and Hinglish use the Hindi voice; English uses the accent you pick.") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = hindiTab, onClick = { hindiTab = true }, label = { Text("हिंदी / Hinglish") })
                FilterChip(selected = !hindiTab, onClick = { hindiTab = false }, label = { Text("English") })
            }
            if (!hindiTab) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("IN" to "Indian", "GB" to "British", "US" to "American").forEach { (code, label) ->
                        FilterChip(
                            selected = accent == code,
                            onClick = { accent = code; englishVoice = null; VoicePrefs.setAccent(context, code); preview(VoiceStyles.byId(styleId), false) },
                            label = { Text(label) }, modifier = Modifier.testTag("voice-accent-$code"),
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("all" to "All", "female" to "Female", "male" to "Male").forEach { (key, label) ->
                    FilterChip(selected = gender == key, onClick = { gender = key }, label = { Text(label) }, modifier = Modifier.testTag("voice-gender-$key"))
                }
            }
            if (ready == true && shown.isEmpty()) {
                Text(
                    if (voices.isEmpty()) "No ${if (hindiTab) "Hindi" else "English (India)"} voice is installed on this phone."
                    else "No ${gender} voice is installed for this language. Pick \"All\".",
                    style = MaterialTheme.typography.bodySmall, color = GravityColors.Muted,
                )
            }
            val best = VoicePicker.choose(tts, VoicePrefs.locale(context, hindiTab), null, yaminiGender).takeIf { ready == true } ?: voices.firstOrNull()
            /** The voice the user tapped; a voice of the other gender makes Yamini that gender (voice and grammar match). */
            fun pick(v: Voice) {
                VoicePicker.gender(v)?.takeIf { it != yaminiGender }?.let { g ->
                    VoicePrefs.setGender(context, g); yaminiGender = g
                    if (hindiTab) englishVoice = VoicePrefs.voiceName(context, false) else hindiVoice = VoicePrefs.voiceName(context, true)
                }
                VoicePrefs.setVoice(context, hindiTab, v.name)
                if (hindiTab) hindiVoice = v.name else englishVoice = v.name
                preview(VoiceStyles.byId(styleId), hindiTab, v)
            }
            shown.forEach { v ->
                val selected = chosen == v.name || (chosen == null && v == best)
                Row(
                    Modifier.fillMaxWidth().clickable { pick(v) }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = { pick(v) })
                    Text(VoicePicker.label(v, voices), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { preview(VoiceStyles.byId(styleId), hindiTab, v) }) { Icon(Icons.Filled.PlayArrow, "Hear this voice", tint = GravityColors.Accent) }
                }
            }
            TextButton(onClick = {
                runCatching {
                    context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { toast(context, "Open Android Settings → Text-to-speech") }
            }) { Text("Get more voices (Android text-to-speech settings)") }
        }

        VoiceCard("Speed and pitch", "On top of the style. Lower pitch sounds deeper, higher sounds lighter.") {
            Text("Speed: ${"%.2f".format(rate)}×", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = rate, onValueChange = { rate = it }, valueRange = 0.6f..1.6f,
                onValueChangeFinished = { VoicePrefs.setRate(context, rate); preview(VoiceStyles.byId(styleId), hindiTab) },
            )
            Text("Pitch: ${"%.2f".format(pitch)} (deep ← → light)", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = pitch, onValueChange = { pitch = it }, valueRange = 0.7f..1.4f,
                onValueChangeFinished = { VoicePrefs.setPitch(context, pitch); preview(VoiceStyles.byId(styleId), hindiTab) },
            )
            TextButton(onClick = {
                rate = 1f; pitch = 1f
                VoicePrefs.setRate(context, 1f); VoicePrefs.setPitch(context, 1f)
            }) { Text("Reset speed and pitch") }
        }

        NaturalVoiceCard(onHear = { hindi -> preview(VoiceStyles.byId(styleId), hindi) })
    }
}

@Composable
private fun VoiceCard(title: String, note: String, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(note, style = MaterialTheme.typography.bodySmall, color = GravityColors.Muted)
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

/**
 * Yamini's natural voice: neural voices made on the phone (no internet, no account, no limits),
 * downloaded once per language. Speaking starts at once (about 0.1 s for a sentence).
 */
@Composable
private fun NaturalVoiceCard(onHear: (hindi: Boolean) -> Unit) {
    val context = LocalContext.current
    val c = com.gravity.ide.ui.LocalAppContainer.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val rows by c.db.models().observeVoiceFiles().collectAsState(initial = emptyList())
    var enabled by remember { mutableStateOf(com.gravity.ide.voice.NeuralVoices.enabled(context)) }
    var chosen by remember { mutableStateOf(listOf("hi", "en").associateWith { com.gravity.ide.voice.NeuralVoices.chosen(context, it).id }) }
    var problem by remember { mutableStateOf<String?>(null) }
    val nv = com.gravity.ide.voice.NeuralVoices
    VoiceCard(
        "Offline voice pack (optional)",
        "Yamini normally uses the phone's own voice (Google's on most phones), which sounds the most natural. This optional pack is a " +
            "neural voice made on the phone (no internet, no account) for phones whose own Hindi voice is missing or poor. " +
            "About 67 MB per language; off unless you switch it on.",
    ) {
        val reason = nv.unavailableReason
        if (reason != null) {
            Text(reason, style = MaterialTheme.typography.bodySmall, color = GravityColors.Amber)
            return@VoiceCard
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Use the offline voice instead of the phone's", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(
                checked = enabled, onCheckedChange = { enabled = it; nv.setEnabled(context, it) },
                modifier = Modifier.testTag("natural-voice-on"),
            )
        }
        for ((lang, title) in listOf("hi" to "Hindi and Hinglish", "en" to "English")) {
            Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
            nv.packs.filter { it.lang == lang }.forEach { pack ->
                val status = nv.statusOf(context, rows, pack)
                val row = nv.rowFor(rows, pack)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().testTag("natural-${pack.id}")) {
                    RadioButton(
                        selected = chosen[lang] == pack.id,
                        onClick = { nv.choose(context, pack); chosen = chosen + (lang to pack.id) },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(pack.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            when (status) {
                                "ready" -> "Downloaded · works offline"
                                "downloading" -> "Downloading… " + (row?.let { r -> if (r.sizeBytes > 0) "${r.downloadedBytes * 100 / r.sizeBytes}%" else "" } ?: "")
                                "failed" -> "Download failed: ${row?.error ?: "try again"}"
                                "paused" -> "Paused"
                                else -> "${pack.size / 1_000_000} MB"
                            },
                            style = MaterialTheme.typography.bodySmall, color = GravityColors.Muted,
                        )
                    }
                    when (status) {
                        "ready" -> {
                            IconButton(onClick = { nv.choose(context, pack); chosen = chosen + (lang to pack.id); onHear(lang == "hi") }) {
                                Icon(Icons.Filled.PlayArrow, "Hear ${pack.label}", tint = GravityColors.Accent)
                            }
                            TextButton(onClick = {
                                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    nv.release()
                                    nv.packDir(context, pack).deleteRecursively()
                                    rows.filter { it.name == pack.archiveName }.forEach { c.models.delete(it.id) }
                                }
                            }) { Text("Delete") }
                        }
                        "downloading" -> Unit
                        else -> TextButton(onClick = {
                            problem = null
                            c.appScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                runCatching {
                                    rows.filter { it.name == pack.archiveName }.forEach { c.models.delete(it.id) }
                                    c.models.enqueueDownload(pack.url, pack.archiveName, pack.sha256, pack.size, "k2-fsa/sherpa-onnx", nv.LICENSE_NOTE, role = "voice")
                                    nv.choose(context, pack)
                                    chosen = chosen + (lang to pack.id)
                                }.onFailure { problem = it.message }
                            }
                        }, modifier = Modifier.testTag("natural-download-${pack.id}")) { Text("Download") }
                    }
                }
            }
        }
        problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = GravityColors.Red) }
        Text(nv.LICENSE_NOTE + ". Engine: sherpa-onnx (Apache-2.0).", style = MaterialTheme.typography.labelSmall, color = GravityColors.Muted)
    }
}

/**
 * Gemini's expressive online voice for reading replies aloud: off by default; when on, a reply's
 * text goes to Google with the user's own Gemini key. ▶ really asks Gemini and says what happened.
 */
@Composable
private fun OnlineVoiceCard(gender: String, hindi: Boolean) {
    val context = LocalContext.current
    var on by remember { mutableStateOf(VoicePrefs.online(context)) }
    var voice by remember(gender) { mutableStateOf(VoicePrefs.onlineVoice(context)) }
    var hasKey by remember { mutableStateOf<Boolean?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        hasKey = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.gravity.ide.voice.OnlineVoicePlayer.gemini(context) != null }
    }
    DisposableEffect(Unit) { onDispose { com.gravity.ide.voice.OnlineVoicePlayer.stop() } }
    VoiceCard(
        "Online expressive voice (Gemini)",
        "Reads replies (the speaker button) in Gemini's voice: real laughs, sighs and moods, Hindi and English. Uses your own Gemini key; " +
            "while on, the reply's text is sent to Google. With no internet, no key or an error, the phone's voice reads it instead. " +
            "Live mode keeps the phone's voice, which starts at once.",
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Use the online voice for replies", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(
                checked = on, enabled = hasKey == true,
                onCheckedChange = { on = it; VoicePrefs.setOnline(context, it) },
                modifier = Modifier.testTag("voice-online"),
            )
        }
        if (hasKey == false) {
            Text("Add a Google Gemini key first (AI Providers). Yamini never ships a key of its own.", style = MaterialTheme.typography.bodySmall, color = GravityColors.Amber)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            com.gravity.ide.voice.OnlineVoice.voices.filter { it.gender == gender }.forEach { v ->
                FilterChip(
                    selected = voice == v.name,
                    onClick = { voice = v.name; VoicePrefs.setOnlineVoice(context, v.name) },
                    label = { Text("${v.name} · ${v.character}", style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = hasKey == true, onClick = {
                status = "Asking Gemini…"
                val line = Expressions.sample(EmotionClassifier.Emotion.LAUGHING, hindi)
                val style = com.gravity.ide.voice.OnlineVoice.style(EmotionClassifier.Result(EmotionClassifier.Emotion.LAUGHING, 1f, 0.8f), VoicePrefs.mode(context))
                val started = System.currentTimeMillis()
                com.gravity.ide.voice.OnlineVoicePlayer.speak(context, line, style, voice) { outcome ->
                    status = when (outcome) {
                        is com.gravity.ide.voice.OnlineVoicePlayer.Outcome.Failed -> "Did not work: ${outcome.reason}"
                        com.gravity.ide.voice.OnlineVoicePlayer.Outcome.Done -> "Played in Gemini's voice (%.1f s from tap to end)".format((System.currentTimeMillis() - started) / 1000.0)
                        com.gravity.ide.voice.OnlineVoicePlayer.Outcome.Stopped -> "Stopped"
                    }
                }
            }, modifier = Modifier.testTag("voice-online-test")) { Text("▶ Test the online voice") }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = GravityColors.Muted) }
    }
}
