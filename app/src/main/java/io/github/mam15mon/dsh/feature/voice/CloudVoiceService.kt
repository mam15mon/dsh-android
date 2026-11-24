package io.github.mam15mon.dsh.feature.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import io.github.mam15mon.dsh.core.data.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import kotlin.math.log10

internal enum class VoiceRecognitionMode { Doubao, System }

internal object CloudVoiceConfigStore {
    private const val SLOT = "cloud-voice"

    fun readMode(context: Context): VoiceRecognitionMode =
        if (preferences(context).getBoolean("use_system", false)) VoiceRecognitionMode.System else VoiceRecognitionMode.Doubao

    fun writeMode(context: Context, mode: VoiceRecognitionMode) {
        preferences(context).edit().putBoolean("use_system", mode == VoiceRecognitionMode.System).apply()
    }

    private fun preferences(context: Context) = context.getSharedPreferences(SLOT, Context.MODE_PRIVATE)

    @Synchronized
    fun read(context: Context): String? = SecretStore(context).read(SLOT)?.let(::decodeKey)

    internal fun decodeKey(stored: String): String? = runCatching {
        val data = Json.parseToJsonElement(stored).jsonObject
        if ((data["provider"] as? JsonPrimitive)?.content != "Doubao") return null
        val key = data["key"] as? JsonPrimitive ?: return null
        if (!key.isString) return null
        validateKey(key.content)
    }.getOrNull()

    internal fun validateKey(key: String): String? =
        key.trim().takeIf { it.isNotEmpty() && it.length <= 4096 && it.all { char -> char in '!'..'~' } }

    @Synchronized
    fun write(context: Context, key: String) {
        val data = buildJsonObject {
            put("provider", "Doubao")
            put("key", key)
        }
        SecretStore(context).write(SLOT, data.toString())
    }
}

internal class CloudVoiceException(val reason: VoiceError) : IOException()

internal object CloudVoiceService {
    internal const val SAMPLE_RATE = 16_000
    private const val MAX_SAMPLES = SAMPLE_RATE * 60
    private val microphone = Mutex()

    @SuppressLint("MissingPermission") // VoiceInput requests microphone permission before opening a session.
    suspend fun capture(stopping: () -> Boolean, onEvent: (VoiceEvent) -> Unit,
                        frames: (ShortArray, Int) -> Unit) = microphone.withLock {
        withContext(Dispatchers.IO) {
            if (stopping()) return@withContext
            val channels = AudioFormat.CHANNEL_IN_MONO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, channels, encoding)
            if (minimum <= 0) throw CloudVoiceException(VoiceError.Audio)
            val recorder = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, channels, encoding, minimum * 2)
            } catch (error: IllegalArgumentException) {
                throw CloudVoiceException(VoiceError.Audio)
            }
            try {
                if (recorder.state != AudioRecord.STATE_INITIALIZED) throw CloudVoiceException(VoiceError.Audio)
                recorder.startRecording()
                if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw CloudVoiceException(VoiceError.Audio)
                withContext(Dispatchers.Main.immediate) { onEvent(VoiceEvent.Ready) }
                val buffer = ShortArray(SAMPLE_RATE / 5)
                var captured = 0
                while (!stopping() && captured < MAX_SAMPLES) {
                    ensureActive()
                    val count = recorder.read(buffer, 0, minOf(buffer.size, MAX_SAMPLES - captured))
                    if (count < 0) throw CloudVoiceException(VoiceError.Audio)
                    if (count == 0) continue
                    var energy = 0.0
                    for (index in 0 until count) {
                        val sample = buffer[index]
                        val normalized = sample / 32768.0
                        energy += normalized * normalized
                    }
                    captured += count
                    frames(buffer, count)
                    val dbfs = 10 * log10((energy / count).coerceAtLeast(1e-10))
                    val level = ((dbfs + 60) / 4 - 2).toFloat()
                    withContext(Dispatchers.Main.immediate) { onEvent(VoiceEvent.Level(level)) }
                }
            } finally {
                recorder.release()
            }
            ensureActive()
        }
    }
}
