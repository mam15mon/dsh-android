package io.github.mam15mon.dsh.core.data

import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [36])
class CommandPolicyRepositoryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun preservesOtherRulesWhenTogglingEditingAndDeleting() = runBlocking {
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
            override fun getFilesDir() = temp.root
        }
        val repo = CommandPolicyRepository(context)
        assertEquals(CommandPolicy(), repo.policy.first())

        repo.setBuiltIn(BuiltInCommandRule.RemotePayload, false)
        repo.setBuiltIn(BuiltInCommandRule.Power, false)
        repo.setBuiltIn(BuiltInCommandRule.RemotePayload, true)

        val spec = CommandRuleSpec("Block force pushes", "git", listOf("push"), listOf("--force"))
        repo.save(null, spec)
        val rule = repo.policy.first().customRules.single()
        assertTrue(rule.enabled)
        repo.setCustom(rule.id, false)
        repo.save(rule.id, spec.copy(name = "Force pushes"))

        val saved = repo.policy.first()
        assertEquals(setOf("power"), saved.disabledBuiltIns)
        assertEquals(rule.id, saved.customRules.single().id)
        assertEquals("Force pushes", saved.customRules.single().spec.name)
        assertFalse(saved.customRules.single().enabled)
        assertTrue(temp.root.resolve("command-policy.json").isFile)

        repo.delete(rule.id)
        assertEquals(CommandPolicy(disabledBuiltIns = setOf("power")), repo.policy.first())
    }
}
