package io.github.mam15mon.dsh.core.provider

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkBuddyTest {
    private fun json(s: String) = Json.parseToJsonElement(s)

    @Test fun bothBuildsArePresetsOnTheEnginePlugin() {
        val cn = presetOf("workbuddy")!!
        val ai = presetOf("workbuddy-ai")!!
        assertEquals("workbuddy", cn.workbuddy)
        assertEquals("workbuddy-ai", ai.workbuddy)
        // the key is optional (a browser sign-in is the other way), the address is the build's API root
        assertTrue(cn.keyOptional && ai.keyOptional)
        assertEquals("https://copilot.tencent.com/v2", cn.baseUrl)
        assertEquals("https://www.workbuddy.ai/v2", ai.baseUrl)
        assertNull(presetOf("openai")!!.workbuddy)
    }

    @Test fun aSignedInRouteNeedsNoKeyInThePatchLayer() {
        val p = ProviderConfig("workbuddy", "workbuddy", "WorkBuddy", "https://copilot.tencent.com/v2", listOf("glm-5.3"))
        val patch = renderProfilePatch(listOf(p), webSearch = true)
        assertTrue(patch.contains("\"baseURL\":\"https://copilot.tencent.com/v2\""))
        assertTrue(patch.contains("\"interfaceType\":\"openai-chat-completions\""))
        // no key stored: no apiKeyEnv, so the engine plugin signs the chat with the stored sign-in
        assertFalse(patch.contains("apiKeyEnv"))
        assertTrue(renderProfilePatch(listOf(p.copy(hasKey = true)), webSearch = true).contains("\"apiKeyEnv\":\"DSH_ANDROID_KEY_WORKBUDDY\""))
    }

    @Test fun stateComesFromTheEnginesAnswer() {
        val s = parseWbState(json("""{"site":"workbuddy","signedIn":true,"name":"Ada","expired":false,"attempt":{"id":"a1","phase":"done","url":"https://x","error":null,"name":"Ada"}}"""))
        assertTrue(s.signedIn)
        assertEquals("Ada", s.name)
        assertEquals("done", s.attempt?.phase)
        assertFalse(s.attempt!!.waiting)
        val waiting = parseWbState(json("""{"signedIn":false,"attempt":{"id":"a2","phase":"waiting","url":"https://login.example/p"}}"""))
        assertTrue(waiting.attempt!!.waiting)
        assertEquals("https://login.example/p", waiting.attempt?.url)
        assertEquals(WbState(), parseWbState(json("null")))
    }

    @Test fun modelsKeepTheConfigsLimitsAndImageFlag() {
        val list = parseWbModels(json("""{"models":[{"id":"glm-5.3","name":"GLM","context":1000000,"output":32000,"images":true},{"id":"kimi-k3","context":256000},{"id":"","name":"x"},{"id":"glm-5.3"}]}"""))
        assertEquals(listOf("glm-5.3", "kimi-k3"), list.map { it.id })
        assertEquals(1_000_000L, list[0].contextWindow)
        assertEquals(32_000, list[0].maxTokens)
        assertEquals(true, list[0].images)
        assertNull(list[1].maxTokens)
        assertEquals(emptyList<RemoteModel>(), parseWbModels(json("{}")))
    }

    @Test fun theEnginesProbeAnswerIsReadLikeEveryOtherProbe() {
        val pass = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"name\":\"dsh_connection_check\"}}]}}]}\n\ndata: [DONE]\n"
        assertNull(wbProbeFailure(200, pass))
        assertTrue(wbProbeFailure(200, "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\ndata: [DONE]\n") is VerifyFailure.NoToolCall)
        assertTrue(wbProbeFailure(401, "{\"error\":{\"message\":\"WorkBuddy: sign in first\"}}") is VerifyFailure.KeyInvalid)
        assertTrue(wbProbeFailure(429, "{\"code\":429,\"msg\":\"too many requests\"}") is VerifyFailure.Quota)
    }

    @Test fun accountsCarryTheirCreditsAndRest() {
        val s = parseWbState(json("""{"signedIn":true,"name":"A","expired":false,"accounts":[
            {"id":"u-a|","name":"A","expired":false,"current":true,"resting":null,"plan":"Free","left":479.5,"total":600,"used":120.5,"creditsError":null},
            {"id":"u-b|","name":"B","expired":false,"current":false,"resting":{"until":1700000000000,"why":"rate"},"plan":null,"left":null,"total":null,"used":null,"creditsError":"boom"},
            {"id":"u-c|","name":"C","expired":true}]}"""))
        assertEquals(listOf("A", "B", "C"), s.accounts.map { it.name })
        val a = s.accounts[0]
        assertTrue(a.current)
        assertEquals(479.5, a.left!!, 0.0)
        assertEquals(600.0, a.total!!, 0.0)
        assertNull(a.restingWhy)
        assertEquals("rate", s.accounts[1].restingWhy)
        assertEquals(1_700_000_000_000L, s.accounts[1].restingUntil)
        assertEquals("boom", s.accounts[1].creditsError)
        assertTrue(s.accounts[2].expired)
    }

    @Test fun creditCountsAreWholeOrTwoPlaces() {
        assertEquals("600", wbCompact(600.0))
        assertEquals("479.5", wbCompact(479.5))
        assertEquals("438.88", wbCompact(438.88000002))
        assertEquals("0", wbCompact(0.0))
    }

    @Test fun reasoningComesFromTheConfigsLevels() {
        // not said: the engine's family table decides
        assertNull(wbReasoningDecl(null))
        // said to have none
        assertEquals(ReasoningDecl(ReasoningControl.None), wbReasoningDecl(emptyList()))
        // the gateway's "none" (thinking off) is DSH's lowest level, in DSH's own order
        assertEquals(ReasoningDecl(ReasoningControl.Effort, listOf("minimal", "low", "high", "max")), wbReasoningDecl(listOf("none", "low", "high", "max")))
        assertEquals(ReasoningDecl(ReasoningControl.Effort, listOf("high", "xhigh")), wbReasoningDecl(listOf("high", "xhigh", "unheard-of")))
    }

    @Test fun modelsCarryTheirLevelsAndDefault() {
        val list = parseWbModels(json("""{"models":[{"id":"glm-5.3","efforts":["none","low","high"],"effort":"high"},{"id":"plain","efforts":[]},{"id":"builtin"}]}"""))
        assertEquals(listOf("none", "low", "high"), list[0].efforts)
        assertEquals("high", list[0].effort)
        assertEquals(emptyList<String>(), list[1].efforts)
        assertNull(list[2].efforts)
    }
}
