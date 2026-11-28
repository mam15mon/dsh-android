package io.github.mam15mon.dsh.core.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.provider.Telephony
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import dagger.hilt.android.AndroidEntryPoint
import io.github.mam15mon.dsh.core.engine.EngineKeepAlive
import io.github.mam15mon.dsh.core.engine.EngineManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** Time alarms and proximity alerts (internal, not exported). */
@AndroidEntryPoint
class TriggerReceiver : BroadcastReceiver() {
    @Inject lateinit var hub: TriggerHub

    override fun onReceive(context: Context, intent: Intent) {
        val jobs = runCatching {
            when (intent.action) {
                ACTION_TIME -> listOf(hub.onEvent(DeviceEvent.Time(intent.getLongExtra("ruleId", -1), intent.getIntExtra("triggerIndex", 0))))
                ACTION_GEOFENCE -> listOf(hub.onEvent(DeviceEvent.Geofence(
                    intent.getLongExtra("ruleId", -1),
                    intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, true),
                    intent.getIntExtra("triggerIndex", 0),
                )))
                ACTION_GEOFENCE_GMS -> {
                    val ev = GeofencingEvent.fromIntent(intent)
                    if (ev == null || ev.hasError()) emptyList() else {
                        val entering = ev.geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER
                        ev.triggeringGeofences.orEmpty().mapNotNull { g ->
                            RuleMatcher.parseGeofenceRequestId(g.requestId)?.let { (id, idx) ->
                                hub.onEvent(DeviceEvent.Geofence(id, entering, idx))
                            }
                        }
                    }
                }
                else -> emptyList()
            }
        }.getOrElse { android.util.Log.e("TriggerReceiver", "dispatch failed", it); emptyList() }
        if (jobs.isEmpty()) return
        // Keep the (possibly alarm-cold-started) process alive until the next alarm is scheduled and
        // the runs are launched; aggressive ROMs kill a bare cached process right after onReceive.
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try { withTimeoutOrNull(9_000) { jobs.joinAll() } } finally { pending.finish() }
        }
    }

    companion object {
        const val ACTION_TIME = "io.github.mam15mon.dsh.TRIGGER_TIME"
        const val ACTION_GEOFENCE = "io.github.mam15mon.dsh.TRIGGER_GEOFENCE"
        const val ACTION_GEOFENCE_GMS = "io.github.mam15mon.dsh.TRIGGER_GEOFENCE_GMS"
    }
}

/**
 * Re-registers all automations after boot or app update. Also wakes `:main` when Android re-created the
 * engine's 引擎常驻 service ([EngineKeepAlive.ACTION_RESTORE]); after boot / update DshApp's start restores it.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var hub: TriggerHub
    @Inject lateinit var engine: dagger.Lazy<EngineManager>

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == EngineKeepAlive.ACTION_RESTORE) { engine.get().keepAlive(restored = true); return }
        if (intent.action !in ACTIONS) return
        val isBoot = intent.action == Intent.ACTION_BOOT_COMPLETED
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                runCatching { hub.sync() }.onFailure { android.util.Log.e("BootReceiver", "sync failed", it) }
                if (isBoot) {
                    withTimeoutOrNull(8_000) { runCatching { hub.onEvent(DeviceEvent.Boot).join() } }
                }
            } finally { pending.finish() }
        }
    }

    private companion object {
        // Exact-alarm access granted or clock/timezone changed: re-sync so time rules stay accurate.
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )
    }
}

/** Incoming SMS broadcast receiver (exported with BROADCAST_SMS permission so only the telephony stack can deliver). */
@AndroidEntryPoint
class SmsReceiver : BroadcastReceiver() {
    @Inject lateinit var hub: TriggerHub

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val msgs = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }.getOrNull().orEmpty()
        if (msgs.isEmpty()) return
        val sender = msgs.firstNotNullOfOrNull { it.originatingAddress } ?: ""
        val body = msgs.joinToString("") { it.messageBody.orEmpty() }
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                withTimeoutOrNull(8_000) { hub.onEvent(DeviceEvent.Sms(sender, body)).join() }
            } finally {
                pending.finish()
            }
        }
    }
}
