package io.github.mam15mon.dsh.core.trigger

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.BatteryManager
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
        runCatching { cancelAlarm(id); cancelGeofence(id) }
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
        val t = s.trigger as? Trigger.Time ?: return null
        return RuleMatcher.nextFire(t, e.createdAt, e.lastFiredAt, nowMs)
    }

    /**
     * What the rule needs but does not have right now (so it would silently never fire). Shown to the
     * model by rule_create/rule_update and usable by the UI.
     */
    fun missingPrerequisites(spec: RuleSpec): List<String> = buildList {
        fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        when (val t = spec.trigger) {
            is Trigger.Geofence -> {
                if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) add("precise location permission")
                if (!granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) add("location 'Allow all the time' (background location)")
            }
            is Trigger.Bluetooth -> if (Build.VERSION.SDK_INT >= 31 && !granted(Manifest.permission.BLUETOOTH_CONNECT)) add("Nearby devices (Bluetooth) permission")
            is Trigger.Wifi -> if (t.ssid != null && !(granted(Manifest.permission.ACCESS_FINE_LOCATION) && granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)))
                add("location 'Allow all the time' (Android hides the Wi-Fi name otherwise)")
            is Trigger.Notification -> if (context.packageName !in NotificationManagerCompat.getEnabledListenerPackages(context)) add("Notification access")
            is Trigger.AppOpened -> if (DshAccessibilityService.instance == null) add("Accessibility service (DSH screen control)")
            else -> {}
        }
    }

    /** Non-blocking caveats (e.g. inexact alarms) to mention when a rule is created. */
    fun caveats(spec: RuleSpec): List<String> = buildList {
        if (spec.trigger is Trigger.Time && !context.getSystemService(AlarmManager::class.java).canExact())
            add("exact alarms are not allowed for DSH, so this time rule may fire several minutes late (grant 'Alarms & reminders' to fix)")
        if (spec.trigger is Trigger.Geofence && !playServicesGeofencing)
            add("no Google Play services: the geofence uses the platform location service and may fire minutes late")
    }

    /** Re-registers OS hooks for all enabled rules. Call at boot and after any change. Never throws. */
    suspend fun sync() = syncLock.withLock {
        val all = runCatching { rules.all() }.getOrElse { Log.e(TAG, "cannot read rules", it); return@withLock }
        val now = System.currentTimeMillis()
        val expiredOneShots = mutableSetOf<Long>()
        cache = all.filter { it.enabled }.mapNotNull { e ->
            val spec = decode(e) ?: return@mapNotNull null
            val t = spec.trigger
            if (t is Trigger.Time && t.onceAtEpochMs != null && RuleMatcher.nextFire(t, e.createdAt, e.lastFiredAt, now) == null) {
                expiredOneShots += e.id
                null
            } else {
                e to spec
            }
        }
        expiredOneShots.forEach { id -> runCatching { rules.setEnabled(id, false) } }
        all.forEach { e -> runCatching { cancelAlarm(e.id) } }
        cache.forEach { (e, s) ->
            val t = s.trigger
            if (t is Trigger.Time) runCatching { scheduleAlarm(e, t, now) }.onFailure { Log.w(TAG, "rule ${e.id}: cannot schedule", it) }
        }
        runCatching { syncGeofences(all.map { it.id }, cache.mapNotNull { (e, s) -> (s.trigger as? Trigger.Geofence)?.let { e.id to it } }) }
            .onFailure { Log.w(TAG, "geofence sync failed", it) }
        val mask = RuleMatcher.monitorMask(cache.map { it.second })
        runCatching { RuleMonitorService.setRunning(context, mask) }
    }

    /**
     * Matches [event] against the enabled rules. Time alarms are rescheduled first (and one-shot
     * timers auto-disabled), then each matching rule fires in its own job, so a slow rule (waiting
     * for an unlock) neither delays the next alarm nor blocks other rules. The returned job completes
     * once dispatch is done (not the runs).
     */
    fun onEvent(event: DeviceEvent): Job = scope.launch {
        if (cache.isEmpty()) sync()
        var oneShotMatch: Pair<RuleEntity, RuleSpec>? = null
        if (event is DeviceEvent.Time) rules.get(event.ruleId)?.let { e ->
            val s = decode(e)
            val t = s?.trigger as? Trigger.Time
            if (t != null && e.enabled) {
                if (t.onceAtEpochMs != null) {
                    oneShotMatch = e to s
                    runCatching { rules.setEnabled(e.id, false) }
                    cache = cache.filterNot { it.first.id == e.id }
                } else {
                    runCatching { scheduleAlarm(e.copy(lastFiredAt = System.currentTimeMillis()), t) }
                }
            }
        }
        val candidates = (cache + listOfNotNull(oneShotMatch)).distinctBy { it.first.id }
        val needBattery = candidates.any { (_, s) -> s.conditions.any { it is Condition.Battery || it is Condition.Charging } }
        val batteryState = if (needBattery) battery() else (0 to false)
        val nowTime = LocalTime.now()
        val nowDate = LocalDate.now()
        candidates.filter { (e, s) ->
            runCatching {
                RuleMatcher.matches(e.id, s.trigger, event) &&
                    s.conditions.all { RuleMatcher.holds(it, nowTime, nowDate, batteryState.first, batteryState.second) }
            }.onFailure { Log.w(TAG, "rule ${e.id}: cannot evaluate", it) }.getOrDefault(false)
        }.forEach { (e, s) -> scope.launch { fire(e, s, event) } }
    }

    private fun battery(): Pair<Int, Boolean> {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val pct = i?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1) } ?: 0
        return pct to ((i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0)
    }

    private suspend fun fire(e: RuleEntity, s: RuleSpec, event: DeviceEvent, manual: Boolean = false): ManualRunResult {
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
                    sessions.get().prompt(sessionId, "[Automation \"${s.name}\" triggered by ${describe(s, event)}]\n${a.prompt}", ruleId = e.id)
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

    private suspend fun describe(s: RuleSpec, e: DeviceEvent): String {
        val readable = e is DeviceEvent.Notification &&
            ((s.trigger as? Trigger.Notification)?.packageName != null || e.packageName !in settings.current().notificationBlocked)
        return RuleMatcher.describeEvent(s, e, readable)
    }

    // ---- time --------------------------------------------------------------------------------

    private fun alarmIntent(id: Long) = PendingIntent.getBroadcast(context, id.toInt(),
        Intent(context, TriggerReceiver::class.java).setAction(TriggerReceiver.ACTION_TIME).putExtra("ruleId", id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun cancelAlarm(id: Long) = context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(id))

    private fun scheduleAlarm(e: RuleEntity, t: Trigger.Time, nowMs: Long = System.currentTimeMillis()) {
        val next = RuleMatcher.nextFire(t, e.createdAt, e.lastFiredAt, nowMs) ?: return
        val am = context.getSystemService(AlarmManager::class.java)
        if (am.canExact()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, alarmIntent(e.id))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, alarmIntent(e.id))
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

    private fun geofenceIntent(id: Long) = PendingIntent.getBroadcast(context, (id + 500_000).toInt(),
        Intent(context, TriggerReceiver::class.java).setAction(TriggerReceiver.ACTION_GEOFENCE).putExtra("ruleId", id),
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** One shared PendingIntent for all Play-services geofences (request id = rule id). */
    private val gmsGeofenceIntent by lazy {
        PendingIntent.getBroadcast(context, 499_999, Intent(context, TriggerReceiver::class.java).setAction(TriggerReceiver.ACTION_GEOFENCE_GMS),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /**
     * Re-registers geofences sequentially inside [syncLock] so removal and registration cannot race.
     */
    private suspend fun syncGeofences(allIds: List<Long>, fences: List<Pair<Long, Trigger.Geofence>>) {
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
                fences.forEach { (id, g) ->
                    addGeofence(Geofence.Builder().setRequestId(id.toString()).setCircularRegion(g.lat, g.lng, g.radiusM)
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
        allIds.forEach { id -> runCatching { lm.removeProximityAlert(geofenceIntent(id)) } }
        if (!canLocate) return
        @Suppress("MissingPermission")
        fences.forEach { (id, g) -> runCatching { lm.addProximityAlert(g.lat, g.lng, g.radiusM, -1, geofenceIntent(id)) } }
    }

    private fun cancelGeofence(id: Long) = runCatching {
        if (playServicesGeofencing) LocationServices.getGeofencingClient(context).removeGeofences(listOf(id.toString()))
        else context.getSystemService(LocationManager::class.java).removeProximityAlert(geofenceIntent(id))
    }

    private companion object {
        const val TAG = "TriggerHub"
        const val DAILY_CAP = 50
    }
}

/** Exact alarms need no grant before Android 12, where `canScheduleExactAlarms()` does not exist (#8). */
fun AlarmManager.canExact(): Boolean = android.os.Build.VERSION.SDK_INT < 31 || canScheduleExactAlarms()
