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

    @Test fun matchesAllTriggerTypesIncludingManualExcludeAndSlots() {
        assertTrue(RuleMatcher.matches(7, Trigger.Manual, DeviceEvent.Manual(7)))
        assertFalse(RuleMatcher.matches(7, Trigger.Manual, DeviceEvent.Manual(8)))
        assertTrue(RuleMatcher.matches(1, Trigger.Wifi(connected = true, ssid = null), DeviceEvent.Wifi(true, null)))
        assertTrue(RuleMatcher.matches(1, Trigger.Wifi(connected = true, ssid = "Home"), DeviceEvent.Wifi(true, "home")))
        assertFalse(RuleMatcher.matches(1, Trigger.Wifi(connected = true, ssid = "Home"), DeviceEvent.Wifi(true, null)))
        assertTrue(RuleMatcher.matches(2, Trigger.BatteryLow(20), DeviceEvent.Battery(percent = 20, previous = 21, charging = false)))
        assertFalse(RuleMatcher.matches(2, Trigger.BatteryLow(20), DeviceEvent.Battery(percent = 20, previous = 21, charging = true)))
        assertFalse(RuleMatcher.matches(2, Trigger.BatteryLow(20), DeviceEvent.Battery(percent = 19, previous = 20, charging = false)))
        assertTrue(RuleMatcher.matches(3, Trigger.Notification("com.chat", "urgent", "spam"), DeviceEvent.Notification("com.chat", "URGENT ping", "hello")))
        assertFalse(RuleMatcher.matches(3, Trigger.Notification("com.chat", "urgent", "spam"), DeviceEvent.Notification("com.chat", "URGENT spam", "hello")))
        assertFalse(RuleMatcher.matches(3, Trigger.Notification("com.chat", "urgent", "spam"), DeviceEvent.Notification("com.other", "URGENT ping", "hello")))

        assertTrue(RuleMatcher.matches(4, Trigger.Call("ringing", "138"), DeviceEvent.Call("ringing", "+8613800138000")))
        assertFalse(RuleMatcher.matches(4, Trigger.Call("ringing", "138"), DeviceEvent.Call("ringing", "10086")))
        assertTrue(RuleMatcher.matches(5, Trigger.Sms("10086", "流量", "推广"), DeviceEvent.Sms("10086", "本月剩余流量 10GB")))
        assertFalse(RuleMatcher.matches(5, Trigger.Sms("10086", "流量", "推广"), DeviceEvent.Sms("10086", "流量推广活动")))
        assertTrue(RuleMatcher.matches(6, Trigger.AppClosed("com.video"), DeviceEvent.AppClosed("com.video")))
        assertTrue(RuleMatcher.matches(7, Trigger.BatteryHigh(80), DeviceEvent.Battery(80, 79, true)))
        assertFalse(RuleMatcher.matches(7, Trigger.BatteryHigh(80), DeviceEvent.Battery(80, 79, false)))
        assertTrue(RuleMatcher.matches(8, Trigger.Network("cellular"), DeviceEvent.Network("cellular")))
        assertTrue(RuleMatcher.matches(9, Trigger.AirplaneMode(true), DeviceEvent.AirplaneMode(true)))
        assertTrue(RuleMatcher.matches(10, Trigger.Boot, DeviceEvent.Boot))
    }

    @Test fun matchRuleSupportsOrExtraTriggersAndIndexedAlarmsGeofences() {
        val spec = RuleSpec(
            name = "Twice daily or headset",
            trigger = Trigger.Time(at = "08:30"),
            extraTriggers = listOf(
                Trigger.Time(at = "18:30"),
                Trigger.Notification("com.chat.a", keyword = "urgent"),
                Trigger.Geofence(31.2, 121.4, enter = true),
            ),
            action = RuleAction.Agent("summarize"),
        )
        assertEquals(0, RuleMatcher.matchRule(5, spec, DeviceEvent.Time(5, triggerIndex = 0))?.index)
        assertEquals(1, RuleMatcher.matchRule(5, spec, DeviceEvent.Time(5, triggerIndex = 1))?.index)
        assertNull(RuleMatcher.matchRule(5, spec, DeviceEvent.Time(5, triggerIndex = 2)))
        assertEquals(2, RuleMatcher.matchRule(5, spec, DeviceEvent.Notification("com.chat.a", "Urgent", "msg"))?.index)
        assertEquals(3, RuleMatcher.matchRule(5, spec, DeviceEvent.Geofence(5, entering = true, triggerIndex = 3))?.index)
        assertNull(RuleMatcher.matchRule(5, spec, DeviceEvent.Geofence(5, entering = true, triggerIndex = 0)))

        assertEquals(12L to 2, RuleMatcher.parseGeofenceRequestId(RuleMatcher.geofenceRequestId(12L, 2)))
        assertEquals(12L to 0, RuleMatcher.parseGeofenceRequestId("12"))
    }

    @Test fun holdsHandlesTimeBatteryWifiScreenAndBluetoothConditions() {
        val monday = LocalDate.of(2026, 11, 23)
        val officeUnlocked = DeviceState(
            batteryPct = 50,
            charging = true,
            wifiConnected = true,
            wifiSsid = "Office",
            screenInteractive = true,
            keyguardLocked = false,
            bluetoothConnected = true,
        )
        val offlineLocked = DeviceState(
            batteryPct = 50,
            charging = false,
            wifiConnected = false,
            wifiSsid = null,
            screenInteractive = false,
            keyguardLocked = true,
            bluetoothConnected = false,
        )
        assertTrue(RuleMatcher.holds(Condition.TimeRange("08:00", "18:00"), LocalTime.of(12, 0), monday, officeUnlocked))
        assertFalse(RuleMatcher.holds(Condition.TimeRange("08:00", "18:00"), LocalTime.of(22, 0), monday, officeUnlocked))
        assertTrue(RuleMatcher.holds(Condition.TimeRange("22:00", "07:00"), LocalTime.of(23, 30), monday, officeUnlocked))
        assertTrue(RuleMatcher.holds(Condition.TimeRange("22:00", "07:00"), LocalTime.of(6, 15), monday, officeUnlocked))
        assertFalse(RuleMatcher.holds(Condition.TimeRange("22:00", "07:00"), LocalTime.of(12, 0), monday, officeUnlocked))
        assertTrue(RuleMatcher.holds(Condition.Days(listOf(1, 5)), LocalTime.NOON, monday, officeUnlocked))
        assertFalse(RuleMatcher.holds(Condition.Days(listOf(2)), LocalTime.NOON, monday, officeUnlocked))
        assertTrue(RuleMatcher.holds(Condition.Battery(min = 20, max = 80), LocalTime.NOON, monday, officeUnlocked))
        assertFalse(RuleMatcher.holds(Condition.Charging(true), LocalTime.NOON, monday, offlineLocked))

        assertTrue(RuleMatcher.holds(Condition.Wifi(true, "office"), LocalTime.NOON, monday, officeUnlocked))
        assertFalse(RuleMatcher.holds(Condition.Wifi(true, "office"), LocalTime.NOON, monday, officeUnlocked.copy(wifiSsid = null)))
        assertTrue(RuleMatcher.holds(Condition.Wifi(false), LocalTime.NOON, monday, offlineLocked))
        assertTrue(RuleMatcher.holds(Condition.Wifi(false, "Office"), LocalTime.NOON, monday, offlineLocked))
        assertFalse(RuleMatcher.holds(Condition.Wifi(false, "Office"), LocalTime.NOON, monday, officeUnlocked.copy(wifiSsid = null)))

        assertTrue(RuleMatcher.holds(Condition.Screen("on"), LocalTime.NOON, monday, officeUnlocked))
        assertTrue(RuleMatcher.holds(Condition.Screen("unlocked"), LocalTime.NOON, monday, officeUnlocked))
        assertFalse(RuleMatcher.holds(Condition.Screen("unlocked"), LocalTime.NOON, monday, officeUnlocked.copy(keyguardLocked = true)))
        assertTrue(RuleMatcher.holds(Condition.Screen("locked"), LocalTime.NOON, monday, officeUnlocked.copy(keyguardLocked = true)))
        assertTrue(RuleMatcher.holds(Condition.Screen("off"), LocalTime.NOON, monday, offlineLocked))

        assertTrue(RuleMatcher.holds(Condition.Bluetooth(true), LocalTime.NOON, monday, officeUnlocked))
        assertFalse(RuleMatcher.holds(Condition.Bluetooth(true), LocalTime.NOON, monday, offlineLocked))

        val richState = officeUnlocked.copy(
            inCall = true,
            foregroundPackage = "com.game",
            networkType = "wifi",
            soundMode = "vibrate",
            dndActive = true,
            headsetPlugged = true,
        )
        assertTrue(RuleMatcher.holds(Condition.InCall(true), LocalTime.NOON, monday, richState))
        assertFalse(RuleMatcher.holds(Condition.InCall(false), LocalTime.NOON, monday, richState))
        assertTrue(RuleMatcher.holds(Condition.ForegroundApp("com.game"), LocalTime.NOON, monday, richState))
        assertFalse(RuleMatcher.holds(Condition.ForegroundApp("com.game", invert = true), LocalTime.NOON, monday, richState))
        assertTrue(RuleMatcher.holds(Condition.Network("wifi"), LocalTime.NOON, monday, richState))
        assertTrue(RuleMatcher.holds(Condition.SoundMode("vibrate"), LocalTime.NOON, monday, richState))
        assertTrue(RuleMatcher.holds(Condition.SoundMode("dnd"), LocalTime.NOON, monday, richState))
        assertTrue(RuleMatcher.holds(Condition.Headset(true), LocalTime.NOON, monday, richState))
    }

    @Test fun nextFireComputesSingleAndEarliestMultiTimeTrigger() {
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

        val multiTimeSpec = RuleSpec(
            name = "Multi time",
            trigger = Trigger.Time(at = "18:30"),
            extraTriggers = listOf(Trigger.Time(at = "09:15")),
            action = RuleAction.Steps(listOf(Step("sound_mode"))),
        )
        assertEquals(
            ZonedDateTime.of(2026, 11, 23, 9, 15, 0, 0, utc).toInstant().toEpochMilli(),
            RuleMatcher.nextFire(multiTimeSpec, 0L, null, monMorning, utc),
        )
    }

    @Test fun monitorMaskCoversPrimaryAndExtraTriggers() {
        val stepAction = RuleAction.Steps(listOf(Step("sound_mode")))
        assertEquals(0, RuleMatcher.monitorMask(listOf(
            RuleSpec("t", Trigger.Time(at = "07:00"), action = stepAction),
            RuleSpec("m", Trigger.Manual, action = stepAction),
            RuleSpec("n", Trigger.Notification(), action = stepAction),
        )))
        val expected = RuleMatcher.FLAG_WIFI or RuleMatcher.FLAG_POWER or RuleMatcher.FLAG_HEADSET or
            RuleMatcher.FLAG_CALL or RuleMatcher.FLAG_NETWORK or RuleMatcher.FLAG_AIRPLANE or RuleMatcher.FLAG_BATTERY
        assertEquals(expected, RuleMatcher.monitorMask(listOf(
            RuleSpec("w", Trigger.Wifi(true), extraTriggers = listOf(Trigger.Headset(true), Trigger.Call("ringing")), action = stepAction),
            RuleSpec("p", Trigger.Power(false), extraTriggers = listOf(Trigger.Network("wifi"), Trigger.AirplaneMode(true), Trigger.BatteryHigh(80)), action = stepAction),
        )))
    }

    @Test fun describeEventFormatsReadableTextForAllEventsAndMatchedTrigger() {
        val spec = RuleSpec(
            "demo",
            Trigger.Time(at = "08:00"),
            extraTriggers = listOf(Trigger.Time(at = "20:00")),
            action = RuleAction.Agent("go"),
        )
        assertEquals("manual run", RuleMatcher.describeEvent(spec, DeviceEvent.Manual(1), readableNotification = true))
        assertEquals("scheduled time (20:00)", RuleMatcher.describeEvent(spec, DeviceEvent.Time(1, 1), readableNotification = true, matchedTrigger = Trigger.Time(at = "20:00")))
        assertEquals("app opened (com.example)", RuleMatcher.describeEvent(spec, DeviceEvent.AppOpened("com.example"), readableNotification = true))
        assertEquals("entered geofence", RuleMatcher.describeEvent(spec, DeviceEvent.Geofence(1, true), readableNotification = true))
        assertTrue(RuleMatcher.describeEvent(spec, DeviceEvent.Notification("a", "hi", "body"), readableNotification = false).contains("content hidden"))
    }

    @Test fun ruleSpecSerializationRemainsBackwardsCompatibleAndSupportsMultiTriggerConditions() {
        val legacyJson = """{"name":"Legacy","trigger":{"type":"power","connected":true},"action":{"type":"steps","steps":[{"tool":"sound_mode"}]}}"""
        val decoded = RpcJson.decodeFromString(RuleSpec.serializer(), legacyJson)
        assertTrue(decoded.extraTriggers.isEmpty())
        assertEquals(60, decoded.cooldownSeconds)
        assertTrue(decoded.notifyOnSuccess)
        assertNull(decoded.modelId)
        assertNull(decoded.workspace)

        val multi = RuleSpec(
            name = "Work alert",
            trigger = Trigger.Notification(packageName = "com.wecom", keyword = "urgent", excludeKeyword = "bot"),
            extraTriggers = listOf(Trigger.Notification(packageName = "com.dingtalk", keyword = "urgent")),
            conditions = listOf(Condition.Wifi(true, "Office"), Condition.Screen("unlocked"), Condition.Bluetooth(true)),
            action = RuleAction.Agent("summarize"),
            cooldownSeconds = 0,
            notifyOnSuccess = false,
            modelProvider = "",
            modelId = "deepseek-flash",
            workspace = "",
        )
        val encoded = RpcJson.encodeToString(RuleSpec.serializer(), multi)
        val roundTrip = RpcJson.decodeFromString(RuleSpec.serializer(), encoded)
        assertEquals(multi, roundTrip)
    }
}
