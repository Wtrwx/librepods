package me.kavishdevar.librepods.utils

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import me.kavishdevar.librepods.data.Battery
import me.kavishdevar.librepods.data.BatteryComponent
import me.kavishdevar.librepods.data.BatteryStatus
import kotlin.math.roundToInt

/** Publishes the HyperOS battery island from LibrePods' own process and notification identity. */
internal object XiaomiBatteryIslandPublisher {
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val gate by lazy { AirPodsIslandConnectionGate(SystemClock::elapsedRealtime) }
    private val pendingShows = mutableMapOf<String, Runnable>()
    private val pendingCancels = mutableMapOf<String, Runnable>()
    private val names = mutableMapOf<String, String>()

    fun prepare(context: Context) {
        handler.post { createChannel(context.applicationContext) }
    }

    fun connected(context: Context, address: String, name: String) {
        val appContext = context.applicationContext
        handler.post {
            createChannel(appContext)
            names[address] = name
            if (!gate.connected(address)) return@post

            val show = Runnable {
                pendingShows.remove(address)
                showIfReady(appContext, address)
            }
            pendingShows[address] = show
            handler.postDelayed(show, AirPodsIslandConnectionGate.INITIAL_DELAY_MS)
            Log.i(TAG, "AirPods connection armed for LibrePods island: $address")
        }
    }

    fun battery(context: Context, address: String, name: String, batteries: List<Battery>) {
        val level = unifiedLevel(batteries) ?: return
        val appContext = context.applicationContext
        handler.post {
            names[address] = name
            gate.battery(address, level)
            showIfReady(appContext, address)
        }
    }

    fun disconnected(context: Context, address: String) {
        val appContext = context.applicationContext
        handler.post {
            gate.disconnected(address)
            names.remove(address)
            pendingShows.remove(address)?.let(handler::removeCallbacks)
            pendingCancels.remove(address)?.let(handler::removeCallbacks)
            cancel(appContext, address)
        }
    }

    fun clear(context: Context) {
        val appContext = context.applicationContext
        handler.post {
            gate.clear()
            pendingShows.values.forEach(handler::removeCallbacks)
            pendingShows.clear()
            pendingCancels.toMap().forEach { (address, callback) ->
                handler.removeCallbacks(callback)
                cancel(appContext, address)
            }
            pendingCancels.clear()
            names.clear()
        }
    }

    private fun showIfReady(context: Context, address: String) {
        val level = gate.takeReadyBattery(address) ?: return
        try {
            val notifications = context.getSystemService(NotificationManager::class.java)
            if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ||
                !notifications.areNotificationsEnabled()
            ) {
                Log.w(TAG, "LibrePods notifications are disabled; battery island skipped")
                return
            }

            val protocol = Settings.System.getInt(
                context.contentResolver,
                "notification_focus_protocol",
                0,
            )
            if (protocol < 3) {
                Log.i(TAG, "HyperOS island protocol unavailable ($protocol); notification skipped")
                return
            }

            createChannel(context)
            val name = names[address].orEmpty().ifBlank { "AirPods" }
            val icon = createEarphoneIcon(context)
            val extras = Bundle().apply {
                // HyperOS requires this protocol marker even for an island without a
                // notification-center card. The payload disables the card and floating.
                putBoolean("mFocusNotification", true)
                putString("miui.focus.param", XiaomiBatteryIslandPayload.build(name, level))
                putBundle("miui.focus.pics", Bundle().apply {
                    putParcelable(XiaomiBatteryIslandPayload.PICTURE_KEY, icon)
                })
            }
            val contentIntent = PendingIntent.getActivity(
                context,
                0,
                Intent().setClassName(context, "me.kavishdevar.librepods.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(icon)
                .setContentTitle(name)
                .setContentText("剩余电量 $level%")
                .setContentIntent(contentIntent)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setPriority(Notification.PRIORITY_HIGH)
                .setOngoing(true)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setTimeoutAfter(XiaomiBatteryIslandPayload.NOTIFICATION_TIMEOUT_MS)
                .addExtras(extras)
                .build()

            pendingCancels.remove(address)?.let(handler::removeCallbacks)
            notifications.notify(notificationTag(address), NOTIFICATION_ID, notification)
            val cancel = Runnable {
                pendingCancels.remove(address)
                cancel(context, address)
            }
            pendingCancels[address] = cancel
            handler.postDelayed(cancel, XiaomiBatteryIslandPayload.NOTIFICATION_TIMEOUT_MS)
            Log.i(TAG, "LibrePods posted compact AirPods battery island ($level%, no focus card)")
        } catch (e: Exception) {
            Log.w(TAG, "LibrePods could not post battery island", e)
        }
    }

    private fun createChannel(context: Context) {
        try {
            val notifications = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AirPods 超级岛电量",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "AirPods 连接时显示一次电量超级岛，不显示焦点卡片"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            notifications.createNotificationChannel(channel)
        } catch (e: Exception) {
            Log.w(TAG, "Could not create LibrePods island channel", e)
        }
    }

    private fun cancel(context: Context, address: String) {
        try {
            context.getSystemService(NotificationManager::class.java)
                .cancel(notificationTag(address), NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.w(TAG, "Could not cancel LibrePods battery island", e)
        }
    }

    internal fun unifiedLevel(batteries: List<Battery>): Int? {
        val levels = listOf(BatteryComponent.LEFT, BatteryComponent.RIGHT).mapNotNull { component ->
            batteries.find { it.component == component }
                ?.takeIf { it.status != BatteryStatus.DISCONNECTED && it.level in 0..100 }
                ?.level
        }
        return levels.minOrNull()
    }

    private fun notificationTag(address: String) = "librepods.airpods.battery.$address"

    @SuppressLint("UseCompatLoadingForDrawables", "UseKtx")
    private fun createEarphoneIcon(context: Context): Icon {
        return try {
            val drawable = requireNotNull(context.getDrawable(R.drawable.airpods)).mutate()
            drawable.setTint(Color.WHITE)
            val size = 96
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            // The island uses a square icon slot; fit the wider AirPods artwork inside
            // it without stretching its stems vertically.
            val sourceWidth = drawable.intrinsicWidth.coerceAtLeast(1)
            val sourceHeight = drawable.intrinsicHeight.coerceAtLeast(1)
            val scale = minOf(size.toFloat() / sourceWidth, size.toFloat() / sourceHeight)
            val width = (sourceWidth * scale).roundToInt().coerceIn(1, size)
            val height = (sourceHeight * scale).roundToInt().coerceIn(1, size)
            val left = (size - width) / 2
            val top = (size - height) / 2
            drawable.setBounds(left, top, left + width, top + height)
            drawable.draw(Canvas(bitmap))
            Icon.createWithBitmap(bitmap)
        } catch (e: Exception) {
            Log.w(TAG, "Using system Bluetooth icon", e)
            Icon.createWithResource(context, android.R.drawable.stat_sys_data_bluetooth)
        }
    }

    private const val TAG = "LibrePodsIsland"
    private const val CHANNEL_ID = "librepods_airpods_battery_island_v3"
    private const val NOTIFICATION_ID = 9527
}
