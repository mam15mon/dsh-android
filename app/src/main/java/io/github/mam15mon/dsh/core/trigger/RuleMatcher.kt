package io.github.mam15mon.dsh.core.trigger

import io.github.mam15mon.dsh.core.model.Condition
import io.github.mam15mon.dsh.core.model.RuleSpec
import io.github.mam15mon.dsh.core.model.Trigger
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Pure rule matching, condition evaluation, scheduling, event formatting, and monitor-mask helpers
 * shared by [TriggerHub], [RuleMonitorService], and unit tests.
 */
object RuleMatcher {
    const val FLAG_POWER = 1 shl 0
    const val FLAG_BATTERY = 1 shl 1
    const val FLAG_HEADSET = 1 shl 2
    const val FLAG_SCREEN = 1 shl 3
    const val FLAG_BLUETOOTH = 1 shl 4
    const val FLAG_WIFI = 1 shl 5

    fun monitorMask(specs: Iterable<RuleSpec>): Int {
        var mask = 0
        for (spec in specs) {
            mask = mask or when (spec.trigger) {
                is Trigger.Power -> FLAG_POWER
                is Trigger.BatteryLow -> FLAG_BATTERY
                is Trigger.Headset -> FLAG_HEADSET
                is Trigger.Screen -> FLAG_SCREEN
                is Trigger.Bluetooth -> FLAG_BLUETOOTH
                is Trigger.Wifi -> FLAG_WIFI
                is Trigger.Time, is Trigger.Geofence, is Trigger.Notification, is Trigger.AppOpened, is Trigger.Manual -> 0
            }
        }
        return mask
    }

    fun matches(id: Long, t: Trigger, ev: DeviceEvent): Boolean = when (t) {
        is Trigger.Manual -> ev is DeviceEvent.Manual && ev.ruleId == id
        is Trigger.Time -> ev is DeviceEvent.Time && ev.ruleId == id
        is Trigger.Geofence -> ev is DeviceEvent.Geofence && ev.ruleId == id && ev.entering == t.enter
        is Trigger.Wifi -> ev is DeviceEvent.Wifi && ev.connected == t.connected && (t.ssid == null || t.ssid.equals(ev.ssid, true))
        is Trigger.Bluetooth -> ev is DeviceEvent.Bluetooth && ev.connected == t.connected &&
            (t.deviceName == null || ev.deviceName?.contains(t.deviceName, true) == true)
        is Trigger.Power -> ev is DeviceEvent.Power && ev.connected == t.connected
        // Fires when the level crosses down through the threshold while discharging (re-arms above it).
        is Trigger.BatteryLow -> ev is DeviceEvent.Battery && !ev.charging && ev.previous != null && ev.previous > t.percent && ev.percent <= t.percent
        is Trigger.Headset -> ev is DeviceEvent.Headset && ev.plugged == t.plugged
        is Trigger.Screen -> ev is DeviceEvent.Screen && ev.event == t.event
        is Trigger.Notification -> ev is DeviceEvent.Notification && (t.packageName == null || t.packageName == ev.packageName) &&
            (t.keyword == null || "${ev.title} ${ev.text}".contains(t.keyword, true))
        is Trigger.AppOpened -> ev is DeviceEvent.AppOpened && ev.packageName == t.packageName
    }

    /** Unparseable stored values (rules from before validation) make the condition false, never a crash. */
    fun holds(c: Condition, nowTime: LocalTime, nowDate: LocalDate, batteryPct: Int, charging: Boolean): Boolean = when (c) {
        is Condition.TimeRange -> {
            val from = RuleTime.parse(c.from)
            val to = RuleTime.parse(c.to)
            if (from == null || to == null) false
            else if (from <= to) nowTime in from..to
            else nowTime >= from || nowTime <= to
        }
        is Condition.Days -> nowDate.dayOfWeek.value in c.days
        is Condition.Battery -> (c.min == null || batteryPct >= c.min) && (c.max == null || batteryPct <= c.max)
        is Condition.Charging -> charging == c.charging
    }

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

    fun describeEvent(s: RuleSpec, e: DeviceEvent, readableNotification: Boolean): String = when (e) {
        is DeviceEvent.Manual -> "manual run"
        is DeviceEvent.Time -> (s.trigger as? Trigger.Time)?.let { t ->
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
        is DeviceEvent.Geofence -> if (e.entering) "entered geofence" else "exited geofence"
    }
}
