package io.github.mam15mon.dsh.feature.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class CloudVoiceMachineTest {
    @Test fun keepsSavedDoubaoKeyAndRejectsRemovedProviders() {
        assertEquals("doubao-key", CloudVoiceConfigStore.decodeKey(
            """{"provider":"Doubao","endpoint":"wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async","model":"volc.seedasr.sauc.duration","key":"doubao-key"}"""
        ))
        assertEquals("doubao-key", CloudVoiceConfigStore.decodeKey("""{"provider":"Doubao","key":"doubao-key"}"""))
        assertNull(CloudVoiceConfigStore.decodeKey("""{"provider":"MiMo","key":"mimo-key"}"""))
        assertNull(CloudVoiceConfigStore.decodeKey("""{"provider":"Custom","key":"custom-key"}"""))
        assertNull(CloudVoiceConfigStore.decodeKey("""{"key":"old-key"}"""))
        assertNull(CloudVoiceConfigStore.decodeKey("""{"provider":"Doubao","key":null}"""))
        assertNull(CloudVoiceConfigStore.validateKey(""))
    }

    @Test fun buildsDoubaoInitAndAudioFrames() {
        fun payload(frame: ByteArray, header: ByteArray, sequence: Int): ByteArray {
            assertArrayEquals(header, frame.copyOf(4))
            val fields = ByteBuffer.wrap(frame).apply { position(4) }
            assertEquals(sequence, fields.int)
            assertEquals(frame.size - 12, fields.int)
            return GZIPInputStream(frame.inputStream(12, frame.size - 12)).use { it.readBytes() }
        }
        val init = payload(DoubaoVoiceService.initFrame().toByteArray(), byteArrayOf(0x11, 0x11, 0x11, 0), 1)
        val request = Json.parseToJsonElement(init.decodeToString()).jsonObject
        assertEquals("""{"format":"pcm","codec":"raw","rate":16000,"bits":16,"channel":1}""", request["audio"].toString())
        assertEquals("""{"model_name":"bigmodel","enable_nonstream":true,"show_utterances":true,"end_window_size":1500}""", request["request"].toString())
        val pcm = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(pcm, payload(DoubaoVoiceService.audioFrame(pcm, 2).toByteArray(), byteArrayOf(0x11, 0x21, 0x11, 0), 2))
        assertArrayEquals(pcm, payload(DoubaoVoiceService.audioFrame(pcm, -3).toByteArray(), byteArrayOf(0x11, 0x23, 0x11, 0), -3))
    }

    @Test fun parsesDoubaoPartialAndFinalRepliesAndRejectsTruncatedFrames() {
        fun reply(last: Boolean, json: String): ByteArray {
            val payload = ByteArrayOutputStream().apply {
                GZIPOutputStream(this).use { it.write(json.toByteArray()) }
            }.toByteArray()
            return ByteBuffer.allocate(12 + payload.size)
                .put(byteArrayOf(0x11, if (last) 0x93.toByte() else 0x91.toByte(), 0x11, 0))
                .putInt(if (last) -2 else 1).putInt(payload.size).put(payload).array()
        }
        val partial = reply(false, """{"result":{"text":"打开 Git"}}""")
        val final = reply(true, """{"result":{"text":"打开 GitHub"}}""")
        assertEquals(DoubaoVoiceService.Reply("打开 Git", false), DoubaoVoiceService.parseResponse(partial))
        val talking = reply(false, """{"result":{"text":"打开 GitHub 看一下","utterances":[{"definite":true},{"definite":false}]}}""")
        assertFalse(DoubaoVoiceService.parseResponse(talking).speechEnded)
        val confirmed = reply(false, """{"result":{"text":"打开 GitHub","utterances":[{"definite":true}]}}""")
        assertEquals(DoubaoVoiceService.Reply("打开 GitHub", false, speechEnded = true), DoubaoVoiceService.parseResponse(confirmed))
        assertEquals(DoubaoVoiceService.Reply("打开 GitHub", true), DoubaoVoiceService.parseResponse(final))
        assertEquals(DoubaoVoiceService.Reply(null, false), DoubaoVoiceService.parseResponse(reply(false, "{}")))
        val emptyFinal = ByteBuffer.allocate(16)
            .put(byteArrayOf(0x11, 0x97.toByte(), 0x11, 0)).putInt(-2).putInt(0).putInt(0).array()
        assertEquals(DoubaoVoiceService.Reply(null, true), DoubaoVoiceService.parseResponse(emptyFinal))
        assertEquals(VoiceError.CloudService, assertThrows(CloudVoiceException::class.java) {
            DoubaoVoiceService.parseResponse(final.copyOf(final.size - 1))
        }.reason)
        val error = ByteBuffer.allocate(12)
            .put(byteArrayOf(0x11, 0xf0.toByte(), 0x10, 0)).putInt(45000002).putInt(0).array()
        assertEquals(VoiceError.NoMatch, assertThrows(CloudVoiceException::class.java) {
            DoubaoVoiceService.parseResponse(error)
        }.reason)
    }

    @Test fun cancellationDuringUploadDropsLateResults() {
        val machine = VoiceMachine()
        machine.on(VoiceEvent.Start)
        machine.on(VoiceEvent.Transcribe)
        assertEquals(VoiceState.Transcribing, machine.state)
        assertEquals(VoiceEffect.Release, machine.on(VoiceEvent.Cancel))
        assertNull(machine.on(VoiceEvent.Final("late text")))
        assertEquals(VoiceState.Idle, machine.state)
    }

    @Test fun cloudResultStartsTheSendCountdownWithTheCompleteText() {
        val machine = VoiceMachine()
        machine.on(VoiceEvent.Start)
        machine.on(VoiceEvent.Transcribe)
        assertEquals(VoiceEffect.Release, machine.on(VoiceEvent.Final("  GitHub 上的 PR  ")))
        assertEquals("GitHub 上的 PR", (machine.state as VoiceState.Countdown).text)
    }
}
