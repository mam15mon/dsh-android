package io.github.jackwener.dsh.feature.voice

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.jackwener.dsh.feature.conversation.ComposerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

@Stable
class VoiceInput internal constructor(private val context: Context, private val composer: ComposerState, private val scope: CoroutineScope) {
    private val machine = VoiceMachine()
    var state: VoiceState by mutableStateOf(VoiceState.Idle)
        private set
    val active get() = state is VoiceState.Listening || state is VoiceState.Transcribing || state is VoiceState.Countdown

    internal var onSend: () -> Unit = {}
    internal var requestPermission: () -> Unit = {}
    internal var requestConfig: () -> Unit = {}

    private var session: Job? = null
    private var stopSignal: AtomicBoolean? = null
    private var recognizer: SpeechRecognizer? = null
    private var timer: Job? = null
    private var draft = ""

    fun toggle() {
        when (state) {
            is VoiceState.Listening -> {
                stopSignal?.set(true)
                recognizer?.let { current ->
                    dispatch(VoiceEvent.Transcribe)
                    runCatching { current.stopListening() }.onFailure { dispatch(VoiceEvent.Error(VoiceError.Other)) }
                }
            }
            VoiceState.Transcribing -> cancel()
            else -> start()
        }
    }

    fun start() {
        cancel()
        val mode = CloudVoiceConfigStore.readMode(context)
        val key = if (mode == VoiceRecognitionMode.Doubao) {
            CloudVoiceConfigStore.read(context) ?: return requestConfig()
        } else null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermission()
            return
        }
        draft = composer.text
        dispatch(VoiceEvent.Start)
        if (key == null) startSystem() else startDoubao(key)
    }

    private fun startSystem() {
        val default = Settings.Secure.getString(context.contentResolver, "voice_recognition_service")
            ?.let(ComponentName::unflattenFromString)
        if (default?.packageName == context.packageName || !SpeechRecognizer.isRecognitionAvailable(context)) {
            dispatch(VoiceEvent.Error(VoiceError.NoRecognizer))
            return
        }
        try {
            val current = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = current
            current.setRecognitionListener(object : RecognitionListener {
                private fun text(bundle: Bundle?) =
                    bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

                override fun onReadyForSpeech(params: Bundle?) {
                    if (recognizer === current) dispatch(VoiceEvent.Ready)
                }
                override fun onRmsChanged(rmsdB: Float) {
                    if (recognizer === current) dispatch(VoiceEvent.Level(rmsdB))
                }
                override fun onEndOfSpeech() {
                    if (recognizer === current) dispatch(VoiceEvent.Transcribe)
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    if (recognizer === current) updatePartial(text(partialResults))
                }
                override fun onResults(results: Bundle?) {
                    if (recognizer === current) dispatch(VoiceEvent.Final(text(results)))
                }
                override fun onError(error: Int) {
                    if (recognizer === current) dispatch(VoiceEvent.Error(VoiceMachine.errorOf(error)))
                }
                override fun onBeginningOfSpeech() = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            current.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1))
        } catch (error: SecurityException) {
            dispatch(VoiceEvent.Error(VoiceError.Permission))
        } catch (error: IllegalStateException) {
            dispatch(VoiceEvent.Error(VoiceError.NoRecognizer))
        }
    }

    private fun startDoubao(key: String) {
        val stop = AtomicBoolean()
        stopSignal = stop
        session = scope.launch {
            try {
                val text = DoubaoVoiceService.transcribe(key, stop::get, ::dispatch) { partial ->
                    if (stopSignal === stop) updatePartial(partial)
                }
                dispatch(VoiceEvent.Final(text))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (stopSignal !== stop) return@launch
                val reason = when (error) {
                    is CloudVoiceException -> error.reason
                    is SecurityException -> VoiceError.Permission
                    is IOException -> VoiceError.Network
                    else -> VoiceError.Other
                }
                dispatch(VoiceEvent.Error(reason))
            }
        }
    }

    private fun updatePartial(text: String) {
        if (text.isNotBlank() && (state is VoiceState.Listening || state == VoiceState.Transcribing)) {
            composer.text = VoiceMachine.join(draft, text)
        }
    }

    fun cancel() { if (state != VoiceState.Idle) dispatch(VoiceEvent.Cancel) }

    fun onUserEdit() = cancel()

    internal fun onPermission(granted: Boolean) {
        if (granted) start() else {
            dispatch(VoiceEvent.Start)
            dispatch(VoiceEvent.Error(VoiceError.Permission))
        }
    }

    private fun dispatch(event: VoiceEvent) {
        val effect = machine.on(event)
        state = machine.state
        when (val next = state) {
            is VoiceState.Countdown -> if (event !is VoiceEvent.Tick) {
                composer.text = VoiceMachine.join(draft, next.text)
                runTimer()
            }
            is VoiceState.Failed -> if (event !is VoiceEvent.Tick) runTimer()
            else -> Unit
        }
        when (effect) {
            is VoiceEffect.Send -> onSend()
            VoiceEffect.Release -> {
                session?.cancel()
                session = null
                stopSignal = null
                val current = recognizer
                recognizer = null
                current?.destroy()
            }
            null -> Unit
        }
        if (!active && state !is VoiceState.Failed) {
            timer?.cancel()
            timer = null
        }
    }

    private fun runTimer() {
        timer?.cancel()
        timer = scope.launch {
            if (state is VoiceState.Failed) {
                delay(4_000)
                if (state is VoiceState.Failed) cancel()
                return@launch
            }
            var last = SystemClock.uptimeMillis()
            while (state is VoiceState.Countdown) {
                delay(50)
                val now = SystemClock.uptimeMillis()
                dispatch(VoiceEvent.Tick(now - last))
                last = now
            }
        }
    }
}

@Composable
fun rememberVoiceInput(composer: ComposerState, onSend: () -> Unit): VoiceInput {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val voice = remember(composer, context) { VoiceInput(context.applicationContext, composer, scope) }
    val send by rememberUpdatedState(onSend)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { voice.onPermission(it) }
    voice.onSend = { send() }
    voice.requestPermission = { permission.launch(Manifest.permission.RECORD_AUDIO) }
    voice.requestConfig = { openCloudVoiceSettings(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(voice, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) voice.cancel() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); voice.cancel() }
    }
    return voice
}
