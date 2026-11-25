package com.gravity.ide.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.gravity.ide.core.SpeechText
import com.gravity.ide.ui.theme.GravityColors
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Languages for live voice mode: what the phone listens for, how the reply is spoken, what the AI is told. */
internal enum class LiveLanguage(val label: String, val recognizeTag: String, val voice: Locale, val forModel: String) {
    // Hinglish is heard with the Hindi recognizer (it knows Hindi words and English ones) and spoken
    // with a Hindi voice; Hindi words come in Devanagari so that voice pronounces them right.
    HINGLISH(
        "Hinglish", "hi-IN", Locale.forLanguageTag("hi-IN"),
        "Hinglish: mix Hindi and English the way people in India talk. Write the Hindi words in Devanagari and the English words " +
            "in English letters (for example: \"आज का weather काफी pleasant है\"), so the voice pronounces both correctly",
    ),
    HINDI("हिंदी", "hi-IN", Locale.forLanguageTag("hi-IN"), "Hindi, written in Devanagari script, in simple everyday Hindi"),
    ENGLISH("English", "en-IN", Locale.forLanguageTag("en-IN"), "clear, simple English"),
}

/** Speaking speeds offered in live mode. */
private val speeds = listOf("Slow" to 0.85f, "Normal" to 1.0f, "Fast" to 1.2f)

private enum class LivePhase { IDLE, LISTENING, THINKING, SPEAKING }

/**
 * Live voice mode, like a call: the phone listens (Android speech recognition), the chat's AI
 * answers (the message and the reply are saved in the chat), the reply is spoken (Android
 * text-to-speech), then it listens again. Nothing here is simulated: if the phone has no speech
 * recognition, no voice for the language, or no microphone permission, it says so.
 */
@Composable
internal fun LiveVoiceDialog(vm: ChatViewModel, modelName: String, onClose: () -> Unit) {
    val context = LocalContext.current
    var language by rememberSaveable { mutableStateOf(LiveLanguage.HINGLISH) }
    var phase by remember { mutableStateOf(LivePhase.IDLE) }
    var heard by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var level by remember { mutableStateOf(0f) }
    var misses by remember { mutableStateOf(0) }
    var ttsReady by remember { mutableStateOf<Boolean?>(null) }
    val open = remember { AtomicBoolean(true) }
    val main = remember { Handler(Looper.getMainLooper()) }
    // Set below; lets callbacks created once start listening with the current settings.
    val listenNow = remember { mutableStateOf<() -> Unit>({}) }

    val tts = remember { TextToSpeech(context.applicationContext) { status -> main.post { ttsReady = status == TextToSpeech.SUCCESS } } }
    val prefs = remember { context.getSharedPreferences("live_voice", android.content.Context.MODE_PRIVATE) }
    var rate by remember { mutableStateOf(prefs.getFloat("rate", 1.0f)) }
    // The voice chosen for each language (null = the clearest installed one, see VoicePicker).
    var voiceName by remember(language) { mutableStateOf(prefs.getString("voice_${language.name}", null)) }
    val voices = remember(ttsReady, language) {
        if (ttsReady == true) com.gravity.ide.files.VoicePicker.voices(tts, language.voice) else emptyList()
    }
    var voiceMenu by remember { mutableStateOf(false) }
    // Recreated when the phone's speech service gets stuck (errors 5, 8, 11), so live mode keeps working.
    var recognizer by remember { mutableStateOf(createRecognizer(context)) }
    var retries by remember { mutableStateOf(0) }
    fun resetRecognizer() {
        runCatching { recognizer?.destroy() }
        recognizer = createRecognizer(context)
    }
    // Live replies are spoken while they are still being written: what has been queued so far.
    var spokenUpTo by remember { mutableStateOf(0) }
    var partNo by remember { mutableStateOf(0) }
    var voiceReady by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            open.set(false)
            runCatching { recognizer?.destroy() }
            runCatching { tts.stop(); tts.shutdown() }
            vm.liveLanguage.value = null
        }
    }
    LaunchedEffect(language) { vm.liveLanguage.value = language.forModel }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            listenNow.value()
        } else {
            phase = LivePhase.IDLE
            problem = "Gravity needs the microphone to hear you. Allow it when Android asks, or in Android Settings → Apps → Gravity → Permissions."
        }
    }

    fun listen() {
        if (!open.get()) return
        val r = recognizer
        if (r == null) {
            phase = LivePhase.IDLE
            problem = "This phone has no speech recognition service. Install or turn on Google's speech services (the Google app), then try again."
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        problem = null
        heard = ""
        level = 0f
        phase = LivePhase.LISTENING
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.recognizeTag)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Stop listening sooner after the user stops talking (services that support it).
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
        runCatching { r.startListening(intent) }.onFailure {
            phase = LivePhase.IDLE
            problem = "Could not start listening: ${it.message}"
        }
    }
    listenNow.value = { listen() }
    /** Listen again a moment later: the speech service refuses a new start right after the last one ended. */
    fun listenSoon(delayMs: Long = 250) = main.postDelayed({ if (open.get()) listenNow.value() }, delayMs)

    /** Voice and speed for this turn (set once, before the first piece is queued). */
    fun prepareVoice() {
        if (voiceReady) return
        val result = tts.setLanguage(language.voice)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            problem = "This phone has no ${language.label} voice installed, so another voice reads the reply. Add one in Android Settings → Text-to-speech."
        }
        com.gravity.ide.files.VoicePicker.choose(tts, language.voice, voiceName)?.let { tts.setVoice(it) }
        tts.setSpeechRate(rate)
        tts.setPitch(1.0f)
        voiceReady = true
    }

    /** Queues one finished piece of the reply while the rest is still being written. */
    fun speakPiece(piece: String) {
        val clean = SpeechText.clean(piece)
        if (clean.isBlank() || ttsReady != true) return
        prepareVoice()
        tts.speak(clean, TextToSpeech.QUEUE_ADD, null, "live-part-${partNo++}")
        phase = LivePhase.SPEAKING
    }

    /** The reply is complete: queue what is left, then listen again when the voice is done. */
    fun finishSpeaking(rest: String) {
        if (ttsReady != true) {
            if (ttsReady == false) problem = "This phone's text-to-speech is not working, so the reply is only shown. Check Android Settings → Text-to-speech."
            phase = LivePhase.IDLE
            listenSoon()
            return
        }
        prepareVoice()
        val parts = SpeechText.chunks(SpeechText.clean(rest), 3500).filter { it.isNotBlank() }
        if (parts.isEmpty()) {
            if (phase != LivePhase.SPEAKING) {
                phase = LivePhase.IDLE
                listenSoon()
                return
            }
            tts.playSilentUtterance(10, TextToSpeech.QUEUE_ADD, "live-end")
        } else {
            parts.forEachIndexed { i, part -> tts.speak(part, TextToSpeech.QUEUE_ADD, null, if (i == parts.lastIndex) "live-end" else "live-rest-$i") }
        }
        phase = LivePhase.SPEAKING
    }

    // Speak each finished sentence as soon as the AI has written it.
    val streamed by vm.streaming.collectAsState()
    LaunchedEffect(streamed) {
        val text = streamed ?: return@LaunchedEffect
        if (phase != LivePhase.THINKING && phase != LivePhase.SPEAKING) return@LaunchedEffect
        val end = SpeechText.speakableEnd(text, spokenUpTo)
        if (end > spokenUpTo) {
            val piece = text.substring(spokenUpTo, end)
            spokenUpTo = end
            speakPiece(piece)
        }
    }

    DisposableEffect(tts) {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == "live-end") main.post {
                    if (open.get() && phase == LivePhase.SPEAKING) {
                        phase = LivePhase.IDLE
                        main.postDelayed({ if (open.get()) listenNow.value() }, 250)
                    }
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                main.post {
                    if (open.get() && phase == LivePhase.SPEAKING) {
                        phase = LivePhase.IDLE
                        listenNow.value()
                    }
                }
            }
        })
        onDispose { }
    }

    DisposableEffect(recognizer) {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { retries = 0 }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { level = rmsdB }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { if (it.isNotBlank()) heard = it }
            }

            override fun onError(error: Int) {
                if (!open.get()) return
                phase = LivePhase.IDLE
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        // Silence: listen once more, then wait for a tap.
                        misses++
                        if (misses < 2) listen() else problem = "I did not hear anything. Tap the circle and speak."
                    }
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        problem = "Gravity needs the microphone to hear you (Android Settings → Apps → Gravity → Permissions)."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                        problem = "Speech recognition needs the internet on this phone, or an offline ${language.label} speech pack (Google app → Settings → Voice → Offline speech recognition)."
                    SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> {
                        // The phone's speech service got stuck: start a fresh one and listen again.
                        retries++
                        if (retries <= 2) {
                            resetRecognizer()
                            listenSoon(400)
                        } else {
                            problem = "The phone's speech service is not responding. Tap the circle to try again."
                        }
                    }
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                        problem = "The phone's speech service has no ${language.label} yet. Add it in the Google app → Settings → Voice → Languages / Offline speech, or pick another language."
                    else -> problem = "Speech recognition stopped (error $error). Tap the circle to try again."
                }
            }

            override fun onResults(results: Bundle?) {
                if (!open.get()) return
                val said = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
                if (said.isEmpty()) {
                    phase = LivePhase.IDLE
                    return
                }
                misses = 0
                retries = 0
                heard = said
                reply = ""
                spokenUpTo = 0
                partNo = 0
                voiceReady = false
                phase = LivePhase.THINKING
                val started = vm.sendLive(said) { answer, error ->
                    if (!open.get()) return@sendLive
                    reply = answer
                    when {
                        error == "Stopped" -> Unit // interrupted by the user
                        error != null -> {
                            tts.stop()
                            phase = LivePhase.IDLE
                            problem = error
                        }
                        else -> finishSpeaking(if (spokenUpTo in 0..answer.length) answer.substring(spokenUpTo) else answer)
                    }
                }
                if (!started) {
                    phase = LivePhase.IDLE
                    problem = "The AI is still answering the last message. Wait a moment, then tap the circle."
                }
            }
        })
        onDispose { }
    }

    // Start listening as soon as live mode opens.
    LaunchedEffect(Unit) { listen() }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("live-dialog"), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Live", style = MaterialTheme.typography.titleLarge)
                        Text(modelName, style = MaterialTheme.typography.labelMedium, color = GravityColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (Regex("(?i)\\b(7|8|13|14)b\\b").containsMatchIn(modelName)) {
                            Text(
                                "Big phone models answer slowly. For quicker live talk use Gemini, or Llama 3.2 3B from Phone Models.",
                                style = MaterialTheme.typography.labelSmall, color = GravityColors.Amber,
                            )
                        }
                    }
                    IconButton(onClick = onClose, modifier = Modifier.testTag("live-close")) { Icon(Icons.Filled.Close, "End live mode") }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LiveLanguage.entries.forEach { l ->
                        FilterChip(
                            selected = language == l,
                            onClick = {
                                language = l
                                if (phase == LivePhase.LISTENING) {
                                    recognizer?.cancel()
                                    listenSoon(300)
                                }
                            },
                            label = { Text(l.label) },
                            modifier = Modifier.testTag("live-lang-${l.name.lowercase()}"),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box {
                        val current = voices.indexOfFirst { it.name == voiceName }.let { if (it < 0) 0 else it }
                        androidx.compose.material3.TextButton(onClick = { voiceMenu = true }, enabled = voices.isNotEmpty(), modifier = Modifier.testTag("live-voice")) {
                            Text(if (voices.isEmpty()) "No ${language.label} voice installed" else "Voice ${current + 1} ▾")
                        }
                        androidx.compose.material3.DropdownMenu(expanded = voiceMenu, onDismissRequest = { voiceMenu = false }) {
                            voices.forEachIndexed { i, v ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(com.gravity.ide.files.VoicePicker.label(v, i)) },
                                    onClick = {
                                        voiceMenu = false
                                        voiceName = v.name
                                        prefs.edit().putString("voice_${language.name}", v.name).apply()
                                        // Say a few words so the voice can be heard right away.
                                        if (phase != LivePhase.LISTENING) {
                                            tts.setLanguage(language.voice)
                                            tts.setVoice(v)
                                            tts.setSpeechRate(rate)
                                            tts.speak(if (language == LiveLanguage.ENGLISH) "Hello! This is my voice." else "नमस्ते! मेरी आवाज़ ऐसी है।", TextToSpeech.QUEUE_FLUSH, null, "voice-sample")
                                        }
                                    },
                                )
                            }
                        }
                    }
                    speeds.forEach { (label, value) ->
                        FilterChip(
                            selected = rate == value,
                            onClick = { rate = value; prefs.edit().putFloat("rate", value).apply() },
                            label = { Text(label) },
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                val pulse by rememberInfiniteTransition(label = "live").animateFloat(
                    initialValue = 1f, targetValue = 1.08f,
                    animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse",
                )
                val scale = when (phase) {
                    LivePhase.LISTENING -> 1f + level.coerceIn(0f, 10f) / 40f
                    LivePhase.THINKING, LivePhase.SPEAKING -> pulse
                    LivePhase.IDLE -> 1f
                }
                Box(
                    Modifier.size(180.dp).scale(scale).clip(CircleShape)
                        .background(GravityColors.Accent.copy(alpha = if (phase == LivePhase.IDLE) 0.35f else 0.9f))
                        .clickable(onClickLabel = "Talk") {
                            when (phase) {
                                LivePhase.IDLE -> { misses = 0; listen() }
                                LivePhase.LISTENING -> recognizer?.stopListening()
                                LivePhase.SPEAKING -> {
                                    tts.stop()
                                    if (vm.busy.value) vm.stop()
                                    phase = LivePhase.IDLE
                                    listenSoon()
                                }
                                LivePhase.THINKING -> Unit
                            }
                        }
                        .testTag("live-orb"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (phase == LivePhase.SPEAKING) Icons.Filled.GraphicEq else Icons.Filled.Mic, null,
                        tint = GravityColors.OnAccent, modifier = Modifier.size(56.dp),
                    )
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    when (phase) {
                        LivePhase.IDLE -> "Tap the circle and speak"
                        LivePhase.LISTENING -> "Listening…"
                        LivePhase.THINKING -> "Thinking…"
                        LivePhase.SPEAKING -> "Speaking… (tap the circle to interrupt)"
                    },
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("live-status"),
                )
                if (heard.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text("You: $heard", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                if (reply.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        SpeechText.clean(reply), style = MaterialTheme.typography.bodyMedium, color = GravityColors.Muted,
                        textAlign = TextAlign.Center, maxLines = 6, overflow = TextOverflow.Ellipsis,
                    )
                }
                problem?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = GravityColors.Red, textAlign = TextAlign.Center, modifier = Modifier.testTag("live-problem"))
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onClose,
                    colors = ButtonDefaults.buttonColors(containerColor = GravityColors.Red, contentColor = Color.White),
                    modifier = Modifier.testTag("live-end"),
                ) { Text("End") }
            }
        }
    }
}

private fun createRecognizer(context: android.content.Context): SpeechRecognizer? =
    if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context) else null
