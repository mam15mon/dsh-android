package io.github.mam15mon.dsh.core.trigger

import android.Manifest
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.PowerManager
import android.telephony.TelephonyManager
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mam15mon.dsh.R
import io.github.mam15mon.dsh.core.capability.CapabilityHub
import io.github.mam15mon.dsh.core.data.RuleDao
import io.github.mam15mon.dsh.core.data.RuleEntity
import io.github.mam15mon.dsh.core.data.RuleRunEntity
import io.github.mam15mon.dsh.core.engine.RpcJson
import io.github.mam15mon.dsh.core.model.AUTO_SESSION_PREFIX
import io.github.mam15mon.dsh.core.model.Condition
import io.github.mam15mon.dsh.core.model.RuleAction
import io.github.mam15mon.dsh.core.model.RuleSpec
import io.github.mam15mon.dsh.core.model.Trigger
import io.github.mam15mon.dsh.core.platform.Notifications
import io.github.mam15mon.dsh.core.session.SessionStore
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import io.github.mam15mon.dsh.core.data.SettingsRepository
import io.github.mam15mon.dsh.service.DshAccessibilityService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Automation engine: keeps OS registrations in sync with the enabled rules, matches incoming
 * [DeviceEvent]s, and runs the rule's action — fixed tool steps directly, or an unattended agent session.
 */
@Singleton
class TriggerHub @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rules: RuleDao,
    private val capabilities: CapabilityHub,
    private val sessions: dagger.Lazy<SessionStore>,
    private val settings: SettingsRepository,
) {
    // A failure in one rule must never take the process down (C1): log it and carry on.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default +
        CoroutineExceptionHandler { _, e -> Log.e(TAG, "automation task failed", e) })
    private val lastFired = ConcurrentHashMap<Long, Long>()
    private val syncLock = Mutex()
    @Volatile private var cache: List<Pair<RuleEntity, RuleSpec>> = emptyList()

    fun decode(e: RuleEntity): RuleSpec? = runCatching { RpcJson.decodeFromString(RuleSpec.serializer(), e.specJson) }.getOrNull()

    /** Validates and normalizes a spec (throws [RuleSpecException] with model-readable problems). */
    fun validate(spec: RuleSpec): RuleSpec = validateRule(spec, capabilities.allTools.keys)

    suspend fun create(spec: RuleSpec): Long {
        val normalized = validate(spec)
        val id = rules.insert(RuleEntity(specJson = RpcJson.encodeToString(RuleSpec.serializer(), normalized)))
        sync(); return id
    }

    /** Replaces a rule's spec (validated and normalized like [create]); the enabled state is kept unless given. */
    suspend fun update(e: RuleEntity, spec: RuleSpec?, enabled: Boolean?): RuleSpec? {
        val normalized = spec?.let(::validate)
        val updated = normalized?.let { RpcJson.encodeToString(RuleSpec.serializer(), it) } ?: e.specJson
        if (rules.replaceSpec(e.id, e.specJson, updated, enabled) == 0) return null
        sync()
        return normalized ?: decode(e)
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) { rules.setEnabled(id, enabled); sync() }
    suspend fun delete(id: Long) {
        runCatching { cancelAlarms(id); cancelGeofences(id) }
        rules.deleteRuns(id)
        rules.delete(id)
        lastFired.remove(id)
        sync()
    }

    fun editableTools(): List<String> = capabilities.allTools.keys.sorted()

    suspend fun editAllowedTools(id: Long, added: Set<String>, removed: Set<String>): Boolean {
        repeat(3) {
            val row = rules.get(id) ?: return false
            val spec = decode(row) ?: return false
            if (spec.action !is RuleAction.Agent) return false
            val allowed = (spec.allowedTools.toSet() - removed + added).sorted()
            val updated = RpcJson.encodeToString(RuleSpec.serializer(), spec.copy(allowedTools = allowed))
            if (rules.replaceSpec(id, row.specJson, updated) == 1) {
                sync()
                return true
            }
        }
        return false
    }

    suspend fun editSpec(id: Long, transform: (RuleSpec) -> RuleSpec): Result<RuleSpec> {
        var lastError: Exception? = null
        repeat(3) {
            val row = rules.get(id) ?: return Result.failure(IllegalArgumentException("Automation #$id not found"))
            val spec = decode(row) ?: return Result.failure(IllegalStateException("Invalid automation #$id"))
            val next = try {
                validate(transform(spec))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                return Result.failure(e)
            }
            val encoded = RpcJson.encodeToString(RuleSpec.serializer(), next)
            if (rules.replaceSpec(id, row.specJson, encoded) == 1) {
                sync()
                return Result.success(next)
            }
            lastError = ConcurrentModificationException("Automation #$id changed concurrently")
        }
        return Result.failure(lastError ?: IllegalStateException("Could not update automation #$id"))
    }

    data class ManualRunResult(val ok: Boolean, val sessionId: String? = null, val message: String = "")

    suspend fun runNow(id: Long): ManualRunResult {
        val e = rules.get(id) ?: return ManualRunResult(false, message = "Automation #$id not found")
        val s = decode(e) ?: return ManualRunResult(false, message = "Invalid automation #$id")
        return fire(e, s, DeviceEvent.Manual(id), manual = true)
    }

    fun nextFireAt(e: RuleEntity, s: RuleSpec, nowMs: Long = System.currentTimeMillis()): Long? {
        if (!e.enabled) return null
        return RuleMatcher.nextFire(s, e.createdAt, e.lastFiredAt, nowMs)
    }

    /**
     * What the rule needs but does not have right now (so it would silently never fire). Shown to the
     * model by rule_create/rule_update and usable by the UI.
     */
    fun missingPrerequisites(spec: RuleSpec): List<String> = linkedSetOf<String>().apply {
        fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        for (t in spec.allTriggers) {
            when (t) {
                is Trigger.Geofence -> {
                    if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) add("precise location permission")
                    if (!granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) add("location 'Allow all the time' (background location)")
                }
                is Trigger.Bluetooth -> if (Build.VERSION.SDK_INT >= 31 && !granted(Manifest.permission.BLUETOOTH_CONNECT)) add("Nearby devices (Bluetooth) permission")
                is Trigger.Wifi -> if (t.ssid != null && !(granted(Manifest.permission.ACCESS_FINE_LOCATION) && granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)))
                    add("location 'Allow all the time' (Android hides the Wi-Fi name otherwise)")
                is Trigger.Notification -> if (context.packageName !in NotificationManagerCompat.getEnabledListenerPackages(context)) add("Notification access")
                is Trigger.AppOpened, is Trigger.AppClosed -> if (DshAccessibilityService.instance == null) add("Accessibility service (DSH screen control)")
                is Trigger.Call -> {
                    if (!granted(Manifest.permission.READ_PHONE_STATE)) add("Phone state permission")
                    if (t.number != null && !granted(Manifest.permission.READ_CALL_LOG)) add("Call log permission (for caller number)")
                }
                is Trigger.Sms -> if (!granted(Manifest.permission.RECEIVE_SMS)) add("SMS permission")
                else -> {}
            }
        }
        for (c in spec.conditions) {
            when (c) {
                is Condition.Wifi -> if (c.ssid != null && !(granted(Manifest.permission.ACCESS_FINE_LOCATION) && granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)))
                    add("location 'Allow all the time' (Android hides the Wi-Fi name otherwise)")
                is Condition.Bluetooth -> if (Build.VERSION.SDK_INT >= 31 && !granted(Manifest.permission.BLUETOOTH_CONNECT))
                    add("Nearby devices (Bluetooth) permission")
                is Condition.ForegroundApp -> if (DshAccessibilityService.instance == null)
                    add("Accessibility service (DSH screen control)")
                else -> {}
            }
        }
    }.toList()

    /** Non-blocking caveats (e.g. inexact alarms) to mention when a rule is created. */
    fun caveats(spec: RuleSpec): List<String> = linkedSetOf<String>().apply {
        if (spec.allTriggers.any { it is Trigger.Time } && !context.getSystemService(AlarmManager::class.java).canExact())
            add("exact alarms are not allowed for DSH, so this time rule may fire several minutes late (grant 'Alarms & reminders' to fix)")
        if (spec.allTriggers.any { it is Trigger.Geofence } && !playServicesGeofencing)
            add("no Google Play services: the geofence uses the platform location service and may fire minutes late")
    }.toList()

    private fun hasLiveTriggers(e: RuleEntity, s: RuleSpec, nowMs: Long): Boolean =
        s.allTriggers.any { t ->
            t !is Trigger.Time || t.onceAtEpochMs == null || RuleMatcher.nextFire(t, e.createdAt, e.lastFiredAt, nowMs) != null
        }

    /** Re-registers OS hooks for all enabled rules. Call at boot and after any change. Never throws. */
    suspend fun sync() = syncLock.withLock {
        val all = runCatching { rules.all() }.getOrElse { Log.e(TAG, "cannot read rules", it); return@withLock }
        val now = System.currentTimeMillis()
        val expiredOneShots = mutableSetOf<Long>()
        cache = all.filter { it.enabled }.mapNotNull { e ->
            val spec = decode(e) ?: return@mapNotNull null
            if (!hasLiveTriggers(e, spec, now)) {
                expiredOneShots += e.id
                null
            } else {
                e to spec
            }
        }
        expiredOneShots.forEach { id -> runCatching { rules.setEnabled(id, false) } }
        all.forEach { e -> runCatching { cancelAlarms(e.id) } }
        val fences = mutableListOf<Triple<Long, Int, Trigger.Geofence>>()
        cache.forEach { (e, s) ->
            s.allTriggers.forEachIndexed { idx, t ->
                when (t) {
                    is Trigger.Time -> runCatching { scheduleAlarm(e, t, idx, now) }.onFailure { Log.w(TAG, "rule ${e.id}[$idx]: cannot schedule", it) }
                    is Trigger.Geofence -> fences += Triple(e.id, idx, t)
                    else -> {}
                }
            }
        }
        runCatching { syncGeofences(all.map { it.id }, fences) }
            .onFailure { Log.w(TAG, "geofence sync failed", it) }
        val mask = RuleMatcher.monitorMask(cache.map { it.second })
        runCatching { RuleMonitorService.setRunning(context, mask) }
    }

    /**
     * Matches [event] against the enabled rules. Time alarms are rescheduled first (and one-shot
     * timers auto-disabled when no live trigger remains), then each matching rule fires in its own job.
     */
    fun onEvent(event: DeviceEvent): Job = scope.launch {
        if (cache.isEmpty()) sync()
        var oneShotMatch: Pair<RuleEntity, RuleSpec>? = null
        if (event is DeviceEvent.Time) rules.get(event.ruleId)?.let { e ->
            val s = decode(e)
            val t = s?.allTriggers?.getOrNull(event.triggerIndex) as? Trigger.Time
            if (t != null && e.enabled) {
                if (t.onceAtEpochMs != null) {
                    oneShotMatch = e to s
                    val otherLive = s.allTriggers.withIndex().any { (idx, tr) ->
                        idx != event.triggerIndex && (tr !is Trigger.Time || tr.onceAtEpochMs == null ||
                            RuleMatcher.nextFire(tr, e.createdAt, e.lastFiredAt, System.currentTimeMillis()) != null)
                    }
                    if (!otherLive) {
                        runCatching { rules.setEnabled(e.id, false) }
                        cache = cache.filterNot { it.first.id == e.id }
                    }
                } else {
                    runCatching { scheduleAlarm(e.copy(lastFiredAt = System.currentTimeMillis()), t, event.triggerIndex) }
                }
            }
        }
        val candidates = (cache + listOfNotNull(oneShotMatch)).distinctBy { it.first.id }
        val allConds = candidates.flatMap { it.second.conditions }
        val state = if (allConds.isEmpty()) DeviceState() else collectDeviceState(allConds)
        val nowTime = LocalTime.now()
        val nowDate = LocalDate.now()
        candidates.mapNotNull { (e, s) ->
            runCatching {
                val hit = RuleMatcher.matchRule(e.id, s, event) ?: return@mapNotNull null
                if (s.conditions.all { RuleMatcher.holds(it, nowTime, nowDate, state) }) Triple(e, s, hit.value) else null
            }.onFailure { Log.w(TAG, "rule ${e.id}: cannot evaluate", it) }.getOrNull()
        }.forEach { (e, s, matchedTrigger) -> scope.launch { fire(e, s, event, matchedTrigger = matchedTrigger) } }
    }

    private fun collectDeviceState(conditions: List<Condition>): DeviceState {
        val needBattery = conditions.any { it is Condition.Battery || it is Condition.Charging }
        val (pct, charging) = if (needBattery) battery() else (0 to false)

        val wifiConds = conditions.filterIsInstance<Condition.Wifi>()
        val (wifiConn, wifiSsid) = if (wifiConds.isNotEmpty()) currentWifi(wifiConds.any { it.ssid != null }) else (false to null)

        val needScreen = conditions.any { it is Condition.Screen }
        val (interactive, locked) = if (needScreen) currentScreen() else (true to false)

        val needBt = conditions.any { it is Condition.Bluetooth }
        val btConn = if (needBt) currentBluetooth() else false

        val needInCall = conditions.any { it is Condition.InCall }
        val inCall = if (needInCall) currentInCall() else false

        val needFg = conditions.any { it is Condition.ForegroundApp }
        val fgPkg = if (needFg) DshAccessibilityService.foregroundPackage.value else null

        val needNet = conditions.any { it is Condition.Network }
        val netType = if (needNet) currentNetworkType() else "offline"

        val needSound = conditions.any { it is Condition.SoundMode }
        val (soundMode, dndActive) = if (needSound) currentSoundMode() else ("normal" to false)

        val needHeadset = conditions.any { it is Condition.Headset }
        val headset = if (needHeadset) currentHeadset() else false

        return DeviceState(
            batteryPct = pct,
            charging = charging,
            wifiConnected = wifiConn,
            wifiSsid = wifiSsid,
            screenInteractive = interactive,
            keyguardLocked = locked,
            bluetoothConnected = btConn,
            inCall = inCall,
            foregroundPackage = fgPkg,
            networkType = netType,
            soundMode = soundMode,
            dndActive = dndActive,
            headsetPlugged = headset,
        )
    }

    private fun currentInCall(): Boolean {
        val audioInCall = runCatching {
            val mode = context.getSystemService(AudioManager::class.java).mode
            mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_RINGTONE
        }.getOrDefault(false)
        if (audioInCall) return true
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return false
        return runCatching {
            @Suppress("DEPRECATION")
            context.getSystemService(TelephonyManager::class.java)?.callState != TelephonyManager.CALL_STATE_IDLE
        }.getOrDefault(false)
    }

    private fun currentNetworkType(): String = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return@runCatching "offline"
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "offline"
        }
    }.getOrDefault("offline")

    private fun currentSoundMode(): Pair<String, Boolean> = runCatching {
        val am = context.getSystemService(AudioManager::class.java)
        val mode = when (am.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            else -> "normal"
        }
        val dnd = context.getSystemService(NotificationManager::class.java)
            .currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        mode to dnd
    }.getOrDefault("normal" to false)

    private fun currentHeadset(): Boolean = runCatching {
        val am = context.getSystemService(AudioManager::class.java)
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
    }.getOrDefault(false)

    private fun currentWifi(needSsid: Boolean): Pair<Boolean, String?> = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            ?.takeIf { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
        if (caps == null) return@runCatching false to null
        if (!needSsid) return@runCatching true to null
        @Suppress("DEPRECATION")
        val info = if (Build.VERSION.SDK_INT >= 31) caps.transportInfo as? WifiInfo
            else context.getSystemService(WifiManager::class.java).connectionInfo
        val ssid = info?.ssid?.trim('"')?.takeIf { it != "<unknown ssid>" && it.isNotEmpty() }
        true to ssid
    }.getOrDefault(false to null)

    private fun currentScreen(): Pair<Boolean, Boolean> {
        val interactive = runCatching { context.getSystemService(PowerManager::class.java).isInteractive }.getOrDefault(true)
        val locked = runCatching { context.getSystemService(KeyguardManager::class.java).isKeyguardLocked }.getOrDefault(false)
        return interactive to locked
    }

    private fun currentBluetooth(): Boolean {
        val audioConnected = runCatching {
            val am = context.getSystemService(AudioManager::class.java)
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
        }.getOrDefault(false)
        if (audioConnected) return true
        if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        return runCatching {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return@runCatching false
            @Suppress("MissingPermission")
            val a2dp = adapter.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED
            @Suppress("MissingPermission")
            val hfp = adapter.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED
            a2dp || hfp
        }.getOrDefault(false)
    }

    private fun battery(): Pair<Int, Boolean> {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val pct = i?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1) } ?: 0
        return pct to ((i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0)
    }

    private suspend fun fire(
        e: RuleEntity,
        s: RuleSpec,
        event: DeviceEvent,
        manual: Boolean = false,
        matchedTrigger: Trigger? = null,
    ): ManualRunResult {
        val now = System.currentTimeMillis()
        if (!manual) {
            val cooldownMs = s.cooldownSeconds.coerceIn(0, 86_400) * 1000L
            if (cooldownMs > 0L) {
                // Claim the debounce slot atomically before suspending: a notification burst (message + group
                // summary, updates) must not run the same rule twice.
                val claimed = lastFired.compute(e.id) { _, prev -> if (prev == null || now - prev >= cooldownMs) now else prev }
                if (claimed != now) return ManualRunResult(false, message = "cooldown")
            }
            if (rules.runsSince(e.id, now - 86_400_000L) >= DAILY_CAP) return ManualRunResult(false, message = "daily_cap")
        }
        rules.setLastFired(e.id, now)
        val sessionId = "$AUTO_SESSION_PREFIX${e.id}_${UUID.randomUUID()}"
        Log.i(TAG, "rule ${e.id} '${s.name}' fired by $event (manual=$manual)")
        when (val a = s.action) {
            is RuleAction.Steps -> {
                val outcome = try {
                    var failure: String? = null
                    for (step in a.steps) {
                        val r = capabilities.invoke(buildJsonObject {
                            put("tool", step.tool); put("sessionId", sessionId); put("callId", UUID.randomUUID().toString()); put("args", step.args)
                        })
                        if (!r.ok) { failure = "${step.tool}: ${r.errorMessage}"; break }
                    }
                    failure?.let { "failed: $it" } ?: "ok"
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Log.w(TAG, "rule ${e.id} failed", ex); "error: ${ex.message}"
                }
                rules.insertRun(RuleRunEntity(ruleId = e.id, firedAt = now, sessionId = null, outcome = outcome))
                if (outcome != "ok" || s.notifyOnSuccess) {
                    Notifications.post(context, Notifications.CHANNEL_TASKS, e.id.toInt() + 1000,
                        context.getString(R.string.notif_rule_ran, s.name), outcome, null)
                }
                return ManualRunResult(outcome == "ok", null, outcome)
            }
            is RuleAction.Agent -> {
                // Insert "started" row before prompt() so SessionStore updates always find a matching row.
                rules.insertRun(RuleRunEntity(ruleId = e.id, firedAt = now, sessionId = sessionId, outcome = "started"))
                return try {
                    sessions.get().prompt(sessionId, "[Automation \"${s.name}\" triggered by ${describe(s, event, matchedTrigger)}]\n${a.prompt}", ruleId = e.id)
                    ManualRunResult(true, sessionId, "started")
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Log.w(TAG, "rule ${e.id} failed", ex)
                    val msg = "error: ${ex.message}"
                    runCatching { rules.updateRunOutcome(sessionId, msg) }
                    ManualRunResult(false, sessionId, msg)
                }
            }
        }
    }

    private suspend fun describe(s: RuleSpec, e: DeviceEvent, matchedTrigger: Trigger?): String {
        val readable = if (e is DeviceEvent.Notification) {
            val matchedHasPkg = (matchedTrigger as? Trigger.Notification)?.packageName != null ||
                s.allTriggers.filterIsInstance<Trigger.Notification>().any { it.packageName == e.packageName }
            matchedHasPkg || e.packageName !in settings.current().notificationBlocked
        } else false
        return RuleMatcher.describeEvent(s, e, readable, matchedTrigger)
    }

    // ---- time --------------------------------------------------------------------------------

    private fun alarmIntent(id: Long, triggerIndex: Int) = PendingIntent.getBroadcast(
        context,
        RuleMatcher.alarmRequestCode(id, triggerIndex),
        Intent(context, TriggerReceiver::class.java)
            .setAction(TriggerReceiver.ACTION_TIME)
            .putExtra("ruleId", id)
            .putExtra("triggerIndex", triggerIndex),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun cancelAlarms(id: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        for (idx in 0 until MAX_RULE_TRIGGERS) am.cancel(alarmIntent(id, idx))
        val legacy = PendingIntent.getBroadcast(
            context, id.toInt(),
            Intent(context, TriggerReceiver::class.java).setAction(TriggerReceiver.ACTION_TIME).putExtra("ruleId", id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        )
        if (legacy != null) am.cancel(legacy)
    }

    private fun scheduleAlarm(e: RuleEntity, t: Trigger.Time, triggerIndex: Int, nowMs: Long = System.currentTimeMillis()) {
        val next = RuleMatcher.nextFire(t, e.createdAt, e.lastFiredAt, nowMs) ?: return
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent(e.id, triggerIndex)
        if (am.canExact()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
    }

    // ---- geofence ------------------------------------------------------------------------------
    // With Play services: GeofencingClient. Its transition broadcasts are on Android's list of
    // background foreground-service-start exemptions, so an agent rule can start the engine, and
    // fused location keeps it cheap. Without Play services (many CN ROMs): the platform
    // LocationManager.addProximityAlert, which is NOT exempt and only sees background location a few
    // times an hour — such rules may fire minutes late, and if the FGS start is refused the run
    // degrades to the 点此执行 notification (DeferredRuns). The rule UI/Doctor say so.

    val playServicesGeofencing: Boolean by lazy {
        runCatching { GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS }.getOrDefault(false)
    }

    private fun geofenceIntent(id: Long, triggerIndex: Int) = PendingIntent.getBroadcast(
        context,
        500_000 + RuleMatcher.alarmRequestCode(id, triggerIndex),
        Intent(context, TriggerReceiver::class.java)
            .setAction(TriggerReceiver.ACTION_GEOFENCE)
            .putExtra("ruleId", id)
            .putExtra("triggerIndex", triggerIndex),
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** One shared PendingIntent for all Play-services geofences (request id = ruleId:triggerIndex). */
    private val gmsGeofenceIntent by lazy {
        PendingIntent.getBroadcast(context, 499_999, Intent(context, TriggerReceiver::class.java).setAction(TriggerReceiver.ACTION_GEOFENCE_GMS),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /**
     * Re-registers geofences sequentially inside [syncLock] so removal and registration cannot race.
     */
    private suspend fun syncGeofences(allIds: List<Long>, fences: List<Triple<Long, Int, Trigger.Geofence>>) {
        val canLocate = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (playServicesGeofencing) {
            val client = LocationServices.getGeofencingClient(context)
            suspendCancellableCoroutine<Unit> { cont ->
                client.removeGeofences(gmsGeofenceIntent).addOnCompleteListener {
                    if (cont.isActive) cont.resume(Unit)
                }
            }
            if (!canLocate || fences.isEmpty()) return
            val request = GeofencingRequest.Builder().setInitialTrigger(0).apply {
                fences.forEach { (id, idx, g) ->
                    addGeofence(Geofence.Builder().setRequestId(RuleMatcher.geofenceRequestId(id, idx)).setCircularRegion(g.lat, g.lng, g.radiusM)
                        .setExpirationDuration(Geofence.NEVER_EXPIRE)
                        .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT).build())
                }
            }.build()
            suspendCancellableCoroutine<Unit> { cont ->
                @Suppress("MissingPermission")
                runCatching {
                    client.addGeofences(request, gmsGeofenceIntent)
                        .addOnFailureListener { Log.w(TAG, "geofences not registered", it) }
                        .addOnCompleteListener { if (cont.isActive) cont.resume(Unit) }
                }.onFailure {
                    Log.w(TAG, "geofences not registered", it)
                    if (cont.isActive) cont.resume(Unit)
                }
            }
            return
        }
        val lm = context.getSystemService(LocationManager::class.java)
        allIds.forEach { id -> cancelPlatformGeofences(lm, id) }
        if (!canLocate) return
        @Suppress("MissingPermission")
        fences.forEach { (id, idx, g) -> runCatching { lm.addProximityAlert(g.lat, g.lng, g.radiusM, -1, geofenceIntent(id, idx)) } }
    }

    private fun cancelPlatformGeofences(lm: LocationManager, id: Long) {
        for (idx in 0 until MAX_RULE_TRIGGERS) runCatching { lm.removeProximityAlert(geofenceIntent(id, idx)) }
        val legacy = PendingIntent.getBroadcast(
            context, (id + 500_000).toInt(),
            Intent(context, TriggerReceiver::class.java).setAction(TriggerReceiver.ACTION_GEOFENCE).putExtra("ruleId", id),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_NO_CREATE,
        )
        if (legacy != null) runCatching { lm.removeProximityAlert(legacy) }
    }

    private fun cancelGeofences(id: Long) = runCatching {
        if (playServicesGeofencing) {
            val reqIds = (0 until MAX_RULE_TRIGGERS).map { RuleMatcher.geofenceRequestId(id, it) } + id.toString()
            LocationServices.getGeofencingClient(context).removeGeofences(reqIds)
        } else {
            cancelPlatformGeofences(context.getSystemService(LocationManager::class.java), id)
        }
    }

    private companion object {
        const val TAG = "TriggerHub"
        const val DAILY_CAP = 50
    }
}

/** Exact alarms need no grant before Android 12, where `canScheduleExactAlarms()` does not exist (#8). */
fun AlarmManager.canExact(): Boolean = android.os.Build.VERSION.SDK_INT < 31 || canScheduleExactAlarms()
