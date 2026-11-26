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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gravity.ide.core.VoiceStyles
import com.gravity.ide.files.VoicePicker
import com.gravity.ide.files.VoicePrefs
import com.gravity.ide.ui.theme.GravityColors

/**
 * Settings → Voice & speech: 10 speaking styles, the phone's male and female voices for Hindi and
 * English, speed and pitch, each with a ▶ to hear it. Used by live mode, the speaker button and
 * audio files. The voices are the phone's own text-to-speech voices (Google's on most phones).
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
    val voices = remember(ready, hindiTab, accent) { if (ready == true) VoicePicker.voices(tts, VoicePrefs.locale(context, hindiTab)) else emptyList() }
    val shown = voices.filter { gender == "all" || VoicePicker.gender(it) == gender }
    val chosen = if (hindiTab) hindiVoice else englishVoice

    fun preview(style: VoiceStyles.Style, hindi: Boolean, voice: Voice? = null) {
        if (ready != true) return
        VoicePrefs.apply(context, tts, hindi, style, voice)
        VoicePrefs.speak(tts, VoicePrefs.sample(style, hindi), style, "preview", "preview-end", flush = true)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("voice-settings")) {
        if (ready == false) {
            Text(
                "This phone's text-to-speech is not working. Install or turn on \"Speech Services by Google\" (Android Settings → Text-to-speech).",
                color = GravityColors.Red, style = MaterialTheme.typography.bodySmall,
            )
        }
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
            val best = voices.firstOrNull()
            shown.forEach { v ->
                val selected = chosen == v.name || (chosen == null && v == best)
                Row(
                    Modifier.fillMaxWidth().clickable {
                        VoicePrefs.setVoice(context, hindiTab, v.name)
                        if (hindiTab) hindiVoice = v.name else englishVoice = v.name
                        preview(VoiceStyles.byId(styleId), hindiTab, v)
                    }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = {
                        VoicePrefs.setVoice(context, hindiTab, v.name)
                        if (hindiTab) hindiVoice = v.name else englishVoice = v.name
                        preview(VoiceStyles.byId(styleId), hindiTab, v)
                    })
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
