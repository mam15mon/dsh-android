package io.github.mam15mon.dsh.core.trigger

import io.github.mam15mon.dsh.core.model.Condition
import io.github.mam15mon.dsh.core.model.RuleSpec
import io.github.mam15mon.dsh.core.model.Trigger
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Snapshot of current device state used when evaluating [Condition]s. */
data class DeviceState(
    val batteryPct: Int = 0,
    val charging: Boolean = false,
    val wifiConnected: Boolean = false,
    val wifiSsid: String? = null,
    val screenInteractive: Boolean = true,
    val keyguardLocked: Boolean = false,
    val bluetoothConnected: Boolean = false,
    val inCall: Boolean = false,
    val foregroundPackage: String? = null,
    val networkType: String = "offline",
    val soundMode: String = "normal",
    val dndActive: Boolean = false,
    val headsetPlugged: Boolean = false,
)

/**
 * Pure rule matching, condition evaluation, scheduling, event formatting, and monitor-mask helpers
 * shared by [TriggerHub], [TriggerReceiver], [SmsReceiver], [RuleMonitorService], and unit tests.
 */
object RuleMatcher {
    const val FLAG_POWER = 1 shl 0
    const val FLAG_BATTERY = 1 shl 1
    const val FLAG_HEADSET = 1 shl 2
    const val FLAG_SCREEN = 1 shl 3
    const val FLAG_BLUETOOTH = 1 shl 4
    const val FLAG_WIFI = 1 shl 5
    const val FLAG_NETWORK = 1 shl 6
    const val FLAG_AIRPLANE = 1 shl 7
    const val FLAG_CALL = 1 shl 8

    fun alarmRequestCode(ruleId: Long, triggerIndex: Int): Int =
        ((ruleId.toInt() and 0xFFFF) shl 4) or (triggerIndex and 0xF)

    fun geofenceRequestId(ruleId: Long, triggerIndex: Int): String = "$ruleId:$triggerIndex"

    fun parseGeofenceRequestId(raw: String): Pair<Long, Int>? {
        val parts = raw.split(':')
        val id = parts.firstOrNull()?.toLongOrNull() ?: return null
        val index = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return id to index
    }

    fun monitorMask(specs: Iterable<RuleSpec>): Int {
        var mask = 0
        for (spec in specs) {
            for (trigger in spec.allTriggers) {
                mask = mask or when (trigger) {
                    is Trigger.Power -> FLAG_POWER
                    is Trigger.BatteryLow, is Trigger.BatteryHigh -> FLAG_BATTERY
                    is Trigger.Headset -> FLAG_HEADSET
                    is Trigger.Screen -> FLAG_SCREEN
                    is Trigger.Bluetooth -> FLAG_BLUETOOTH
                    is Trigger.Wifi -> FLAG_WIFI
                    is Trigger.Network -> FLAG_NETWORK
                    is Trigger.AirplaneMode -> FLAG_AIRPLANE
                    is Trigger.Call -> FLAG_CALL
                    is Trigger.Time, is Trigger.Geofence, is Trigger.Notification,
                    is Trigger.AppOpened, is Trigger.AppClosed, is Trigger.Sms,
                    is Trigger.Boot, is Trigger.Manual -> 0
                }
            }
        }
        return mask
    }

    fun matchRule(id: Long, spec: RuleSpec, ev: DeviceEvent): IndexedValue<Trigger>? =
        spec.allTriggers.withIndex().firstOrNull { (idx, t) -> matches(id, t, ev, idx) }

    fun matches(id: Long, t: Trigger, ev: DeviceEvent, triggerIndex: Int = 0): Boolean = when (t) {
        is Trigger.Manual -> ev is DeviceEvent.Manual && ev.ruleId == id
        is Trigger.Time -> ev is DeviceEvent.Time && ev.ruleId == id && ev.triggerIndex == triggerIndex
        is Trigger.Geofence -> ev is DeviceEvent.Geofence && ev.ruleId == id && ev.triggerIndex == triggerIndex && ev.entering == t.enter
        is Trigger.Wifi -> ev is DeviceEvent.Wifi && ev.connected == t.connected && (t.ssid == null || t.ssid.equals(ev.ssid, true))
        is Trigger.Bluetooth -> ev is DeviceEvent.Bluetooth && ev.connected == t.connected &&
            (t.deviceName == null || ev.deviceName?.contains(t.deviceName, true) == true)
        is Trigger.Power -> ev is DeviceEvent.Power && ev.connected == t.connected
        // Fires when the level crosses down through the threshold while discharging (re-arms above it).
        is Trigger.BatteryLow -> ev is DeviceEvent.Battery && !ev.charging && ev.previous != null && ev.previous > t.percent && ev.percent <= t.percent
        // Fires when the level crosses up through the threshold while charging.
        is Trigger.BatteryHigh -> ev is DeviceEvent.Battery && ev.charging && ev.previous != null && ev.previous < t.percent && ev.percent >= t.percent
        is Trigger.Headset -> ev is DeviceEvent.Headset && ev.plugged == t.plugged
        is Trigger.Screen -> ev is DeviceEvent.Screen && ev.event == t.event
        is Trigger.Notification -> if (ev !is DeviceEvent.Notification) false else {
            val haystack = "${ev.title} ${ev.text}"
            (t.packageName == null || t.packageName == ev.packageName) &&
                (t.keyword == null || haystack.contains(t.keyword, true)) &&
                (t.excludeKeyword == null || !haystack.contains(t.excludeKeyword, true))
        }
        is Trigger.AppOpened -> ev is DeviceEvent.AppOpened && ev.packageName == t.packageName
        is Trigger.AppClosed -> ev is DeviceEvent.AppClosed && ev.packageName == t.packageName
        is Trigger.Call -> ev is DeviceEvent.Call && ev.event == t.event &&
            (t.number == null || ev.number?.contains(t.number, true) == true)
        is Trigger.Sms -> if (ev !is DeviceEvent.Sms) false else {
            (t.sender == null || ev.sender.contains(t.sender, true)) &&
                (t.keyword == null || ev.body.contains(t.keyword, true)) &&
                (t.excludeKeyword == null || !ev.body.contains(t.excludeKeyword, true))
        }
        is Trigger.Network -> ev is DeviceEvent.Network && ev.type == t.type
        is Trigger.AirplaneMode -> ev is DeviceEvent.AirplaneMode && ev.enabled == t.enabled
        is Trigger.Boot -> ev is DeviceEvent.Boot
    }

    /** Unparseable stored values (rules from before validation) make the condition false, never a crash. */
    fun holds(c: Condition, nowTime: LocalTime, nowDate: LocalDate, state: DeviceState): Boolean = when (c) {
        is Condition.TimeRange -> {
            val from = RuleTime.parse(c.from)
            val to = RuleTime.parse(c.to)
            if (from == null || to == null) false
            else if (from <= to) nowTime in from..to
            else nowTime >= from || nowTime <= to
        }
        is Condition.Days -> nowDate.dayOfWeek.value in c.days
        is Condition.Battery -> (c.min == null || state.batteryPct >= c.min) && (c.max == null || state.batteryPct <= c.max)
        is Condition.Charging -> state.charging == c.charging
        is Condition.Wifi -> when {
            c.ssid == null -> state.wifiConnected == c.connected
            c.connected -> state.wifiConnected && c.ssid.equals(state.wifiSsid, true)
            else -> !state.wifiConnected || (state.wifiSsid != null && !c.ssid.equals(state.wifiSsid, true))
        }
        is Condition.Screen -> when (c.state) {
            "on" -> state.screenInteractive
            "off" -> !state.screenInteractive
            "unlocked" -> state.screenInteractive && !state.keyguardLocked
            "locked" -> !state.screenInteractive || state.keyguardLocked
            else -> false
        }
        is Condition.Bluetooth -> state.bluetoothConnected == c.connected
        is Condition.InCall -> state.inCall == c.inCall
        is Condition.ForegroundApp -> {
            val match = state.foregroundPackage == c.packageName
            if (c.invert) !match else match
        }
        is Condition.Network -> state.networkType == c.type
        is Condition.SoundMode -> if (c.mode == "dnd") state.dndActive else state.soundMode == c.mode
        is Condition.Headset -> state.headsetPlugged == c.plugged
    }

    fun nextFire(
        spec: RuleSpec,
        createdAt: Long,
        lastFiredAt: Long?,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long? = spec.allTriggers.filterIsInstance<Trigger.Time>()
        .mapNotNull { nextFire(it, createdAt, lastFiredAt, nowMs, zone) }
        .minOrNull()

    fun nextFire(
        t: Trigger.Time,
        createdAt: Long,
        lastFiredAt: Long?,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long? {
        t.onceAtEpochMs?.let { return it.takeIf { it > nowMs } }
        t.everyMinutes?.let { m ->
            // Anchored to the last run (or creation), so re-syncing on every app start or edit does not
            // keep pushing the next run into the future. A missed run fires a minute from now.
            val period = m.coerceAtLeast(15) * 60_000L
            val anchor = lastFiredAt ?: createdAt
            val due = anchor + period
            return if (due > nowMs) due else nowMs + 60_000L
        }
        val at = RuleTime.parse(t.at) ?: return null
        val nowDt = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        var dt = LocalDateTime.of(nowDt.toLocalDate(), at)
        repeat(8) {
            if (dt.isAfter(nowDt) && (t.days.isEmpty() || dt.dayOfWeek.value in t.days)) {
                return dt.atZone(zone).toInstant().toEpochMilli()
            }
            dt = dt.plusDays(1)
        }
        return null
    }

    fun describeEvent(
        s: RuleSpec,
        e: DeviceEvent,
        readableNotification: Boolean,
        matchedTrigger: Trigger? = null,
    ): String = when (e) {
        is DeviceEvent.Manual -> "manual run"
        is DeviceEvent.Time -> ((matchedTrigger ?: s.trigger) as? Trigger.Time)?.let { t ->
            t.at?.let { "scheduled time ($it)" }
                ?: t.everyMinutes?.let { "periodic schedule (every $it min)" }
                ?: "one-time schedule"
        } ?: "scheduled time"
        is DeviceEvent.Wifi -> "Wi-Fi ${if (e.connected) "connected" else "disconnected"}${e.ssid?.let { " ($it)" } ?: ""}"
        is DeviceEvent.Bluetooth -> "Bluetooth ${if (e.connected) "connected" else "disconnected"}${e.deviceName?.let { " ($it)" } ?: ""}"
        is DeviceEvent.Power -> if (e.connected) "charger connected" else "charger disconnected"
        is DeviceEvent.Battery -> "battery at ${e.percent}%"
        is DeviceEvent.Headset -> if (e.plugged) "headset plugged in" else "headset unplugged"
        is DeviceEvent.Screen -> "screen ${e.event}"
        is DeviceEvent.Notification -> if (readableNotification) {
            "notification from ${e.packageName}: ${e.title} — ${e.text.take(500)}"
        } else {
            "notification from ${e.packageName} (content hidden: the user blocked reading this app's notifications)"
        }
        is DeviceEvent.AppOpened -> "app opened (${e.packageName})"
        is DeviceEvent.AppClosed -> "app closed (${e.packageName})"
        is DeviceEvent.Geofence -> if (e.entering) "entered geofence" else "exited geofence"
        is DeviceEvent.Call -> "call ${e.event}${e.number?.let { " ($it)" } ?: ""}"
        is DeviceEvent.Sms -> "SMS from ${e.sender}: ${e.body.take(500)}"
        is DeviceEvent.Network -> "network changed to ${e.type}"
        is DeviceEvent.AirplaneMode -> if (e.enabled) "airplane mode on" else "airplane mode off"
        is DeviceEvent.Boot -> "device booted"
    }
}
