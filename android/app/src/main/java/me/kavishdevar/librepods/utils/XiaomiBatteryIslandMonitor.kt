package me.kavishdevar.librepods.utils

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import me.kavishdevar.librepods.R

/** Runs only in com.xiaomi.bluetooth's main process, using the host's notification identity. */
internal class XiaomiBatteryIslandMonitor private constructor(
    private val context: Application,
    private val isAirPods: (BluetoothDevice) -> Boolean,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val gate = AirPodsIslandConnectionGate(SystemClock::elapsedRealtime)
    private val pendingReads = mutableMapOf<String, Runnable>()
    private val pendingCancels = mutableMapOf<String, Runnable>()
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val icon by lazy { createEarphoneIcon() }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Never allow a missing permission, malformed parcel, or vendor failure to crash the host.
            try {
                if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                    if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) in
                        listOf(BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF)) {
                        clear()
                    }
                    return
                }
                val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    ?: return
                val address = device.address
                when (intent.action) {
                    BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> when (
                        intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    ) {
                        BluetoothProfile.STATE_CONNECTED -> connected(device)
                        BluetoothProfile.STATE_DISCONNECTED -> disconnected(address)
                    }
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> disconnected(address)
                    ACTION_BATTERY -> {
                        gate.battery(address, intent.getIntExtra(EXTRA_LEVEL, -1))
                        showIfReady(device)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Battery island event ignored", e)
            }
        }
    }

    private fun register() {
        val filter = IntentFilter().apply {
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(ACTION_BATTERY)
        }
        // Bluetooth broadcasts can originate from a privileged UID other than system_server.
        context.registerReceiver(receiver, filter, Manifest.permission.BLUETOOTH_CONNECT,
            handler, Context.RECEIVER_EXPORTED)
        Log.i(TAG, "Monitoring AirPods connections in ${context.packageName}")
    }

    private fun readAlreadyConnectedDevices() {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        try {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    try {
                        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                            proxy.connectedDevices.forEach(::connected)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not read initial A2DP connections", e)
                    } finally {
                        adapter.closeProfileProxy(profile, proxy)
                    }
                }
                override fun onServiceDisconnected(profile: Int) = Unit
            }, BluetoothProfile.A2DP)
        } catch (e: Exception) {
            Log.w(TAG, "Could not request A2DP connection snapshot", e)
        }
    }

    private fun connected(device: BluetoothDevice) {
        if (!isAirPods(device) || !gate.connected(device.address)) return
        val address = device.address
        // Query once after the connection settles. Later battery broadcasts may complete the
        // session for 15 seconds, but never create a new session or reopen a consumed one.
        val read = Runnable {
            pendingReads.remove(address)
            try {
                val level = BluetoothDevice::class.java.getMethod("getBatteryLevel").invoke(device) as? Int
                if (level != null) gate.battery(address, level)
            } catch (e: Exception) {
                Log.d(TAG, "System battery unavailable; waiting for battery broadcast: ${e.message}")
            }
            showIfReady(device)
        }
        pendingReads[address] = read
        handler.postDelayed(read, AirPodsIslandConnectionGate.INITIAL_DELAY_MS)
    }

    private fun showIfReady(device: BluetoothDevice) {
        val level = gate.takeReadyBattery(device.address) ?: return
        try {
            if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ||
                !notifications.areNotificationsEnabled()) {
                Log.w(TAG, "Host notifications are disabled; battery island skipped")
                return
            }
            val name = if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                device.alias ?: device.name ?: "AirPods"
            } else "AirPods"
            val channel = NotificationChannel(CHANNEL_ID, "AirPods 超级岛电量", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
            }
            notifications.createNotificationChannel(channel)
            val extras = Bundle().apply {
                putString("miui.focus.param", XiaomiBatteryIslandPayload.build(name, level))
                putBundle("miui.focus.pics", Bundle().apply {
                    putParcelable(XiaomiBatteryIslandPayload.PICTURE_KEY, icon)
                })
            }
            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(icon)
                .setContentTitle(name)
                .setContentText("剩余电量 $level%")
                .setCategory(Notification.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                // System-side expiry also works if the injected host process is killed.
                .setTimeoutAfter(XiaomiBatteryIslandPayload.NOTIFICATION_TIMEOUT_MS)
                .addExtras(extras)
                .build()
            val address = device.address
            pendingCancels.remove(address)?.let(handler::removeCallbacks)
            notifications.notify(notificationTag(address), NOTIFICATION_ID, notification)
            val cancel = Runnable {
                pendingCancels.remove(address)
                cancelNotification(address)
            }
            pendingCancels[address] = cancel
            handler.postDelayed(cancel, XiaomiBatteryIslandPayload.NOTIFICATION_TIMEOUT_MS)
            Log.i(TAG, "Posted AirPods battery island ($level%)")
        } catch (e: Exception) {
            Log.w(TAG, "Could not post battery island", e)
        }
    }

    private fun disconnected(address: String) {
        gate.disconnected(address)
        pendingReads.remove(address)?.let(handler::removeCallbacks)
        pendingCancels.remove(address)?.let {
            handler.removeCallbacks(it)
            cancelNotification(address)
        }
    }

    private fun clear() {
        gate.clear()
        pendingReads.values.forEach(handler::removeCallbacks)
        pendingReads.clear()
        pendingCancels.toMap().forEach { (address, callback) ->
            handler.removeCallbacks(callback)
            cancelNotification(address)
        }
        pendingCancels.clear()
    }

    private fun cancelNotification(address: String) {
        try {
            // A private tag avoids cancelling Xiaomi's own Bluetooth notifications.
            notifications.cancel(notificationTag(address), NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.w(TAG, "Could not cancel battery island", e)
        }
    }

    private fun notificationTag(address: String) = "librepods.airpods.battery.$address"

    // Framework drawing APIs work at our minimum SDK and avoid AppCompat setup in the host.
    @SuppressLint("UseCompatLoadingForDrawables", "UseKtx")
    private fun createEarphoneIcon(): Icon {
        return try {
            // Module context is used only to load artwork; notifications use the host context.
            val resources = context.createPackageContext("me.kavishdevar.librepods", 0)
            val drawable = requireNotNull(resources.getDrawable(R.drawable.airpods)).mutate()
            drawable.setTint(Color.WHITE)
            val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, 96, 96)
            drawable.draw(Canvas(bitmap))
            Icon.createWithBitmap(bitmap)
        } catch (e: Exception) {
            Log.w(TAG, "Using system Bluetooth icon", e)
            Icon.createWithResource(context, android.R.drawable.stat_sys_data_bluetooth)
        }
    }

    companion object {
        private const val TAG = "LibrePodsIsland"
        private const val CHANNEL_ID = "librepods_airpods_battery_island_v1"
        private const val NOTIFICATION_ID = 9527
        private const val ACTION_BATTERY = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        private const val EXTRA_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"
        private var instance: XiaomiBatteryIslandMonitor? = null

        fun start(context: Application, isAirPods: (BluetoothDevice) -> Boolean) {
            if (instance != null || context.packageName != "com.xiaomi.bluetooth") return
            try {
                // Do not emit ordinary notifications on older HyperOS versions without an island.
                val protocol = Settings.System.getInt(context.contentResolver, "notification_focus_protocol", 0)
                if (protocol < 3) {
                    Log.i(TAG, "HyperOS island protocol unavailable ($protocol); monitor not started")
                    return
                }
                val monitor = XiaomiBatteryIslandMonitor(context, isAirPods)
                monitor.register()
                instance = monitor
                monitor.readAlreadyConnectedDevices()
            } catch (e: Exception) {
                Log.w(TAG, "Could not start battery island monitor", e)
            }
        }
    }
}
