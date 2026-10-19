package com.lianbei.daily

import android.app.*
import android.content.*
import android.graphics.drawable.Icon
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.*
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object PaymentSettings {
    fun enabled(c: Context) = c.getSharedPreferences("payments", 0).getBoolean("enabled", false)
    fun enable(c: Context, value: Boolean) {
        val prefs=c.getSharedPreferences("payments",0)
        prefs.edit().putBoolean("enabled",value).putString("session",java.util.UUID.randomUUID().toString()).apply {
            if(value && !prefs.getBoolean("enabled",false)) putLong("enabled_at",System.currentTimeMillis())
        }.apply()
    }
    fun session(c:Context)=c.getSharedPreferences("payments",0).getString("session","legacy").orEmpty()
    fun enabledAt(c:Context)=c.getSharedPreferences("payments",0).getLong("enabled_at",System.currentTimeMillis())
}

class PaymentListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onListenerConnected() {
        super.onListenerConnected()
        PaymentDiagnostics.connected.value=true
        if(PaymentSettings.enabled(this)) {
            // Only still-visible recent notifications since opt-in; this is not a historical bill import.
            val cutoff=maxOf(PaymentSettings.enabledAt(this),System.currentTimeMillis()-86_400_000)
            runCatching { activeNotifications }.getOrNull()?.filter {it.postTime>=cutoff}?.forEach {capture(it)}
        }
    }
    override fun onListenerDisconnected() {
        PaymentDiagnostics.connected.value=false
        super.onListenerDisconnected()
        if(PaymentSettings.enabled(this)) runCatching { requestRebind(ComponentName(this,PaymentListener::class.java)) }
    }
    override fun onDestroy() {
        PaymentDiagnostics.connected.value=false
        scope.cancel()
        super.onDestroy()
    }
    override fun onNotificationPosted(sbn: StatusBarNotification) { capture(sbn) }
    private fun capture(sbn:StatusBarNotification) {
        if(!PaymentSettings.enabled(this)) return
        if(sbn.packageName==packageName && sbn.id==PaymentNotifications.SELF_TEST_ID && sbn.notification.channelId==PaymentNotifications.SELF_TEST_CHANNEL) {
            PaymentDiagnostics.prefs(this).edit().putLong("self_received",System.currentTimeMillis()).apply()
            runCatching {cancelNotification(sbn.key)}
            return
        }
        if(sbn.packageName !in PaymentParser.sources) return
        val session=PaymentSettings.session(this)
        scope.launch {PaymentCapture.accept(this@PaymentListener,DailyDatabase.get(this@PaymentListener).records(),sbn.packageName,sbn.key,sbn.postTime,sbn.notification,session)}
    }
}

object PaymentNotifications {
    const val SELF_TEST_ID=201
    const val SELF_TEST_CHANNEL="payment_listener_check"
    fun selfTest(c:Context):Boolean {
        val manager=c.getSystemService(NotificationManager::class.java)
        if(!manager.areNotificationsEnabled()) return false
        manager.createNotificationChannel(NotificationChannel(SELF_TEST_CHANNEL,"通知监听检查",NotificationManager.IMPORTANCE_LOW))
        if(manager.getNotificationChannel(SELF_TEST_CHANNEL)?.importance==NotificationManager.IMPORTANCE_NONE) return false
        PaymentDiagnostics.prefs(c).edit().putLong("self_requested",System.currentTimeMillis()).putLong("self_received",0).apply()
        return runCatching {
            manager.notify(SELF_TEST_ID,Notification.Builder(c,SELF_TEST_CHANNEL).setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle("练呗通知监听检查").setContentText("用于检查通知是否送达监听服务，不会生成消费记录。").setTimeoutAfter(15_000).build())
            true
        }.getOrDefault(false)
    }
    const val CHANNEL = "payment_confirmations"
    fun cancel(c: Context, id: String) = c.getSystemService(NotificationManager::class.java).cancel(id, 200)
    fun show(c: Context, p: PendingPayment) {
        val manager = c.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "支付待确认", NotificationManager.IMPORTANCE_HIGH).apply { lockscreenVisibility = Notification.VISIBILITY_PRIVATE })
        if (!manager.areNotificationsEnabled()) return
        try { manager.notify(p.id, 200, build(c, p)) } catch (_: SecurityException) { /* Pending inbox remains available. */ }
    }
    fun build(c: Context, p: PendingPayment, protocol: Int = Settings.System.getInt(c.contentResolver, "notification_focus_protocol", 0)): Notification {
        val icon = Icon.createWithResource(c, android.R.drawable.ic_menu_edit)
        fun action(actionName: String, label: String): Notification.Action {
            val intent = Intent(c, PaymentActionReceiver::class.java).setAction(actionName)
                .setData(android.net.Uri.parse("lianbei://payment/${p.id}/$actionName")).putExtra("payment_id", p.id)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            val pending = PendingIntent.getBroadcast(c, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            return Notification.Action.Builder(icon, label, pending).build()
        }
        val confirm = action("confirm", "确认入账")
        val ignore = action("ignore", "忽略")
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java).putExtra("payments", true)
            .setData(android.net.Uri.parse("lianbei://pending/${p.id}")), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(c, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("待确认支出 ¥${RecordRules.money(p.cents)}")
            .setContentText("${p.source} · ${p.merchant}，点击可分类")
            .setContentIntent(open).setVisibility(Notification.VISIBILITY_PRIVATE).setOnlyAlertOnce(true)
            .addAction(confirm).addAction(ignore).setAutoCancel(false)
        // Official OS2/OS3 protocol. The OS decides eligibility; standard notification remains usable.
        if (protocol >= 2) {
            val picKey = "miui.focus.pic_payment"
            val pic = JSONObject().put("type", 1).put("pic", picKey)
            val params = JSONObject().put("protocol", 1).put("business", "payment_confirmation")
                .put("updatable", false).put("filterWhenNoPermission", false)
                .put("ticker", "待确认支出").put("aodTitle", "有一笔支出待确认")
                .put("baseInfo", JSONObject().put("type", 2).put("title", "待确认支出 ¥${RecordRules.money(p.cents)}").put("content", "${p.source} · ${p.merchant}"))
                .put("picInfo", JSONObject().put("type", 1).put("pic", picKey))
                .put("hintInfo", JSONObject().put("type", 2).put("content", "确认入账").put("title", "其他").put("actionInfo", JSONObject().put("action", "miui.focus.action_confirm")))
                .put("param_island", JSONObject().put("islandProperty", 2)
                    .put("bigIslandArea", JSONObject().put("imageTextInfoLeft", JSONObject().put("type", 1).put("picInfo", pic)
                        .put("textInfo", JSONObject().put("title", "待确认支出"))).put("picInfo", pic))
                    .put("smallIslandArea", JSONObject().put("picInfo", pic)))
            builder.addExtras(Bundle().apply {
                putString("miui.focus.param", JSONObject().put("param_v2", params).toString())
                putBundle("miui.focus.actions", Bundle().apply { putParcelable("miui.focus.action_confirm", confirm); putParcelable("miui.focus.action_ignore", ignore) })
                putBundle("miui.focus.pics", Bundle().apply { putParcelable(picKey, icon) })
            })
        }
        return builder.build()
    }
}

class PaymentActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("payment_id") ?: return
        if (intent.action !in setOf("confirm", "ignore")) return
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = DailyDatabase.get(context).records()
                if (intent.action == "confirm") dao.confirm(id) else dao.resolve(id, "ignored")
                PaymentNotifications.cancel(context, id)
            } catch (_: Exception) {
                // Keep the pending entry and notification available for a later retry.
            } finally { result.finish() }
        }
    }
}
