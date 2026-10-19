package com.lianbei.daily

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Notification listener living in the `:capture` process, deliberately without UI, database or
 * Compose so that removing the task or the system reclaiming the UI process does not interrupt
 * payment capture. It only parses and enqueues; Room belongs to the default process.
 */
class PaymentListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        CaptureStore.migrateLegacy(this)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connectedHere = true
        CaptureStore.update(this) { values ->
            values["connected"] = "true"
            values["connected_at"] = System.currentTimeMillis().toString()
            values["refused"] = ""
        }
        if (!PaymentSettings.enabled(this)) return
        // Only still-visible recent notifications since opt-in; this is not a historical bill import.
        val cutoff = maxOf(PaymentSettings.enabledAt(this), System.currentTimeMillis() - 86_400_000)
        runCatching { activeNotifications }.getOrNull()
            ?.filter { it.postTime >= cutoff }
            ?.forEach { capture(it) }
    }

    override fun onListenerDisconnected() {
        connectedHere = false
        CaptureStore.update(this) { it["connected"] = "false" }
        super.onListenerDisconnected()
        if (PaymentSettings.enabled(this)) runCatching {
            NotificationListenerService.requestRebind(ComponentName(this, PaymentListener::class.java))
        }
    }

    override fun onDestroy() {
        connectedHere = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) capture(sbn)
    }

    private fun capture(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) {
            if (sbn.id == PaymentNotifications.SELF_TEST_ID && sbn.notification.channelId == PaymentNotifications.SELF_TEST_CHANNEL) {
                CaptureStore.update(this) { it["self_received"] = System.currentTimeMillis().toString() }
                runCatching { cancelNotification(sbn.key) }
            }
            return
        }
        // Cheap package filter before any disk or parser work.
        if (sbn.packageName !in PaymentParser.sources) return
        val session = PaymentSettings.session(this)
        scope.launch {
            PaymentCapture.accept(this@PaymentListener, sbn.packageName, sbn.key, sbn.postTime, sbn.notification, session)
        }
    }

    companion object {
        /** Whether this very process is bound as a listener. Not persisted across processes by design. */
        @Volatile
        var connectedHere = false
            private set

        fun requestRebindNow(context: Context): Boolean = runCatching {
            NotificationListenerService.requestRebind(ComponentName(context, PaymentListener::class.java))
            true
        }.onFailure { android.util.Log.w("LianbeiCapture", "requestRebind failed", it) }.getOrDefault(false)
    }
}

/**
 * Holds the `:capture` process alive with a visible ongoing notification so payments keep being
 * captured after the task is removed or the screen is locked. It performs no polling of its own;
 * it only re-requests the listener binding when this process has not been connected.
 */
class PaymentWatchService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var attempts = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // A state file left by a dead process must never claim a live connection.
        CaptureStore.update(this) { it["connected"] = PaymentListener.connectedHere.toString() }
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!PaymentSettings.enabled(this)) {
            CaptureStore.update(this) { it["watch"] = "false" }
            stopSelf()
            return START_NOT_STICKY
        }
        CaptureStore.update(this) { values ->
            values["watch"] = "true"
            if (values["watch_since"].isNullOrEmpty()) values["watch_since"] = System.currentTimeMillis().toString()
        }
        startForegroundCompat()
        ensureListener()
        return START_STICKY
    }

    /** Removing the task must not stop capture; the user turns it off from the app. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        CaptureStore.update(this) { it["watch"] = "true" }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        CaptureStore.update(this) { it["watch"] = "false" }
        super.onDestroy()
    }

    private fun ensureListener() {
        if (PaymentListener.connectedHere) {
            attempts = 0
            return
        }
        if (attempts >= 4) return
        attempts++
        val requested = PaymentListener.requestRebindNow(this)
        android.util.Log.i("LianbeiCapture", "ensureListener attempt=$attempts requested=$requested connected=${PaymentListener.connectedHere}")
        handler.postDelayed({ if (!PaymentListener.connectedHere) ensureListener() }, 5_000L * attempts)
    }

    private fun startForegroundCompat() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "后台监听支付通知", NotificationManager.IMPORTANCE_MIN).apply {
                description = "保持支付通知识别在划走应用后继续工作，可随时在练呗内关闭。"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).putExtra("payments", true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("正在监听付款通知")
            .setContentText("微信／支付宝的付款通知会先进入待确认。点按查看或关闭。")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, notification)
        }
    }

    companion object {
        const val CHANNEL = "payment_watch"
        const val ID = 203

        fun start(context: Context) {
            CaptureStore.update(context) { it["watch_wanted"] = "true" }
            runCatching { context.startForegroundService(Intent(context, PaymentWatchService::class.java)) }
                .onFailure { CaptureStore.refuse(context, "系统不允许启动后台监听，请在应用信息中检查后台运行权限") }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, PaymentWatchService::class.java)) }
            CaptureStore.update(context) {
                it["watch"] = "false"
                it["watch_wanted"] = "false"
            }
        }
    }
}

/**
 * Restores the watch service after a reboot or an app update. A force stop by the user still stops
 * everything; the app reports that honestly instead of pretending to keep listening.
 */
class PaymentBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!PaymentSettings.enabled(context)) return
        PaymentWatchService.start(context)
        PaymentListener.requestRebindNow(context)
    }
}
