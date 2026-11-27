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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.material3.TextButton
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.gravity.ide.core.SpeechText
import com.gravity.ide.core.VoiceStyles
import com.gravity.ide.files.VoicePrefs
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
    val open = remember { AtomicBoolean(true) }
    val main = remember { Handler(Looper.getMainLooper()) }
    // Set below; lets callbacks created once start listening with the current settings.
    val listenNow = remember { mutableStateOf<() -> Unit>({}) }

    // Hinglish and Hindi use the Hindi voice; English the English (India) voice.
    val hindiVoice = language != LiveLanguage.ENGLISH
    // Yamini's natural voice (offline) when its pack is downloaded, else the phone's own voice.
    val engine = remember(hindiVoice) { VoicePrefs.engine(context, hindiVoice) }
    var ttsReady by remember(engine) { mutableStateOf<Boolean?>(null) }
    val tts = remember(engine) { VoicePrefs.create(context, engine) { status -> main.post { ttsReady = status == TextToSpeech.SUCCESS } } }
    DisposableEffect(tts) { onDispose { runCatching { tts.stop(); tts.shutdown() } } }
    LaunchedEffect(hindiVoice) { com.gravity.ide.voice.NeuralVoices.preload(context, if (hindiVoice) "hi" else "en") }
    // Speaking style (Settings → Voice & speech): the AI's mood and the voice's speed, pitch and pauses.
    var styleId by remember { mutableStateOf(VoicePrefs.style(context).id) }
    val style = VoiceStyles.byId(styleId)
    var voiceName by remember(language) { mutableStateOf(VoicePrefs.voiceName(context, hindiVoice)) }
    val voices = remember(ttsReady, language) {
        if (ttsReady == true) com.gravity.ide.files.VoicePicker.voices(tts, VoicePrefs.locale(context, hindiVoice)) else emptyList()
    }
    var voiceMenu by remember { mutableStateOf(false) }
    var styleMenu by remember { mutableStateOf(false) }
    // "Fastest AI": live talk uses the cloud AI (answers in about a second) when there is one and the phone is online.
    val providers by vm.providers.collectAsState(initial = emptyList())
    val hasCloud = providers.any { it.enabled && it.kind != com.gravity.ide.ai.ProviderKind.LOCAL.name }
    // Off until the user turns it on: while on, what they say goes to the cloud AI even in a phone-model chat.
    var fastest by remember { mutableStateOf(VoicePrefs.fastest(context)) }
    // Recreated when the phone's speech service gets stuck (errors 5, 8, 11), so live mode keeps working.
    var recognizer by remember { mutableStateOf(createRecognizer(context)) }
    var retries by remember { mutableStateOf(0) }
    fun resetRecognizer() {
        runCatching { recognizer?.destroy() }
        recognizer = createRecognizer(context)
    }
    // End of speech: when the words stop changing for a moment, stop listening at once (the
    // speech service on its own waits 1-2 s of silence before it answers).
    // The speech service then still works out its final words, sometimes for a second or more: the
    // words heard so far are sent when the final ones are not there within a moment.
    val submitNowRef = remember { mutableStateOf<(String) -> Unit>({}) }
    val finalWait = remember { Runnable { if (open.get() && phase == LivePhase.LISTENING && heard.isNotBlank()) submitNowRef.value(heard.trim()) } }
    val endpoint = remember {
        Runnable {
            if (open.get() && phase == LivePhase.LISTENING && heard.isNotBlank()) {
                recognizer?.stopListening()
                main.postDelayed(finalWait, 300)
            }
        }
    }
    // Live replies are spoken while they are still being written: what has been queued so far.
    var spokenUpTo by remember { mutableStateOf(0) }
    var partNo by remember { mutableStateOf(0) }
    var voiceReady by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            open.set(false)
            runCatching { recognizer?.destroy() }
            vm.liveLanguage.value = null
        }
    }
    LaunchedEffect(language) { vm.liveLanguage.value = language.forModel }
    // The voice in use (chosen, else the clearest one of Yamini's gender) decides the Hindi grammar for "I".
    val yaminiGender = VoicePrefs.gender(context)
    val voiceGender = (voices.firstOrNull { it.name == voiceName }
        ?: voices.firstOrNull { com.gravity.ide.files.VoicePicker.gender(it) == yaminiGender } ?: voices.firstOrNull())
        ?.let { com.gravity.ide.files.VoicePicker.gender(it) } ?: yaminiGender
    LaunchedEffect(styleId, voiceGender, hindiVoice) {
        vm.livePersona.value = VoiceStyles.personaFor(style, VoicePrefs.address(context), voiceGender, hindiVoice)
    }
    LaunchedEffect(fastest, hasCloud) { vm.liveFastest.value = fastest && hasCloud }
    // Load the AI now (e.g. the phone model) and let it read the live instructions, not after the
    // user's first sentence; again when the language, style or voice (its persona) changes.
    LaunchedEffect(language, styleId, voiceGender) {
        kotlinx.coroutines.delay(200) // the effects above set the language and persona first
        vm.warmUp()
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            listenNow.value()
        } else {
            phase = LivePhase.IDLE
            problem = "Yamini needs the microphone to hear you. Allow it when Android asks, or in Android Settings → Apps → Yamini → Permissions."
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
    fun listenSoon(delayMs: Long = 150) = main.postDelayed({ if (open.get()) listenNow.value() }, delayMs)

    /** Voice and speed for this turn (set once, before the first piece is queued). */
    fun prepareVoice() {
        if (voiceReady) return
        val result = VoicePrefs.apply(context, tts, hindiVoice, style)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            problem = "This phone has no ${language.label} voice installed, so another voice reads the reply. Add one in Android Settings → Text-to-speech."
        }
        voiceReady = true
    }

    /** Queues one finished piece of the reply while the rest is still being written. */
    fun speakPiece(piece: String) {
        val clean = SpeechText.clean(piece)
        if (clean.isBlank() || ttsReady != true) return
        prepareVoice()
        val n = partNo++
        VoicePrefs.speak(tts, clean, style, "live-p$n", "live-p$n-end", flush = false, userText = heard)
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
        val clean = SpeechText.clean(rest)
        if (clean.isBlank() && phase != LivePhase.SPEAKING) {
            phase = LivePhase.IDLE
            listenSoon()
            return
        }
        VoicePrefs.speak(tts, clean, style, "live-rest", "live-end", flush = false, userText = heard)
        phase = LivePhase.SPEAKING
    }

    // Sends what the user said (or the "Kuch baat karo" button) and speaks the answer as it comes.
    // Kept in a state holder so the speech recognizer's listener always calls the current one.
    val submitNow = remember { mutableStateOf<(String) -> Unit>({}) }
    // A listening response: when the answer takes a moment, a short "हम्म…" (no gendered words)
    // so the user knows they were heard. Never when the reply has already started.
    val fillerNow = remember { mutableStateOf<() -> Unit>({}) }
    fillerNow.value = {
        if (open.get() && phase == LivePhase.THINKING && partNo == 0 && ttsReady == true && VoicePrefs.filler(context)) {
            prepareVoice()
            val line = com.gravity.ide.core.SpeechPlanner.listeningFiller(hindiVoice, (System.nanoTime() / 1000).toInt())
            VoicePrefs.speak(tts, line, style, "filler", "filler-end", flush = false, keepMood = false)
        }
    }
    val filler = remember { Runnable { fillerNow.value() } }
    submitNowRef.value = { said -> submitNow.value(said) }
    submitNow.value = { said ->
        heard = said
        reply = ""
        spokenUpTo = 0
        partNo = 0
        voiceReady = false
        problem = null
        phase = LivePhase.THINKING
        main.removeCallbacks(filler)
        main.postDelayed(filler, 900)
        val started = vm.sendLive(said) { answer, error ->
            if (open.get()) {
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
        }
        if (!started) {
            phase = LivePhase.IDLE
            problem = "The AI is still answering the last message. Wait a moment, then tap the circle."
        }
    }
    DisposableEffect(Unit) { onDispose { main.removeCallbacks(filler) } }

    /** Plays a sample of a style or voice. Pauses listening (so the mic does not hear it); never cuts a reply. */
    fun previewSample(st: VoiceStyles.Style, v: android.speech.tts.Voice? = null) {
        if (ttsReady != true || phase == LivePhase.THINKING || phase == LivePhase.SPEAKING) return
        if (phase == LivePhase.LISTENING) {
            main.removeCallbacks(endpoint)
            runCatching { recognizer?.cancel() }
            phase = LivePhase.IDLE
        }
        VoicePrefs.apply(context, tts, hindiVoice, st, v)
        VoicePrefs.speak(tts, VoicePrefs.sample(st, hindiVoice), st, "sample", "sample-end", flush = true)
        voiceReady = false
    }

    // Load the voice before the first reply (the first use of a voice can take a moment).
    LaunchedEffect(ttsReady, language, styleId, voiceName) {
        if (ttsReady == true) {
            voiceReady = false
            prepareVoice()
            tts.playSilentUtterance(1, TextToSpeech.QUEUE_ADD, "warm-up")
        }
    }

    // Speak each finished sentence as soon as the AI has written it.
    val streamed by vm.streaming.collectAsState()
    LaunchedEffect(streamed) {
        val text = streamed ?: return@LaunchedEffect
        if (phase != LivePhase.THINKING && phase != LivePhase.SPEAKING) return@LaunchedEffect
        reply = text
        if (spokenUpTo > text.length) spokenUpTo = text.length
        // The first piece may stop at a clause, so the voice starts as soon as possible.
        val end = if (partNo == 0) SpeechText.firstSpeakableEnd(text, spokenUpTo) else SpeechText.speakableEnd(text, spokenUpTo)
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
                // After a sample, go back to listening.
                if (utteranceId == "sample-end") main.post { if (open.get() && phase == LivePhase.IDLE) listenSoon() }
                if (utteranceId == "live-end") main.post {
                    if (open.get() && phase == LivePhase.SPEAKING) {
                        phase = LivePhase.IDLE
                        main.postDelayed({ if (open.get()) listenNow.value() }, 150)
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
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
                    if (it.isNotBlank() && it != heard) {
                        heard = it
                        main.removeCallbacks(endpoint)
                        main.postDelayed(endpoint, 650)
                    }
                }
            }

            override fun onError(error: Int) {
                main.removeCallbacks(endpoint)
                main.removeCallbacks(finalWait)
                if (!open.get()) return
                // The words were already sent (the final ones came too late): this listening is over.
                if (phase == LivePhase.THINKING || phase == LivePhase.SPEAKING) return
                phase = LivePhase.IDLE
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        // Silence: listen once more, then wait for a tap.
                        misses++
                        if (misses < 2) listen() else problem = "I did not hear anything. Tap the circle and speak."
                    }
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        problem = "Yamini needs the microphone to hear you (Android Settings → Apps → Yamini → Permissions)."
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
                main.removeCallbacks(endpoint)
                main.removeCallbacks(finalWait)
                if (!open.get()) return
                // Already sent with the words heard so far.
                if (phase != LivePhase.LISTENING) return
                val said = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
                if (said.isEmpty()) {
                    phase = LivePhase.IDLE
                    return
                }
                misses = 0
                retries = 0
                submitNow.value(said)
            }
        })
        onDispose { }
    }

    // When live mode opens: a short greeting in the chosen style (then it listens), or straight to listening.
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(ttsReady) {
        if (opened || ttsReady == null) return@LaunchedEffect
        opened = true
        if (ttsReady == true && VoicePrefs.greet(context)) {
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            val line = VoiceStyles.greeting(style, hindiVoice, VoicePrefs.address(context), hour, voiceGender == "female")
            prepareVoice()
            reply = line
            phase = LivePhase.SPEAKING
            VoicePrefs.speak(tts, line, style, "greet", "live-end", flush = false)
        } else {
            listen()
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("live-dialog"), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Live · ${style.title}", style = MaterialTheme.typography.titleLarge)
                        Text(modelName, style = MaterialTheme.typography.labelMedium, color = GravityColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (Regex("(?i)\\b(7|8|13|14)b\\b").containsMatchIn(modelName)) {
                            Text(
                                "Big phone models answer slowly. Turn on ⚡ Fastest AI (cloud), or use Llama 3.2 3B from Phone Models.",
                                style = MaterialTheme.typography.labelSmall, color = GravityColors.Amber,
                            )
                        }
                    }
                    IconButton(onClick = onClose, modifier = Modifier.testTag("live-close")) { Icon(Icons.Filled.Close, "End live mode") }
                }
                // The natural offline voice is not downloaded yet: offer it here, where it matters most.
                NaturalVoiceOffer(if (hindiVoice) "hi" else "en")
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
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box {
                        androidx.compose.material3.TextButton(onClick = { styleMenu = true }, modifier = Modifier.testTag("live-style")) {
                            Text("Style: ${style.title} ▾")
                        }
                        androidx.compose.material3.DropdownMenu(expanded = styleMenu, onDismissRequest = { styleMenu = false }) {
                            VoiceStyles.all.forEach { st ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("${st.title} · ${st.hindi}") },
                                    onClick = {
                                        styleMenu = false
                                        styleId = st.id
                                        VoicePrefs.setStyle(context, st.id)
                                        previewSample(st)
                                    },
                                )
                            }
                        }
                    }
                    Box {
                        val currentVoice = voices.firstOrNull { it.name == voiceName } ?: voices.firstOrNull()
                        androidx.compose.material3.TextButton(onClick = { voiceMenu = true }, enabled = voices.isNotEmpty(), modifier = Modifier.testTag("live-voice")) {
                            Text(
                                if (currentVoice == null) "No ${language.label} voice" else com.gravity.ide.files.VoicePicker.label(currentVoice, voices).substringBefore(" ·") + " ▾",
                            )
                        }
                        androidx.compose.material3.DropdownMenu(expanded = voiceMenu, onDismissRequest = { voiceMenu = false }) {
                            voices.forEach { v ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(com.gravity.ide.files.VoicePicker.label(v, voices)) },
                                    onClick = {
                                        voiceMenu = false
                                        voiceName = v.name
                                        VoicePrefs.setVoice(context, hindiVoice, v.name)
                                        previewSample(style, v)
                                    },
                                )
                            }
                        }
                    }
                }
                if (hasCloud) {
                    FilterChip(
                        selected = fastest, onClick = { fastest = !fastest; VoicePrefs.setFastest(context, fastest) },
                        label = { Text(if (fastest) "⚡ Fastest AI: on (cloud)" else "⚡ Fastest AI: off") },
                        modifier = Modifier.testTag("live-fastest"),
                    )
                    if (fastest) {
                        Text(
                            "What you say goes to your cloud AI (e.g. Gemini) for quick answers, even in a phone-model chat.",
                            style = MaterialTheme.typography.labelSmall, color = GravityColors.Muted, textAlign = TextAlign.Center,
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
                // HUD rings: they turn while it thinks or speaks and follow your voice while it listens.
                val spin by rememberInfiniteTransition(label = "hud").animateFloat(
                    initialValue = 0f, targetValue = 360f,
                    animationSpec = infiniteRepeatable(tween(if (phase == LivePhase.THINKING) 1400 else 5200, easing = androidx.compose.animation.core.LinearEasing)),
                    label = "spin",
                )
                val ringColor = GravityColors.Accent
                Box(contentAlignment = Alignment.Center) {
                androidx.compose.foundation.Canvas(Modifier.size(250.dp).testTag("live-hud")) {
                    val active = phase != LivePhase.IDLE
                    val alpha = if (active) 0.9f else 0.3f
                    val stroke1 = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    val stroke2 = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    val grow = if (phase == LivePhase.LISTENING) level.coerceIn(0f, 10f) / 10f * 10.dp.toPx() else 0f
                    val r1 = size.minDimension / 2 - 4.dp.toPx()
                    // Outer ring: three arcs turning clockwise.
                    for (k in 0 until 3) {
                        drawArc(
                            ringColor.copy(alpha = alpha), startAngle = spin + k * 120f, sweepAngle = 70f, useCenter = false, style = stroke1,
                            topLeft = androidx.compose.ui.geometry.Offset(center.x - r1, center.y - r1), size = androidx.compose.ui.geometry.Size(r1 * 2, r1 * 2),
                        )
                    }
                    // Inner ring: two arcs turning the other way, a little larger with your voice.
                    val r2 = r1 - 14.dp.toPx() + grow
                    for (k in 0 until 2) {
                        drawArc(
                            ringColor.copy(alpha = alpha * 0.7f), startAngle = -spin * 1.5f + k * 180f, sweepAngle = 110f, useCenter = false, style = stroke2,
                            topLeft = androidx.compose.ui.geometry.Offset(center.x - r2, center.y - r2), size = androidx.compose.ui.geometry.Size(r2 * 2, r2 * 2),
                        )
                    }
                    // Tick marks around the outside.
                    val r3 = r1 + 2.dp.toPx()
                    for (k in 0 until 48) {
                        val a = Math.toRadians((k * 7.5 + spin / 4).toDouble())
                        val len = if (k % 4 == 0) 6.dp.toPx() else 3.dp.toPx()
                        val cx = center.x + (r3 * kotlin.math.cos(a)).toFloat()
                        val cy = center.y + (r3 * kotlin.math.sin(a)).toFloat()
                        val ex = center.x + ((r3 + len) * kotlin.math.cos(a)).toFloat()
                        val ey = center.y + ((r3 + len) * kotlin.math.sin(a)).toFloat()
                        drawLine(ringColor.copy(alpha = alpha * 0.45f), androidx.compose.ui.geometry.Offset(cx, cy), androidx.compose.ui.geometry.Offset(ex, ey), strokeWidth = 1.dp.toPx())
                    }
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
                // Nothing to say? Yamini brings up a topic herself (a different one each time).
                if (phase == LivePhase.IDLE || phase == LivePhase.LISTENING) {
                    androidx.compose.material3.OutlinedButton(
                        onClick = {
                            recognizer?.cancel()
                            submitNow.value(if (hindiVoice) "कुछ बात करो" else "Let's chat about something")
                        },
                        modifier = Modifier.testTag("live-topic"),
                    ) { Text(if (hindiVoice) "💬 कुछ बात करो" else "💬 Talk about something") }
                    Spacer(Modifier.height(8.dp))
                }
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

/** A one-line offer to download Yamini's natural voice for [lang] (hidden once it is there or downloading). */
@Composable
private fun NaturalVoiceOffer(lang: String) {
    val context = LocalContext.current
    val c = com.gravity.ide.ui.LocalAppContainer.current
    val nv = com.gravity.ide.voice.NeuralVoices
    if (!nv.available || !nv.enabled(context)) return
    val rows by c.db.models().observeVoiceFiles().collectAsState(initial = emptyList())
    val pack = nv.chosen(context, lang)
    val status = nv.statusOf(context, rows, pack)
    if (status == "ready" || status == "downloading") return
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth().padding(top = 6.dp).testTag("live-natural-offer"), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Get Yamini's natural voice: human-like, offline, starts at once (${pack.size / 1_000_000} MB, once).",
            style = MaterialTheme.typography.labelSmall, color = GravityColors.Muted, modifier = Modifier.weight(1f),
        )
        TextButton(onClick = {
            c.appScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                runCatching {
                    rows.filter { it.name == pack.archiveName }.forEach { c.models.delete(it.id) }
                    c.models.enqueueDownload(pack.url, pack.archiveName, pack.sha256, pack.size, "k2-fsa/sherpa-onnx", nv.LICENSE_NOTE, role = "voice")
                }
            }
        }) { Text("Download") }
    }
}

