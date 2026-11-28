package io.github.mam15mon.dsh.core.trigger

import io.github.mam15mon.dsh.core.model.Condition
import io.github.mam15mon.dsh.core.model.RuleAction
import io.github.mam15mon.dsh.core.model.RuleSpec
import io.github.mam15mon.dsh.core.model.Step
import io.github.mam15mon.dsh.core.model.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RuleValidationTest {
    private val tools = setOf("sound_mode", "media_control")
    private fun steps(vararg t: String) = RuleAction.Steps(t.map { Step(it) })

    @Test fun normalizesLenientTimesAndAddsStepTools() {
        val spec = validateRule(RuleSpec("m", Trigger.Time(at = "7:30", days = listOf(1, 5)),
            listOf(Condition.TimeRange("9:00", "18:05")), steps("sound_mode")), tools)
        assertEquals("07:30", (spec.trigger as Trigger.Time).at)
        assertEquals(Condition.TimeRange("09:00", "18:05"), spec.conditions[0])
        assertEquals(listOf("sound_mode"), spec.allowedTools)
    }

    @Test fun rejectsBadSpecsWithEveryProblem() {
        try {
            validateRule(RuleSpec("", Trigger.Time(at = "24:00", days = listOf(0, 8)), listOf(Condition.TimeRange("7点", "9:00")), steps("nope")), tools)
            fail("expected RuleSpecException")
        } catch (e: RuleSpecException) {
            val all = e.problems.joinToString("\n")
            listOf("name", "trigger.at", "days", "time_range.from", "nope").forEach { assertTrue("missing '$it' in $all", it in all) }
        }
    }

    @Test fun rejectsScreenEventAndShortIntervals() {
        listOf(
            RuleSpec("a", Trigger.Screen("unlocked"), action = RuleAction.Agent("x")),
            RuleSpec("b", Trigger.Time(everyMinutes = 5), action = RuleAction.Agent("x")),
            RuleSpec("c", Trigger.Time(), action = RuleAction.Agent("x")),
        ).forEach { s -> try { validateRule(s, tools); fail("accepted ${s.name}") } catch (_: RuleSpecException) {} }
    }

    @Test fun parserNeverThrows() {
        assertNull(RuleTime.parse("7点"))
        assertNull(RuleTime.parse("24:00"))
        assertEquals("23:59", RuleTime.parse("23:59")?.let(RuleTime::format))
        assertEquals("08:05", RuleTime.parse("8：05")?.let(RuleTime::format))
    }

    @Test fun adbShellAndPhoneShAreNeverDirectSteps() {
        for (tool in listOf("adb_shell", "phone_sh")) {
            val withTool = tools + tool
            try {
                validateRule(RuleSpec("x", Trigger.Time(at = "07:00"), emptyList(), steps(tool)), withTool)
                fail("accepted $tool as a step")
            } catch (e: RuleSpecException) {
                assertTrue(e.problems.toString(), e.problems.any { "$tool cannot be a direct step" in it })
            }
            // As a tool an agent action may use, it is fine (the engine checks each command).
            val agent = validateRule(RuleSpec("y", Trigger.Time(at = "07:00"), emptyList(), RuleAction.Agent("check battery"), listOf(tool)), withTool)
            assertEquals(listOf(tool), agent.allowedTools)
        }
    }

    @Test fun validatesManualCooldownModelAndWorkspace() {
        val spec = validateRule(
            RuleSpec(
                name = "Check repo",
                trigger = Trigger.Manual,
                action = RuleAction.Agent("git status"),
                cooldownSeconds = 0,
                notifyOnSuccess = false,
                modelProvider = " ",
                modelId = " deepseek-flash ",
                workspace = " /sdcard/proj/ ",
            ),
            tools,
        )
        assertEquals("", spec.modelProvider)
        assertEquals("deepseek-flash", spec.modelId)
        assertEquals("/sdcard/proj", spec.workspace)

        listOf(
            spec.copy(cooldownSeconds = -1),
            spec.copy(cooldownSeconds = 86_401),
            spec.copy(workspace = "relative/path"),
        ).forEach { bad ->
            try {
                validateRule(bad, tools)
                fail("accepted invalid spec $bad")
            } catch (_: RuleSpecException) {}
        }
    }

    @Test fun validatesExtraTriggersNewConditionsAndMaxLimit() {
        val spec = validateRule(
            RuleSpec(
                name = "Morning or evening",
                trigger = Trigger.Time(at = "8:30"),
                extraTriggers = listOf(
                    Trigger.Time(at = "18:05"),
                    Trigger.Notification(packageName = " ", keyword = " vip ", excludeKeyword = " ad "),
                ),
                conditions = listOf(
                    Condition.Wifi(connected = true, ssid = " Home "),
                    Condition.Screen("unlocked"),
                    Condition.Bluetooth(false),
                ),
                action = RuleAction.Agent("report"),
            ),
            tools,
        )
        assertEquals("08:30", (spec.trigger as Trigger.Time).at)
        assertEquals("18:05", (spec.extraTriggers[0] as Trigger.Time).at)
        assertEquals(Trigger.Notification(null, "vip", "ad"), spec.extraTriggers[1])
        assertEquals(Condition.Wifi(true, "Home"), spec.conditions[0])

        val tooMany = spec.copy(extraTriggers = List(MAX_RULE_TRIGGERS) { Trigger.Manual })
        val badScreen = spec.copy(conditions = listOf(Condition.Screen("sleep")))
        listOf(tooMany, badScreen).forEach { bad ->
            try {
                validateRule(bad, tools)
                fail("accepted invalid multi-trigger/condition $bad")
            } catch (_: RuleSpecException) {}
        }
    }
}
