package io.github.mam15mon.dsh.core.trigger

import io.github.mam15mon.dsh.core.engine.RpcJson
import io.github.mam15mon.dsh.core.model.Condition
import io.github.mam15mon.dsh.core.model.RuleAction
import io.github.mam15mon.dsh.core.model.RuleSpec
import io.github.mam15mon.dsh.core.model.Step
import io.github.mam15mon.dsh.core.model.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class RuleMatcherTest {
    private val utc = ZoneId.of("UTC")

    @Test fun matchesAllTriggerTypesIncludingManualAndWifiWithoutSsid() {
        assertTrue(RuleMatcher.matches(7, Trigger.Manual, DeviceEvent.Manual(7)))
        assertFalse(RuleMatcher.matches(7, Trigger.Manual, DeviceEvent.Manual(8)))
        assertTrue(RuleMatcher.matches(1, Trigger.Wifi(connected = true, ssid = null), DeviceEvent.Wifi(true, null)))
        assertTrue(RuleMatcher.matches(1, Trigger.Wifi(connected = true, ssid = "Home"), DeviceEvent.Wifi(true, "home")))
        assertFalse(RuleMatcher.matches(1, Trigger.Wifi(connected = true, ssid = "Home"), DeviceEvent.Wifi(true, null)))
        assertTrue(RuleMatcher.matches(2, Trigger.BatteryLow(20), DeviceEvent.Battery(percent = 20, previous = 21, charging = false)))
        assertFalse(RuleMatcher.matches(2, Trigger.BatteryLow(20), DeviceEvent.Battery(percent = 20, previous = 21, charging = true)))
        assertFalse(RuleMatcher.matches(2, Trigger.BatteryLow(20), DeviceEvent.Battery(percent = 19, previous = 20, charging = false)))
        assertTrue(RuleMatcher.matches(3, Trigger.Notification("com.chat", "urgent"), DeviceEvent.Notification("com.chat", "URGENT ping", "hello")))
        assertFalse(RuleMatcher.matches(3, Trigger.Notification("com.chat", "urgent"), DeviceEvent.Notification("com.other", "URGENT ping", "hello")))
    }

    @Test fun holdsHandlesNormalAndCrossMidnightTimeRanges() {
        val monday = LocalDate.of(2026, 11, 23)
        assertTrue(RuleMatcher.holds(Condition.TimeRange("08:00", "18:00"), LocalTime.of(12, 0), monday, 80, false))
        assertFalse(RuleMatcher.holds(Condition.TimeRange("08:00", "18:00"), LocalTime.of(22, 0), monday, 80, false))
        assertTrue(RuleMatcher.holds(Condition.TimeRange("22:00", "07:00"), LocalTime.of(23, 30), monday, 80, false))
        assertTrue(RuleMatcher.holds(Condition.TimeRange("22:00", "07:00"), LocalTime.of(6, 15), monday, 80, false))
        assertFalse(RuleMatcher.holds(Condition.TimeRange("22:00", "07:00"), LocalTime.of(12, 0), monday, 80, false))
        assertTrue(RuleMatcher.holds(Condition.Days(listOf(1, 5)), LocalTime.NOON, monday, 50, true))
        assertFalse(RuleMatcher.holds(Condition.Days(listOf(2)), LocalTime.NOON, monday, 50, true))
        assertTrue(RuleMatcher.holds(Condition.Battery(min = 20, max = 80), LocalTime.NOON, monday, 50, true))
        assertFalse(RuleMatcher.holds(Condition.Charging(true), LocalTime.NOON, monday, 50, false))
    }

    @Test fun nextFireComputesScheduledPeriodicAndOneShotTimes() {
        val monMorning = ZonedDateTime.of(2026, 11, 23, 8, 0, 0, 0, utc).toInstant().toEpochMilli()
        val friSameTime = RuleMatcher.nextFire(Trigger.Time(at = "08:00", days = listOf(5)), 0L, null, monMorning, utc)
        assertNotNull(friSameTime)
        assertEquals(
            ZonedDateTime.of(2026, 11, 27, 8, 0, 0, 0, utc).toInstant().toEpochMilli(),
            friSameTime!!,
        )

        val every = RuleMatcher.nextFire(Trigger.Time(everyMinutes = 30), createdAt = monMorning, lastFiredAt = null, nowMs = monMorning + 60_000L, zone = utc)
        assertEquals(monMorning + 30 * 60_000L, every)

        val overdue = RuleMatcher.nextFire(Trigger.Time(everyMinutes = 30), createdAt = monMorning, lastFiredAt = null, nowMs = monMorning + 60 * 60_000L, zone = utc)
        assertEquals(monMorning + 61 * 60_000L, overdue)

        val futureOnce = Instant.ofEpochMilli(monMorning + 10_000L).toEpochMilli()
        assertEquals(futureOnce, RuleMatcher.nextFire(Trigger.Time(onceAtEpochMs = futureOnce), 0L, null, monMorning, utc))
        assertNull(RuleMatcher.nextFire(Trigger.Time(onceAtEpochMs = monMorning - 1), 0L, null, monMorning, utc))
    }

    @Test fun monitorMaskOnlyEnablesNeededBroadcastCategories() {
        val stepAction = RuleAction.Steps(listOf(Step("sound_mode")))
        assertEquals(0, RuleMatcher.monitorMask(listOf(
            RuleSpec("t", Trigger.Time(at = "07:00"), action = stepAction),
            RuleSpec("m", Trigger.Manual, action = stepAction),
            RuleSpec("n", Trigger.Notification(), action = stepAction),
        )))
        val expected = RuleMatcher.FLAG_WIFI or RuleMatcher.FLAG_POWER
        assertEquals(expected, RuleMatcher.monitorMask(listOf(
            RuleSpec("w", Trigger.Wifi(true), action = stepAction),
            RuleSpec("p", Trigger.Power(false), action = stepAction),
        )))
    }

    @Test fun describeEventFormatsReadableTextForAllEvents() {
        val spec = RuleSpec("demo", Trigger.Manual, action = RuleAction.Agent("go"))
        assertEquals("manual run", RuleMatcher.describeEvent(spec, DeviceEvent.Manual(1), readableNotification = true))
        assertEquals("app opened (com.example)", RuleMatcher.describeEvent(spec, DeviceEvent.AppOpened("com.example"), readableNotification = true))
        assertEquals("entered geofence", RuleMatcher.describeEvent(spec, DeviceEvent.Geofence(1, true), readableNotification = true))
        assertTrue(RuleMatcher.describeEvent(spec, DeviceEvent.Notification("a", "hi", "body"), readableNotification = false).contains("content hidden"))
    }

    @Test fun ruleSpecSerializationRemainsBackwardsCompatibleAndSupportsManual() {
        val legacyJson = """{"name":"Legacy","trigger":{"type":"power","connected":true},"action":{"type":"steps","steps":[{"tool":"sound_mode"}]}}"""
        val decoded = RpcJson.decodeFromString(RuleSpec.serializer(), legacyJson)
        assertEquals(60, decoded.cooldownSeconds)
        assertTrue(decoded.notifyOnSuccess)
        assertNull(decoded.modelId)
        assertNull(decoded.workspace)

        val manual = RuleSpec(
            name = "Daily recap",
            trigger = Trigger.Manual,
            action = RuleAction.Agent("summarize"),
            cooldownSeconds = 0,
            notifyOnSuccess = false,
            modelProvider = "",
            modelId = "deepseek-flash",
            workspace = "",
        )
        val encoded = RpcJson.encodeToString(RuleSpec.serializer(), manual)
        val roundTrip = RpcJson.decodeFromString(RuleSpec.serializer(), encoded)
        assertEquals(manual, roundTrip)
    }
}
