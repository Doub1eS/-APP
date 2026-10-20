package com.lianbei.daily

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.*
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * Main-process integration:
 * - Call refresh(context) on app resume, after restore, and after changes to linked expenses.
 * - MainActivity handles the Boolean extra "subscriptions" in onCreate AND onNewIntent.
 * - Register .SubscriptionReminderReceiver, exported=false, in the default app process.
 *   Its intent-filter needs BOOT_COMPLETED, TIMEZONE_CHANGED, TIME_SET and MY_PACKAGE_REPLACED
 *   (all prefixed android.intent.action.). RECEIVE_BOOT_COMPLETED is required.
 * No exact-alarm permission, foreground service, or additional dependency is needed.
 */
object SubscriptionReminders {
    internal const val ACTION_CHECK = "com.lianbei.daily.SUBSCRIPTION_REMINDER_CHECK"
    internal const val ALARM_REQUEST = 4101
    internal const val NOTIFICATION_ID = 4102
    private const val CHANNEL = "subscription_due"
    private const val PREFS = "subscription_reminders"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val guard = Any()
    private var running: Job? = null
    private var revision = 0L

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)

    fun notificationsAllowed(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun enable(context: Context, enabled: Boolean): Job {
        val app = context.applicationContext
        synchronized(guard) {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
            if (!enabled) cancel(app)
        }
        return refresh(app)
    }

    /** Coalesce bursts of mutations; return the job so callers can await the bounded refresh. */
    fun refresh(context: Context): Job {
        val app = context.applicationContext
        synchronized(guard) {
            revision++
            running?.takeIf { it.isActive }?.let { return it }
            return scope.launch(start = CoroutineStart.LAZY) {
                try {
                    // Includes Room opening/queries and all coalesced passes, below receiver's deadline.
                    withTimeout(6_000) {
                        do {
                            val version = synchronized(guard) { revision }
                            checkNow(app)
                            val again = synchronized(guard) {
                                if (version == revision) {
                                    running = null
                                    false
                                } else true
                            }
                        } while (again)
                    }
                } catch (_: TimeoutCancellationException) {
                    Log.w("SubscriptionReminders", "Reminder check timed out; next check remains scheduled")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w("SubscriptionReminders", "Reminder check failed (${error.javaClass.simpleName}); will retry on next refresh")
                } finally {
                    val job = coroutineContext[Job]
                    synchronized(guard) { if (running === job) running = null }
                }
            }.also { running = it; it.start() }
        }
    }

    private suspend fun checkNow(context: Context) {
        synchronized(guard) {
            if (!isEnabled(context)) {
                cancel(context)
                return
            }
            schedule(context)
            if (!notificationsAllowed(context)) {
                Log.d("SubscriptionReminders", "skip permission-blocked before snapshot")
                context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
                return
            }
        }
        val database = DailyDatabase.get(context)
        val (subscriptions, payments) = database.withTransaction {
            val dao = database.records()
            // Deleted expenses can leave payment links behind. Read only live expense IDs in this
            // same snapshot, without loading every record's content or making one query per link.
            val expenseIds = database.query("SELECT id FROM records WHERE kind = ?", arrayOf("EXPENSE")).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        add(cursor.getString(0))
                    }
                }
            }
            // A one-shot snapshot uses suspend queries, without registering Flow observers.
            dao.allSubscriptions().sortedBy { it.name } to dao.allSubscriptionPayments().filter { it.recordId in expenseIds }
        }
        currentCoroutineContext().ensureActive()
        val today = LocalDate.now()
        val due = SubscriptionReminderRules.due(subscriptions, payments, today)
        Log.d("SubscriptionReminders", "snapshot subscriptions=${subscriptions.size} validPayments=${payments.size} due=${due.size}")
        val coroutine = currentCoroutineContext()
        synchronized(guard) {
            coroutine.ensureActive()
            if (!isEnabled(context)) { cancel(context); return }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "订阅到期提醒", NotificationManager.IMPORTANCE_DEFAULT))
            // Check again after the database suspension: permission or switch may have changed.
            if (!notificationsAllowed(context)) {
                Log.d("SubscriptionReminders", "skip permission-blocked after snapshot")
                manager.cancel(NOTIFICATION_ID)
                return
            }
            val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val sent = preferences.getStringSet("sent", emptySet()).orEmpty()
                .filterTo(HashSet()) { it.startsWith("$today|") }
            if (due.isEmpty()) {
                Log.d("SubscriptionReminders", "cancel no-unpaid-due")
                manager.cancel(NOTIFICATION_ID)
                preferences.edit().putStringSet("sent", sent).apply()
                return
            }
            val unsent = due.filter { it.key !in sent }
            val existing = manager.activeNotifications.firstOrNull { it.id == NOTIFICATION_ID }
            val lines = due.take(20).map {
                "${it.subscription.name.take(40)} · ${if (it.due == today) "今天" else "明天"}到期"
            }
            val text = lines.take(3).joinToString("；") + if (due.size > 3) "，共${due.size}项" else ""
            val expanded = lines.joinToString("\n")
            // Do not resurrect a dismissed notification. Update a still-visible summary if paid/edited.
            if (unsent.isEmpty() && (existing == null ||
                    (existing.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() == text &&
                        existing.notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() == expanded))) {
                Log.d("SubscriptionReminders", "skip same-day-sent visible=${existing != null}")
                return
            }
            val open = PendingIntent.getActivity(context, NOTIFICATION_ID,
                Intent(context, MainActivity::class.java).putExtra("subscriptions", true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("订阅到期提醒")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(expanded))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setOnlyAlertOnce(unsent.isEmpty())
                .setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
            Log.d("SubscriptionReminders", "notify requested due=${due.size} new=${unsent.size} visible=${existing != null}")
            // Never consume keys when the OS blocks notifications.
            if (notificationsAllowed(context)) {
                sent.addAll(unsent.map { it.key })
                preferences.edit().putStringSet("sent", sent).apply()
            }
        }
    }

    private fun alarmIntent(context: Context, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(context, ALARM_REQUEST,
            Intent(context, SubscriptionReminderReceiver::class.java).setAction(ACTION_CHECK),
            flags or PendingIntent.FLAG_IMMUTABLE)

    private fun schedule(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
            SubscriptionReminderRules.nextCheck(ZonedDateTime.now()).toInstant().toEpochMilli(),
            alarmIntent(context, PendingIntent.FLAG_UPDATE_CURRENT)!!)
    }

    private fun cancel(context: Context) {
        alarmIntent(context, PendingIntent.FLAG_NO_CREATE)?.let {
            context.getSystemService(AlarmManager::class.java).cancel(it)
            it.cancel()
        }
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}

/** Always finishes goAsync, including timeout, query failure and unexpected exceptions. */
class SubscriptionReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(SubscriptionReminders.ACTION_CHECK, Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val pending = goAsync()
        receiverScope.launch {
            try {
                withTimeout(7_000) { SubscriptionReminders.refresh(context.applicationContext).join() }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
