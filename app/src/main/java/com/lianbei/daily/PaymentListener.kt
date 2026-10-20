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
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Notification listener living in the `:capture` process, deliberately without UI, database or
 * Compose to reduce coupling to the UI lifecycle. Android may still stop this process.
 * It only parses and enqueues; Room belongs to the default process.
 */
class PaymentListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        CaptureStore.migrateLegacy(this)
        connectedHere = false
        CaptureStore.update(this) {
            CaptureLiveness.resetForProcess(it, Process.myPid(), connectedHere,
                PaymentWatchService.runningHere && it["watch_wanted"] == "true")
        }
        PaymentWatchService.listenerStateChanged()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connectedHere = true
        CaptureStore.update(this) { values ->
            CaptureLiveness.record(values, "connected", true, Process.myPid())
            values["connected_at"] = System.currentTimeMillis().toString()
            values["refused"] = ""
        }
        PaymentWatchService.listenerStateChanged()
        if (!PaymentSettings.enabled(this)) return
        // Only still-visible recent notifications since opt-in; this is not a historical bill import.
        val cutoff = maxOf(PaymentSettings.enabledAt(this), System.currentTimeMillis() - 86_400_000)
        runCatching { activeNotifications }.getOrNull()
            ?.filter { it.postTime >= cutoff }
            ?.forEach { capture(it) }
    }

    override fun onListenerDisconnected() {
        connectedHere = false
        CaptureStore.update(this) { CaptureLiveness.record(it, "connected", false, Process.myPid()) }
        super.onListenerDisconnected()
        PaymentWatchService.listenerStateChanged()
        if (PaymentSettings.enabled(this) && !PaymentWatchService.runningHere) {
            requestRebindNow(this)
        }
    }

    override fun onDestroy() {
        connectedHere = false
        CaptureStore.update(this) { CaptureLiveness.record(it, "connected", false, Process.myPid()) }
        PaymentWatchService.listenerStateChanged()
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
 * Uses a foreground notification to help the `:capture` process remain available. System policy
 * can still stop it. It performs no liveness polling; binding retries are bounded.
 */
class PaymentWatchService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var attempts = 0
    private var foreground = false
    private var stopping = false
    private var lastStartId = 0
    private var receiverRegistered = false
    private val rebind = Runnable { ensureListener() }
    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_SETTINGS_CHANGED || current !== this@PaymentWatchService) return
            // Broadcasts are only wakeups. A delayed stop must not override a later reenable.
            val raw = CaptureStore.state(this@PaymentWatchService)
            if (raw["enabled"] != "true" || raw["watch_wanted"] != "true") stopAfterForeground()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Fulfil an accepted startForegroundService request even if the user already disabled it.
        // Never let a cross-process stop cancel this required promotion.
        startForegroundCompat()
        // A state file left by a dead process must never claim a live connection.
        CaptureStore.update(this) {
            CaptureLiveness.resetForProcess(it, Process.myPid(), PaymentListener.connectedHere, false)
        }
        current = this
        ContextCompat.registerReceiver(this, settingsReceiver, IntentFilter(ACTION_SETTINGS_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // A newer FGS start may have arrived while an older stop was in flight. Acknowledge
        // every delivered start before considering shutdown, including on an existing instance.
        startForegroundCompat()
        val shouldRun = CaptureStore.updateIf(this) { values ->
            if (values["enabled"] != "true" || values["watch_wanted"] != "true") return@updateIf false
            if (values["watch"] != "true") values["watch_since"] = System.currentTimeMillis().toString()
            CaptureLiveness.record(values, "watch", true, Process.myPid())
            true
        }
        if (!shouldRun) {
            stopAfterForeground()
            return START_NOT_STICKY
        }
        stopping = false
        ensureListener()
        return START_STICKY
    }

    private fun stopAfterForeground() {
        // Before the first onStartCommand there is no safe startId to acknowledge. Its state
        // check will finish the stop; no timer, service start, or stopService probe is needed.
        if (!foreground || lastStartId == 0) return
        stopping = true
        handler.removeCallbacksAndMessages(null)
        CaptureStore.update(this) { CaptureLiveness.record(it, "watch", false, Process.myPid()) }
        // Keep the foreground registration while a newer, undelivered start request exists.
        // stopSelf()/stopService() would cancel that request and can trigger an FGS timeout.
        if (stopSelfResult(lastStartId)) {
            foreground = false
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    override fun onDestroy() {
        stopping = true
        foreground = false
        if (current === this) current = null
        if (receiverRegistered) {
            unregisterReceiver(settingsReceiver)
            receiverRegistered = false
        }
        handler.removeCallbacksAndMessages(null)
        CaptureStore.update(this) { CaptureLiveness.record(it, "watch", false, Process.myPid()) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun ensureListener() {
        // Keep at most one pending retry, even after repeated start commands or callbacks.
        handler.removeCallbacks(rebind)
        if (!foreground || stopping) return
        val raw = CaptureStore.state(this)
        if (raw["enabled"] != "true" || raw["watch_wanted"] != "true" || raw["watch"] != "true") return
        if (PaymentListener.connectedHere) {
            attempts = 0
            return
        }
        if (attempts >= 4) return
        attempts++
        val requested = PaymentListener.requestRebindNow(this)
        android.util.Log.i("LianbeiCapture", "ensureListener attempt=$attempts requested=$requested connected=${PaymentListener.connectedHere}")
        if (attempts < 4) handler.postDelayed(rebind, 5_000L * attempts)
    }

    private fun onListenerStateChanged() {
        // Only the current foreground instance may update its notification. In particular, a
        // listener callback after stop() must not recreate an orphaned ongoing notification.
        if (current !== this || !foreground || stopping) return
        val raw = CaptureStore.state(this)
        if (raw["watch"] != "true" || raw["watch_wanted"] != "true") return
        getSystemService(NotificationManager::class.java).notify(ID, foregroundNotification(raw))
        ensureListener()
    }

    private fun foregroundNotification(raw: Map<String, String>): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "后台监听支付通知", NotificationManager.IMPORTANCE_MIN).apply {
                description = "帮助维持支付通知监听，可在练呗内关闭；系统限制仍可能中断监听。"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).putExtra("payments", true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val enabled = raw["enabled"] == "true" && raw["watch_wanted"] == "true"
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(when {
                !enabled -> "后台监听正在停止"
                PaymentListener.connectedHere -> "正在监听付款通知"
                else -> "付款通知监听尚未连接"
            })
            .setContentText(if (enabled && PaymentListener.connectedHere)
                "识别到的付款通知会进入待确认。点按查看或关闭。"
            else "暂时无法确认自动识别可用，点按查看状态与设置。")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    private fun startForegroundCompat() {
        val notification = foregroundNotification(CaptureStore.state(this))
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, notification)
        }
        foreground = true
    }

    companion object {
        const val CHANNEL = "payment_watch"
        const val ID = 203
        internal const val ACTION_SETTINGS_CHANGED = "com.lianbei.daily.PAYMENT_WATCH_SETTINGS_CHANGED"
        private var current: PaymentWatchService? = null

        internal val runningHere: Boolean get() = current?.let { it.foreground && !it.stopping } == true

        internal fun listenerStateChanged() {
            current?.onListenerStateChanged()
        }

        fun start(context: Context) {
            CaptureStore.update(context) { it["watch_wanted"] = "true" }
            runCatching { context.startForegroundService(Intent(context, PaymentWatchService::class.java)) }
                .onFailure { CaptureStore.refuse(context, "系统不允许启动后台监听，请在应用信息中检查后台运行权限") }
        }

        fun stop(context: Context) {
            CaptureStore.update(context) {
                CaptureLiveness.record(it, "watch", false, Process.myPid())
                it["watch_wanted"] = "false"
            }
            // Only an existing instance receives this broadcast. If creation is still pending,
            // onStartCommand reads watch_wanted=false after first registering as foreground.
            context.sendBroadcast(Intent(ACTION_SETTINGS_CHANGED).setPackage(context.packageName)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND))
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
        val raw = CaptureStore.state(context)
        if (raw["enabled"] != "true") return
        if (raw["watch_wanted"] != "false") PaymentWatchService.start(context)
        PaymentListener.requestRebindNow(context)
    }
}
