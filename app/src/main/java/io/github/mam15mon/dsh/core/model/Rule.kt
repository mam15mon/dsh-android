package io.github.mam15mon.dsh.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** "When [trigger] (and all [conditions]) then [action]". */
@Serializable
data class RuleSpec(
    val name: String,
    val trigger: Trigger,
    val conditions: List<Condition> = emptyList(),
    val action: RuleAction,
    /** Tools the unattended run may use; anything else is refused. */
    val allowedTools: List<String> = emptyList(),
    /** Minimum interval between automatic runs (seconds); 0 disables cooldown. */
    val cooldownSeconds: Int = 60,
    /** Whether successful runs post a notification (fixed steps) or allow turn notifications (agent). */
    val notifyOnSuccess: Boolean = true,
    /** Optional per-rule model override ("" = DeepSeek, non-empty = provider id; null modelId = follow default). */
    val modelProvider: String? = null,
    val modelId: String? = null,
    val modelEffort: String? = null,
    /** Optional per-rule working directory override for agent runs (null = follow default). */
    val workspace: String? = null,
    /** Additional triggers (OR semantics: any trigger in [allTriggers] firing evaluates [conditions]). */
    val extraTriggers: List<Trigger> = emptyList(),
) {
    val allTriggers: List<Trigger> get() = listOf(trigger) + extraTriggers
}

@Serializable
sealed interface Trigger {
    @Serializable @SerialName("manual")
    data object Manual : Trigger
    @Serializable @SerialName("time")
    data class Time(val at: String? = null, val days: List<Int> = emptyList(), val everyMinutes: Int? = null, val onceAtEpochMs: Long? = null) : Trigger
    @Serializable @SerialName("wifi")
    data class Wifi(val connected: Boolean, val ssid: String? = null) : Trigger
    @Serializable @SerialName("bluetooth")
    data class Bluetooth(val connected: Boolean, val deviceName: String? = null) : Trigger
    @Serializable @SerialName("power")
    data class Power(val connected: Boolean) : Trigger
    @Serializable @SerialName("battery_low")
    data class BatteryLow(val percent: Int) : Trigger
    @Serializable @SerialName("headset")
    data class Headset(val plugged: Boolean) : Trigger
    @Serializable @SerialName("screen")
    data class Screen(val event: String /* on | off | unlock */) : Trigger
    @Serializable @SerialName("notification")
    data class Notification(val packageName: String? = null, val keyword: String? = null, val excludeKeyword: String? = null) : Trigger
    @Serializable @SerialName("app_opened")
    data class AppOpened(val packageName: String) : Trigger
    @Serializable @SerialName("app_closed")
    data class AppClosed(val packageName: String) : Trigger
    @Serializable @SerialName("geofence")
    data class Geofence(val lat: Double, val lng: Double, val radiusM: Float = 200f, val enter: Boolean = true) : Trigger
    @Serializable @SerialName("call")
    data class Call(val event: String /* ringing | answered | ended | missed */, val number: String? = null) : Trigger
    @Serializable @SerialName("sms")
    data class Sms(val sender: String? = null, val keyword: String? = null, val excludeKeyword: String? = null) : Trigger
    @Serializable @SerialName("battery_high")
    data class BatteryHigh(val percent: Int) : Trigger
    @Serializable @SerialName("network")
    data class Network(val type: String /* wifi | cellular | offline */) : Trigger
    @Serializable @SerialName("airplane_mode")
    data class AirplaneMode(val enabled: Boolean) : Trigger
    @Serializable @SerialName("boot")
    data object Boot : Trigger
}

@Serializable
sealed interface Condition {
    @Serializable @SerialName("time_range")
    data class TimeRange(val from: String, val to: String) : Condition
    @Serializable @SerialName("days")
    data class Days(val days: List<Int>) : Condition
    @Serializable @SerialName("battery")
    data class Battery(val min: Int? = null, val max: Int? = null) : Condition
    @Serializable @SerialName("charging")
    data class Charging(val charging: Boolean) : Condition
    @Serializable @SerialName("wifi")
    data class Wifi(val connected: Boolean, val ssid: String? = null) : Condition
    @Serializable @SerialName("screen")
    data class Screen(val state: String /* on | off | unlocked | locked */) : Condition
    @Serializable @SerialName("bluetooth")
    data class Bluetooth(val connected: Boolean) : Condition
    @Serializable @SerialName("in_call")
    data class InCall(val inCall: Boolean) : Condition
    @Serializable @SerialName("foreground_app")
    data class ForegroundApp(val packageName: String, val invert: Boolean = false) : Condition
    @Serializable @SerialName("network")
    data class Network(val type: String /* wifi | cellular | offline */) : Condition
    @Serializable @SerialName("sound_mode")
    data class SoundMode(val mode: String /* normal | vibrate | silent | dnd */) : Condition
    @Serializable @SerialName("headset")
    data class Headset(val plugged: Boolean) : Condition
}

@Serializable
sealed interface RuleAction {
    /** Hand the event to the agent with this instruction. */
    @Serializable @SerialName("agent")
    data class Agent(val prompt: String) : RuleAction
    /** Run fixed tool calls directly — no model, no tokens. */
    @Serializable @SerialName("steps")
    data class Steps(val steps: List<Step>) : RuleAction
}

@Serializable
data class Step(val tool: String, val args: JsonObject = JsonObject(emptyMap()))
