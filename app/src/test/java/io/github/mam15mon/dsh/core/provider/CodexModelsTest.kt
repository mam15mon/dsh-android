package io.github.mam15mon.dsh.core.provider

import org.junit.Assert.assertEquals
import org.junit.Test

/** Codex /models uses slug as its request model identifier, not its display name. */
class CodexModelsTest {
    @Test fun codexCatalogRetainsIdsAndCapabilities() {
        val models = parseModels("""{"models":[
            {"slug":"gpt-5-codex","display_name":"GPT-5 Codex","context_window":272000,"input_modalities":["text","image"]},
            {"slug":"gpt-5-mini","display_name":"GPT-5 Mini","input_modalities":["text"]}
        ]}""")
        assertEquals(listOf("gpt-5-codex", "gpt-5-mini"), models.map { it.id })
        assertEquals(272000L, models[0].contextWindow)
        assertEquals(true, models[0].images)
        assertEquals(false, models[1].images)
    }

    @Test fun slugTakesPrecedenceOverADisplayName() {
        assertEquals(listOf("gpt-5-codex"), parseModelIds("""{"models":[{"slug":"gpt-5-codex","name":"GPT-5 Codex"}]}"""))
    }

    @Test fun openAiIdsStillTakePrecedence() {
        assertEquals(listOf("route-model"), parseModelIds("""{"data":[{"id":"route-model","slug":"alias","name":"Display"}]}"""))
    }
}
