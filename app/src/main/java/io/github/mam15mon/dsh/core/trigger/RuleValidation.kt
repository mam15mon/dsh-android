package io.github.mam15mon.dsh.core.trigger

import io.github.mam15mon.dsh.core.model.Condition
import io.github.mam15mon.dsh.core.model.RuleAction
import io.github.mam15mon.dsh.core.model.RuleSpec
import io.github.mam15mon.dsh.core.model.Trigger
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Lenient wall-clock parsing for rules: "7:30", "07:30", "7:30:00". Never throws. */
object RuleTime {
    private val formats = listOf("H:mm", "HH:mm", "H:mm:ss").map { DateTimeFormatter.ofPattern(it).withResolverStyle(java.time.format.ResolverStyle.STRICT) }
    fun parse(s: String?): LocalTime? {
        val t = s?.trim()?.replace('：', ':') ?: return null
        for (f in formats) runCatching { return LocalTime.parse(t, f) }
        return null
    }
    fun format(t: LocalTime): String = "%02d:%02d".format(t.hour, t.minute)
}

/** Tools whose arguments only the engine's policy checks (plugin-guard hardline): never direct rule steps. */
val ENGINE_ONLY_STEP_TOOLS = setOf("adb_shell", "phone_sh")

const val MAX_RULE_TRIGGERS = 8

class RuleSpecException(val problems: List<String>) : Exception(problems.joinToString("; "))

/**
 * Checks a RuleSpec before it is stored, and returns it normalized (times as HH:mm, step tools added
 * to allowedTools). Throws [RuleSpecException] listing every problem in model-readable form.
 */
fun validateRule(spec: RuleSpec, knownTools: Set<String>, now: Long = System.currentTimeMillis()): RuleSpec {
    val p = mutableListOf<String>()
    if (spec.name.isBlank()) p += "name must not be empty"
    if (spec.allTriggers.size > MAX_RULE_TRIGGERS) p += "at most $MAX_RULE_TRIGGERS triggers per automation"
    fun time(field: String, v: String?): String? {
        if (v == null) return null
        val t = RuleTime.parse(v)
        if (t == null) { p += "$field '$v' is not a time; use 24-hour \"HH:mm\" such as \"07:30\" (00:00–23:59)"; return v }
        return RuleTime.format(t)
    }
    fun days(field: String, d: List<Int>) { if (d.any { it !in 1..7 }) p += "$field must be numbers 1..7 (Mon=1 … Sun=7), got $d" }

    fun validateTrigger(raw: Trigger, prefix: String): Trigger = when (raw) {
        is Trigger.Time -> {
            val set = listOfNotNull(raw.at, raw.everyMinutes, raw.onceAtEpochMs).size
            if (set != 1) p += "$prefix time trigger needs exactly one of \"at\" (HH:mm), \"everyMinutes\" (>=15) or \"onceAtEpochMs\""
            days("$prefix.days", raw.days)
            if (raw.everyMinutes != null && raw.everyMinutes < 15) p += "$prefix.everyMinutes must be at least 15 (Android limits background alarms), got ${raw.everyMinutes}"
            if (raw.onceAtEpochMs != null && raw.onceAtEpochMs <= now) p += "$prefix.onceAtEpochMs is in the past"
            if (raw.days.isNotEmpty() && raw.at == null) p += "$prefix \"days\" only applies together with \"at\""
            raw.copy(at = time("$prefix.at", raw.at))
        }
        is Trigger.Wifi -> raw.copy(ssid = raw.ssid?.trim()?.takeIf { it.isNotEmpty() })
        is Trigger.Bluetooth -> raw.copy(deviceName = raw.deviceName?.trim()?.takeIf { it.isNotEmpty() })
        is Trigger.Screen -> raw.also { if (it.event !in setOf("on", "off", "unlock")) p += "$prefix.event must be \"on\", \"off\" or \"unlock\", got \"${it.event}\"" }
        is Trigger.BatteryLow -> raw.also { if (it.percent !in 1..99) p += "$prefix.percent must be 1..99, got ${it.percent}" }
        is Trigger.Geofence -> raw.also {
            if (it.lat !in -90.0..90.0 || it.lng !in -180.0..180.0) p += "$prefix lat/lng out of range"
            if (it.radiusM !in 50f..100_000f) p += "$prefix.radiusM must be 50..100000 metres (100+ recommended), got ${it.radiusM}"
        }
        is Trigger.AppOpened -> raw.also { if (it.packageName.isBlank()) p += "$prefix.packageName must not be empty" }
        is Trigger.AppClosed -> raw.let {
            if (it.packageName.isBlank()) { p += "$prefix.packageName must not be empty"; it }
            else it.copy(packageName = it.packageName.trim())
        }
        is Trigger.Call -> raw.let {
            if (it.event !in setOf("ringing", "answered", "ended", "missed")) {
                p += "$prefix.event must be \"ringing\", \"answered\", \"ended\" or \"missed\", got \"${it.event}\""
            }
            it.copy(number = it.number?.trim()?.takeIf { n -> n.isNotEmpty() })
        }
        is Trigger.Sms -> raw.copy(
            sender = raw.sender?.trim()?.takeIf { it.isNotEmpty() },
            keyword = raw.keyword?.trim()?.takeIf { it.isNotEmpty() },
            excludeKeyword = raw.excludeKeyword?.trim()?.takeIf { it.isNotEmpty() },
        )
        is Trigger.BatteryHigh -> raw.also { if (it.percent !in 2..100) p += "$prefix.percent must be 2..100, got ${it.percent}" }
        is Trigger.Network -> raw.also {
            if (it.type !in setOf("wifi", "cellular", "offline")) p += "$prefix.type must be \"wifi\", \"cellular\" or \"offline\", got \"${it.type}\""
        }
        is Trigger.Notification -> raw.copy(
            packageName = raw.packageName?.trim()?.takeIf { it.isNotEmpty() },
            keyword = raw.keyword?.trim()?.takeIf { it.isNotEmpty() },
            excludeKeyword = raw.excludeKeyword?.trim()?.takeIf { it.isNotEmpty() },
        )
        else -> raw
    }

    val trigger = validateTrigger(spec.trigger, "trigger")
    val extraTriggers = spec.extraTriggers.mapIndexed { idx, t -> validateTrigger(t, "extraTriggers[$idx]") }
    val conditions = spec.conditions.map { c ->
        when (c) {
            is Condition.TimeRange -> c.copy(from = time("time_range.from", c.from) ?: c.from, to = time("time_range.to", c.to) ?: c.to)
            is Condition.Days -> c.also { days("days condition", it.days) }
            is Condition.Battery -> c.also {
                if ((it.min != null && it.min !in 0..100) || (it.max != null && it.max !in 0..100)) p += "battery condition min/max must be 0..100"
                if (it.min != null && it.max != null && it.min > it.max) p += "battery condition min must be <= max"
            }
            is Condition.Wifi -> c.copy(ssid = c.ssid?.trim()?.takeIf { it.isNotEmpty() })
            is Condition.Screen -> c.also {
                if (it.state !in setOf("on", "off", "unlocked", "locked")) {
                    p += "screen condition state must be \"on\", \"off\", \"unlocked\" or \"locked\", got \"${it.state}\""
                }
            }
            is Condition.ForegroundApp -> {
                if (c.packageName.isBlank()) { p += "foreground_app condition packageName must not be empty"; c }
                else c.copy(packageName = c.packageName.trim())
            }
            is Condition.Network -> c.also {
                if (it.type !in setOf("wifi", "cellular", "offline")) {
                    p += "network condition type must be \"wifi\", \"cellular\" or \"offline\", got \"${it.type}\""
                }
            }
            is Condition.SoundMode -> c.also {
                if (it.mode !in setOf("normal", "vibrate", "silent", "dnd")) {
                    p += "sound_mode condition mode must be \"normal\", \"vibrate\", \"silent\" or \"dnd\", got \"${it.mode}\""
                }
            }
            is Condition.Charging, is Condition.Bluetooth, is Condition.InCall, is Condition.Headset -> c
        }
    }
    var allowed = spec.allowedTools.map { it.trim() }.filter { it.isNotEmpty() }
    when (val a = spec.action) {
        is RuleAction.Agent -> if (a.prompt.isBlank()) p += "agent action needs a non-empty prompt"
        is RuleAction.Steps -> {
            if (a.steps.isEmpty()) p += "steps action needs at least one step"
            a.steps.map { it.tool }.filter { it !in knownTools }.distinct().takeIf { it.isNotEmpty() }?.let { p += "unknown step tool(s): ${it.joinToString()}" }
            // A step runs without the engine, so the hardline command checks would not see it.
            a.steps.map { it.tool }.filter { it in ENGINE_ONLY_STEP_TOOLS }.distinct().takeIf { it.isNotEmpty() }
                ?.let { p += "${it.joinToString()} cannot be a direct step (DSH checks its commands in the engine); use an agent action that calls it" }
            allowed = allowed + a.steps.map { it.tool }
        }
    }
    if (spec.cooldownSeconds !in 0..86_400) p += "cooldownSeconds must be 0..86400, got ${spec.cooldownSeconds}"
    val normalizedWorkspace = spec.workspace?.let { raw ->
        if (raw.isEmpty()) "" else {
            val ws = raw.trim().trimEnd('/').ifEmpty { "/" }
            if (!ws.startsWith("/") || ws.any { it == '\n' || it == '\t' || it == '\u0000' }) {
                p += "workspace must be an absolute path"
                raw
            } else ws
        }
    }
    val normalizedModelId = spec.modelId?.trim()?.takeIf { it.isNotEmpty() }
    val normalizedProvider = if (normalizedModelId == null) null else spec.modelProvider?.trim().orEmpty()
    val normalizedEffort = if (normalizedModelId == null) null else spec.modelEffort?.trim()?.takeIf { it.isNotEmpty() }
    // allowedTools may also name engine built-ins (web_search, read, …), so unknown names are not an error.
    if (p.isNotEmpty()) throw RuleSpecException(p)
    return spec.copy(
        trigger = trigger,
        extraTriggers = extraTriggers,
        conditions = conditions,
        allowedTools = allowed.distinct(),
        modelProvider = normalizedProvider,
        modelId = normalizedModelId,
        modelEffort = normalizedEffort,
        workspace = normalizedWorkspace,
    )
}
