package io.github.mam15mon.dsh.core.trigger

import android.Manifest
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import androidx.core.content.ContextCompat
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.mam15mon.dsh.R
import io.github.mam15mon.dsh.core.platform.Notifications

/**
 * Lightweight foreground service that only exists while some rule needs runtime-registered
 * broadcasts (Wi-Fi, Bluetooth, power, battery, headset, screen). It never loads the engine.
 */
class RuleMonitorService : Service() {
    private var lastBattery: Int? = null
    private var lastSsid: String? = null
    private var wifiConnected = false
    /** False until the first network callback: the current Wi-Fi is the baseline, not a "connected" event. */
    private var wifiSeeded = false
    private var activeMask = 0
    private var stdMask = 0
    private var btRegistered = false
    private var wifiRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // Sticky broadcasts (headset, battery) replay the current state on registration: that is the
            // baseline, not a transition (otherwise every monitor start fires "unplugged"/"battery" rules).
            val sticky = isInitialStickyBroadcast
            val ev = when (i.action) {
                Intent.ACTION_POWER_CONNECTED -> DeviceEvent.Power(true)
                Intent.ACTION_POWER_DISCONNECTED -> DeviceEvent.Power(false)
                Intent.ACTION_HEADSET_PLUG -> if (sticky) return else DeviceEvent.Headset(i.getIntExtra("state", 0) == 1)
                Intent.ACTION_SCREEN_ON -> DeviceEvent.Screen("on")
                Intent.ACTION_SCREEN_OFF -> DeviceEvent.Screen("off")
                Intent.ACTION_USER_PRESENT -> DeviceEvent.Screen("unlock")
                Intent.ACTION_BATTERY_CHANGED -> {
                    val pct = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                    val charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                    val previous = lastBattery
                    if (pct == previous) return
                    lastBattery = pct
                    if (sticky || previous == null) return
                    DeviceEvent.Battery(pct, previous, charging)
                }
                else -> return
            }
            TriggerEvents.emit(ev)
        }
    }

    /**
     * Bluetooth ACL broadcasts come from the Bluetooth process (its own uid), which a NOT_EXPORTED
     * receiver refuses; they are also protected by BLUETOOTH_CONNECT on Android 12+, so only the
     * system can send them to us. Hence a separate, exported receiver.
     */
    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != BluetoothDevice.ACTION_ACL_CONNECTED && i.action != BluetoothDevice.ACTION_ACL_DISCONNECTED) return
            @Suppress("DEPRECATION") val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            val canName = Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            val name = if (canName) runCatching { d?.name }.getOrNull() else null
            TriggerEvents.emit(DeviceEvent.Bluetooth(i.action == BluetoothDevice.ACTION_ACL_CONNECTED, name))
        }
    }

    private fun onWifi(caps: NetworkCapabilities) {
        @Suppress("DEPRECATION")
        val info = if (Build.VERSION.SDK_INT >= 31) caps.transportInfo as? WifiInfo
            else runCatching { getSystemService(WifiManager::class.java).connectionInfo }.getOrNull()
        val ssid = info?.ssid?.trim('"')?.takeIf { it != "<unknown ssid>" && it.isNotEmpty() }
        if (!wifiSeeded) { wifiSeeded = true; wifiConnected = true; lastSsid = ssid; return }
        if (!wifiConnected || (ssid != null && ssid != lastSsid)) {
            wifiConnected = true
            lastSsid = ssid
            TriggerEvents.emit(DeviceEvent.Wifi(true, ssid))
        }
    }

    // NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) is API 31+; constructing it on 29/30 crashed the service.
    private val netCallback: ConnectivityManager.NetworkCallback =
        if (Build.VERSION.SDK_INT >= 31) object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = onWifi(caps)
            override fun onLost(network: Network) = onWifiLost()
        } else object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = onWifi(caps)
            override fun onLost(network: Network) = onWifiLost()
        }

    private fun onWifiLost() {
        if (!wifiSeeded || wifiConnected) {
            wifiSeeded = true
            wifiConnected = false
            val prev = lastSsid
            lastSsid = null
            TriggerEvents.emit(DeviceEvent.Wifi(false, prev))
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        runCatching {
            startForeground(Notifications.ID_MONITOR, NotificationCompat.Builder(this, Notifications.CHANNEL_MONITOR)
                .setSmallIcon(R.drawable.ic_stat_dsh).setContentTitle(getString(R.string.monitor_running)).setOngoing(true).setSilent(true).build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }.onFailure { android.util.Log.w("RuleMonitorService", "startForeground refused", it); lastStartRefused = true; running = false; stopSelf(); return }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) return START_NOT_STICKY
        val mask = intent?.getIntExtra(EXTRA_MASK, currentMask) ?: currentMask
        if (mask == 0) {
            stopSelf()
            return START_NOT_STICKY
        }
        applyMask(mask)
        return START_STICKY
    }

    private fun applyMask(mask: Int) {
        if (mask == activeMask) return
        val nextStd = mask and (RuleMatcher.FLAG_POWER or RuleMatcher.FLAG_BATTERY or RuleMatcher.FLAG_HEADSET or RuleMatcher.FLAG_SCREEN)
        if (nextStd != stdMask) {
            if (stdMask != 0) runCatching { unregisterReceiver(receiver) }
            if ((nextStd and RuleMatcher.FLAG_BATTERY) == 0) lastBattery = null
            if (nextStd != 0) {
                val filter = IntentFilter().apply {
                    if ((nextStd and RuleMatcher.FLAG_POWER) != 0) {
                        addAction(Intent.ACTION_POWER_CONNECTED)
                        addAction(Intent.ACTION_POWER_DISCONNECTED)
                    }
                    if ((nextStd and RuleMatcher.FLAG_HEADSET) != 0) addAction(Intent.ACTION_HEADSET_PLUG)
                    if ((nextStd and RuleMatcher.FLAG_SCREEN) != 0) {
                        addAction(Intent.ACTION_SCREEN_ON)
                        addAction(Intent.ACTION_SCREEN_OFF)
                        addAction(Intent.ACTION_USER_PRESENT)
                    }
                    if ((nextStd and RuleMatcher.FLAG_BATTERY) != 0) addAction(Intent.ACTION_BATTERY_CHANGED)
                }
                ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            }
            stdMask = nextStd
        }

        val wantBt = (mask and RuleMatcher.FLAG_BLUETOOTH) != 0
        if (wantBt != btRegistered) {
            if (wantBt) {
                ContextCompat.registerReceiver(this, btReceiver, IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_ACL_CONNECTED); addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                }, ContextCompat.RECEIVER_EXPORTED)
                btRegistered = true
            } else {
                runCatching { unregisterReceiver(btReceiver) }
                btRegistered = false
            }
        }

        val wantWifi = (mask and RuleMatcher.FLAG_WIFI) != 0
        if (wantWifi != wifiRegistered) {
            if (wantWifi) {
                runCatching {
                    val cm = getSystemService(ConnectivityManager::class.java)
                    @Suppress("DEPRECATION")
                    val alreadyOnWifi = cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
                    wifiConnected = alreadyOnWifi
                    wifiSeeded = !alreadyOnWifi
                    lastSsid = null
                    cm.registerNetworkCallback(
                        NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), netCallback)
                    wifiRegistered = true
                }.onFailure { android.util.Log.w("RuleMonitorService", "network callback", it) }
            } else {
                runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netCallback) }
                wifiRegistered = false
                wifiConnected = false
                wifiSeeded = false
                lastSsid = null
            }
        }
        activeMask = mask
    }

    override fun onDestroy() {
        running = false
        if (stdMask != 0) runCatching { unregisterReceiver(receiver) }
        if (btRegistered) runCatching { unregisterReceiver(btReceiver) }
        if (wifiRegistered) runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netCallback) }
        stdMask = 0
        btRegistered = false
        wifiRegistered = false
        activeMask = 0
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val EXTRA_MASK = "monitor_mask"
        @Volatile private var currentMask = 0
        /** Whether the monitor is up (Doctor). */
        @Volatile var running = false
            private set
        /** Set when Android refused to start it from the background (Doctor explains the battery exemption). */
        @Volatile var lastStartRefused = false
            private set

        fun setRunning(context: Context, mask: Int) {
            currentMask = mask
            val i = Intent(context, RuleMonitorService::class.java).putExtra(EXTRA_MASK, mask)
            if (mask != 0) {
                lastStartRefused = runCatching { context.startForegroundService(i) }
                    .onFailure { android.util.Log.w("RuleMonitorService", "start refused", it) }.isFailure
            } else context.stopService(i)
        }
    }
}
