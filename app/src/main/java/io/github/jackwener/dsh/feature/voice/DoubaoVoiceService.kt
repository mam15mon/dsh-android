package io.github.jackwener.dsh.feature.voice

import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

internal object DoubaoVoiceService {
    private const val TIMEOUT_MS = 15_000L
    private const val MAX_REPLY_BYTES = 256 * 1024
    private const val ENDPOINT = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"
    private const val RESOURCE_ID = "volc.seedasr.sauc.duration"
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()

    suspend fun transcribe(
        key: String,
        stopping: () -> Boolean,
        onEvent: (VoiceEvent) -> Unit,
        onPartial: (String) -> Unit,
    ): String = coroutineScope {
        val replies = Channel<ByteString>(32)
        val request = Request.Builder().url(ENDPOINT)
            .header("X-Api-Key", key)
            .header("X-Api-Resource-Id", RESOURCE_ID)
            .header("X-Api-Request-Id", UUID.randomUUID().toString())
            .build()
        val initial = initFrame()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!webSocket.send(initial)) replies.close(CloudVoiceException(VoiceError.Network))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (replies.trySend(bytes).isFailure) replies.close(CloudVoiceException(VoiceError.CloudService))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                replies.close(CloudVoiceException(VoiceError.CloudService))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val reason = when (response?.code) {
                    400, 401, 403, 404 -> VoiceError.CloudConfig
                    429 -> VoiceError.Busy
                    null -> VoiceError.Network
                    else -> VoiceError.CloudService
                }
                replies.close(CloudVoiceException(reason))
            }
        })
        var finished = false
        try {
            val ack = withTimeoutOrNull(TIMEOUT_MS) { replies.receive() }
                ?: throw CloudVoiceException(VoiceError.Network)
            if (parseResponse(ack.toByteArray()).last) throw CloudVoiceException(VoiceError.CloudService)
            val speechEnded = AtomicBoolean()
            val result = async {
                var text = ""
                for (bytes in replies) {
                    val reply = parseResponse(bytes.toByteArray())
                    reply.text?.let {
                        if (text != it) onPartial(it)
                        text = it
                    }
                    if (reply.last) return@async text
                    if (reply.speechEnded) speechEnded.set(true)
                }
                throw CloudVoiceException(VoiceError.CloudService)
            }
            // Keep one chunk so the last frame carries real audio and the end-of-stream flag.
            var pending: ByteArray? = null
            var sequence = 2
            CloudVoiceService.capture({ stopping() || speechEnded.get() }, onEvent) { samples, count ->
                pending?.let { sendAudio(socket, it, sequence++) }
                val pcm = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (index in 0 until count) pcm.putShort(samples[index])
                pending = pcm.array()
            }
            val last = pending
            if (last == null) {
                result.cancel()
                return@coroutineScope ""
            }
            onEvent(VoiceEvent.Transcribe)
            sendAudio(socket, last, -sequence)
            val text = withTimeoutOrNull(TIMEOUT_MS) { result.await() } ?: throw CloudVoiceException(VoiceError.Network)
            finished = socket.close(1000, null)
            text
        } finally {
            replies.cancel()
            if (!finished) socket.cancel()
        }
    }

    private fun sendAudio(socket: WebSocket, pcm: ByteArray, sequence: Int) {
        if (!socket.send(audioFrame(pcm, sequence))) throw CloudVoiceException(VoiceError.Network)
    }

    internal fun initFrame(): ByteString {
        val json = buildJsonObject {
            put("audio", buildJsonObject {
                put("format", "pcm")
                put("codec", "raw")
                put("rate", CloudVoiceService.SAMPLE_RATE)
                put("bits", 16)
                put("channel", 1)
            })
            put("request", buildJsonObject {
                put("model_name", "bigmodel")
                put("enable_nonstream", true)
                put("show_utterances", true)
                put("end_window_size", 1500)
            })
        }
        return frame(0x11, 1, json.toString().toByteArray())
    }

    internal fun audioFrame(pcm: ByteArray, sequence: Int) = frame(if (sequence < 0) 0x23 else 0x21, sequence, pcm)

    private fun frame(typeAndFlags: Int, sequence: Int, payload: ByteArray): ByteString {
        val compressed = ByteArrayOutputStream().apply {
            GZIPOutputStream(this).use { it.write(payload) }
        }.toByteArray()
        return ByteBuffer.allocate(12 + compressed.size)
            .put(0x11).put(typeAndFlags.toByte()).put(0x11).put(0)
            .putInt(sequence).putInt(compressed.size).put(compressed).array().toByteString()
    }

    internal data class Reply(val text: String?, val last: Boolean, val speechEnded: Boolean = false)

    internal fun parseResponse(bytes: ByteArray): Reply {
        fun invalid(): Nothing = throw CloudVoiceException(VoiceError.CloudService)
        if (bytes.size !in 8..MAX_REPLY_BYTES || bytes[0].toInt() != 0x11) invalid()
        val typeAndFlags = bytes[1].toInt() and 0xff
        val type = typeAndFlags shr 4
        if (type != 9 && type != 15) invalid()
        val frame = ByteBuffer.wrap(bytes).apply { position(4) }
        if (typeAndFlags and 1 != 0) {
            if (frame.remaining() < 8) invalid()
            frame.int // Sequence number
        }
        if (typeAndFlags and 4 != 0) {
            if (frame.remaining() < 8) invalid()
            frame.int // Event ID
        }
        if (type == 15) {
            if (frame.remaining() < 8) invalid()
            throw CloudVoiceException(when (frame.int) {
                45000002 -> VoiceError.NoMatch
                45000151 -> VoiceError.Audio
                45000081 -> VoiceError.Network
                55000031 -> VoiceError.Busy
                else -> VoiceError.CloudService
            })
        }
        val size = frame.int
        if (size != frame.remaining()) invalid()
        val last = typeAndFlags and 2 != 0
        if (size == 0) return Reply(null, last)
        val json = try {
            val payload = bytes.inputStream(frame.position(), size)
            val reader = when (bytes[2].toInt()) {
                0x10 -> payload
                0x11 -> GZIPInputStream(payload)
                else -> invalid()
            }.bufferedReader(Charsets.UTF_8)
            reader.use {
                val text = StringBuilder()
                val buffer = CharArray(4096)
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    if (text.length + count > MAX_REPLY_BYTES) invalid()
                    text.append(buffer, 0, count)
                }
                Json.parseToJsonElement(text.toString()) as? JsonObject ?: invalid()
            }
        } catch (error: IllegalArgumentException) {
            invalid()
        } catch (error: IOException) {
            invalid()
        }
        val result = json["result"] as? JsonObject
        val text = (result?.get("text") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val utterance = (result?.get("utterances") as? JsonArray)?.lastOrNull() as? JsonObject
        val speechEnded = !text.isNullOrBlank() &&
            (utterance?.get("definite") as? JsonPrimitive)?.booleanOrNull == true
        return Reply(text, last, speechEnded)
    }
}
