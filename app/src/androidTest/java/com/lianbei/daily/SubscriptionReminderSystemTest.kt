package com.lianbei.daily

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import com.lianbei.daily.SubscriptionNotificationTestSupport.awaitState
import com.lianbei.daily.SubscriptionNotificationTestSupport.setPermission

/** Uses the real app database, AlarmManager and NotificationManager; run on an isolated emulator. */
class SubscriptionReminderSystemTest {
    @Test fun deletedExpenseRestoresUnseenReminderUpdatesVisibleSummaryAndDoesNotResurrectDismissed() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        val dao = DailyDatabase.get(context).records()
        val today = LocalDate.now().toString()
        val id = "deleted-expense-${System.nanoTime()}"
        val keeper = "$id-keeper"
        val wasEnabled = SubscriptionReminders.isEnabled(context)
        val wasAllowed = manager.areNotificationsEnabled()
        fun notification() = manager.activeNotifications.firstOrNull { it.id == SubscriptionReminders.NOTIFICATION_ID }?.notification
        fun text() = notification()?.extras?.getCharSequence("android.bigText")?.toString().orEmpty()
        suspend fun awaitText(stage: String, present: Boolean) {
            awaitState(context, stage) {
                notification() != null && text().contains("删账恢复会员") == present
            }
        }
        suspend fun deleteExpense() {
            dao.delete(checkNotNull(dao.record("subscription-$id-$today")))
            assertNotNull("Exercise the retained stale link", dao.subscriptionPayment(id, today))
        }
        try {
            SubscriptionReminders.enable(context, false).join()
            awaitState(context, "deleted-expense/setup-cancelled") { notification() == null }
            setPermission(context, true)
            dao.saveSubscription(Subscription(id, "删账恢复会员", 100, "MONTH", today))
            dao.confirmSubscription(id, today, 100, today)
            SubscriptionReminders.enable(context, true).join()
            assertFalse(text().contains("删账恢复会员"))

            // This due date has never been notified: deleting the actual expense must restore it.
            deleteExpense()
            SubscriptionReminders.refresh(context).join()
            awaitText("deleted-expense/first-expense-deleted-posted", true)

            // Keep the aggregate visible while paying then removing the target expense again.
            dao.saveSubscription(Subscription(keeper, "摘要保留会员", 100, "MONTH", today))
            dao.confirmSubscription(id, today, 100, today)
            SubscriptionReminders.refresh(context).join()
            awaitText("deleted-expense/confirmed-removed-from-summary", false)
            assertTrue(text().contains("摘要保留会员"))
            deleteExpense()
            SubscriptionReminders.refresh(context).join()
            awaitText("deleted-expense/second-expense-deleted-summary-updated", true)
            assertTrue("An already-seen due date updates silently",
                checkNotNull(notification()).flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE != 0)

            // Cancelling models the OS state after dismissing the aggregate. A stale payment link
            // must neither suppress an unseen reminder nor erase the seen key and resurrect this one.
            manager.cancel(SubscriptionReminders.NOTIFICATION_ID)
            awaitState(context, "deleted-expense/dismissed") { notification() == null }
            dao.confirmSubscription(id, today, 100, today)
            SubscriptionReminders.refresh(context).join()
            deleteExpense()
            SubscriptionReminders.refresh(context).join()
            // NotificationManager posts asynchronously; observe for accidental delayed re-posts too.
            repeat(10) { delay(100); assertNull("Dismissed same-day reminders stay dismissed", notification()) }
        } finally {
            listOf(id, keeper).forEach {
                dao.deleteSubscription(it)
                dao.record("subscription-$it-$today")?.let { record -> dao.delete(record) }
                dao.removeSubscriptionPayment(it, today)
            }
            SubscriptionReminders.enable(context, false).join()
            setPermission(context, wasAllowed)
            SubscriptionReminders.enable(context, wasEnabled).join()
        }
    }

    @Test fun paymentLinkToNonExpenseDoesNotSuppressNotification() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        val dao = DailyDatabase.get(context).records()
        val today = LocalDate.now().toString()
        val id = "wrong-kind-${System.nanoTime()}"
        val recordId = "subscription-$id-$today"
        val wasEnabled = SubscriptionReminders.isEnabled(context)
        val wasAllowed = manager.areNotificationsEnabled()
        try {
            SubscriptionReminders.enable(context, false).join()
            awaitState(context, "wrong-kind/setup-cancelled") {
                manager.activeNotifications.none { it.id == SubscriptionReminders.NOTIFICATION_ID }
            }
            setPermission(context, true)
            dao.saveSubscription(Subscription(id, "无效类型会员", 100, "MONTH", today))
            dao.confirmSubscription(id, today, 100, today)
            dao.save(checkNotNull(dao.record(recordId)).copy(kind = "NOTE"))
            SubscriptionReminders.enable(context, true).join()
            awaitState(context, "wrong-kind/notification-posted") {
                manager.activeNotifications.any {
                    it.id == SubscriptionReminders.NOTIFICATION_ID &&
                        it.notification.extras.getCharSequence("android.bigText").toString().contains("无效类型会员")
                }
            }
        } finally {
            dao.deleteSubscription(id)
            dao.record(recordId)?.let { dao.delete(it) }
            dao.removeSubscriptionPayment(id, today)
            SubscriptionReminders.enable(context, false).join()
            setPermission(context, wasAllowed)
            SubscriptionReminders.enable(context, wasEnabled).join()
        }
    }

    @Test fun realNotificationsDeduplicateExcludePaidAndCancelWhenDisabled() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        val database = DailyDatabase.get(context)
        val dao = database.records()
        val today = LocalDate.now().toString()
        val id = "reminder-system-${System.nanoTime()}"
        val paidId = "$id-paid"
        val pausedId = "$id-paused"
        val secondId = "$id-second"
        val wasEnabled = SubscriptionReminders.isEnabled(context)
        val wasAllowed = manager.areNotificationsEnabled()
        var temporarilyPausedIds = emptyList<String>()
        fun reminders() = manager.activeNotifications.filter { it.id == SubscriptionReminders.NOTIFICATION_ID }
        suspend fun awaitNoReminders(stage: String) {
            awaitState(context, stage) { reminders().isEmpty() }
        }
        suspend fun awaitReminder(stage: String, name: String) {
            awaitState(context, stage) {
                reminders().singleOrNull()?.notification?.extras?.getCharSequence("android.bigText")
                    ?.contains(name) == true
            }
        }
        try {
            SubscriptionReminders.enable(context, false).join()
            awaitNoReminders("dedup/setup-cancelled")
            // The notification is an aggregate: an unrelated due subscription legitimately keeps
            // it visible after our fixture is paid. Isolate the fixture while retaining the strong
            // assertion that the final unpaid item disappearing cancels the actual notification.
            temporarilyPausedIds = database.withTransaction {
                val active = dao.allSubscriptions().filter { it.active }
                active.forEach { dao.saveSubscription(it.copy(active = false)) }
                active.map { it.id }
            }
            val fixtureMessage = "SubscriptionReminderTest dedup/fixture-isolated existingActivePaused=${temporarilyPausedIds.size}"
            Log.i("SubscriptionReminderTest", fixtureMessage)
            instrumentation.sendStatus(2, Bundle().apply { putString("stream", "\n$fixtureMessage\n") })
            setPermission(context, true)
            assertTrue("dedup/channel must allow notifications; ${SubscriptionNotificationTestSupport.state(context)}",
                SubscriptionReminders.notificationsAllowed(context))
            dao.saveSubscription(Subscription(id, "设备通知会员", 100, "MONTH", today))
            dao.saveSubscription(Subscription(paidId, "已付排除会员", 100, "MONTH", today))
            dao.confirmSubscription(paidId, today, 100, today)
            dao.saveSubscription(Subscription(pausedId, "停用排除会员", 100, "MONTH", today, active = false))
            SubscriptionReminders.refresh(context).join()
            awaitNoReminders("dedup/disabled-still-empty")
            assertTrue("Global default/off must not post", reminders().isEmpty())
            SubscriptionReminders.enable(context, true).join()
            awaitReminder("dedup/first-notification-posted", "设备通知会员")
            val posted = reminders().single()
            assertTrue(posted.notification.extras.getCharSequence("android.text").toString().contains("设备通知会员"))
            assertFalse(posted.notification.extras.getCharSequence("android.text").toString().contains("已付排除会员"))
            assertFalse(posted.notification.extras.getCharSequence("android.text").toString().contains("停用排除会员"))
            assertNotNull(PendingIntent.getBroadcast(context, SubscriptionReminders.ALARM_REQUEST,
                Intent(context, SubscriptionReminderReceiver::class.java).setAction(SubscriptionReminders.ACTION_CHECK),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE))
            SubscriptionReminders.refresh(context).join()
            awaitReminder("dedup/repeated-refresh-visible", "设备通知会员")
            assertEquals("Same-day check must not re-post", posted.postTime, reminders().single().postTime)
            dao.confirmSubscription(id, today, 100, today)
            SubscriptionReminders.refresh(context).join()
            awaitNoReminders("dedup/paid-notification-cancelled")
            assertTrue("Paid must remove existing reminder", reminders().isEmpty())
            dao.saveSubscription(Subscription(secondId, "停用撤销会员", 100, "MONTH", today))
            SubscriptionReminders.refresh(context).join()
            awaitReminder("dedup/second-notification-posted", "停用撤销会员")
            assertTrue(reminders().single().notification.extras.getCharSequence("android.text").toString().contains("停用撤销会员"))
            dao.saveSubscription(Subscription(secondId, "停用撤销会员", 100, "MONTH", today, active = false))
            SubscriptionReminders.refresh(context).join()
            awaitNoReminders("dedup/inactive-notification-cancelled")
            assertTrue("Inactive must remove existing reminder", reminders().isEmpty())
            SubscriptionReminders.enable(context, false).join()
            awaitNoReminders("dedup/disabled-notification-cancelled")
            assertTrue(reminders().isEmpty())
            assertNull(PendingIntent.getBroadcast(context, SubscriptionReminders.ALARM_REQUEST,
                Intent(context, SubscriptionReminderReceiver::class.java).setAction(SubscriptionReminders.ACTION_CHECK),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE))
        } finally {
            SubscriptionReminders.enable(context, false).join()
            try {
                database.withTransaction {
                    listOf(id, paidId, pausedId, secondId).forEach {
                        dao.deleteSubscription(it)
                        dao.record("subscription-$it-$today")?.let { record -> dao.delete(record) }
                        dao.removeSubscriptionPayment(it, today)
                    }
                    // Restore only the active flag; preserve any other fields and never resurrect
                    // a subscription removed during the test.
                    temporarilyPausedIds.forEach { existingId ->
                        dao.subscription(existingId)?.let { dao.saveSubscription(it.copy(active = true)) }
                    }
                }
            } finally {
                try {
                    setPermission(context, wasAllowed)
                } finally {
                    SubscriptionReminders.enable(context, wasEnabled).join()
                }
            }
        }
    }

    @Test fun deniedPermissionDoesNotConsumeReminderAndManifestReceiverRestoresIt() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        val dao = DailyDatabase.get(context).records()
        val id = "permission-system-${System.nanoTime()}"
        val wasEnabled = SubscriptionReminders.isEnabled(context)
        val wasAllowed = manager.areNotificationsEnabled()
        suspend fun broadcast(action: String) {
            val completed = CompletableDeferred<Unit>()
            context.sendOrderedBroadcast(
                Intent(context, SubscriptionReminderReceiver::class.java).setAction(action), null,
                object : android.content.BroadcastReceiver() {
                    override fun onReceive(context: android.content.Context, intent: Intent) { completed.complete(Unit) }
                }, null, 0, null, null)
            // A missing finish() in the asynchronous manifest receiver fails this deadline.
            assertNotNull("permission/receiver-finish; ${SubscriptionNotificationTestSupport.state(context)}",
                withTimeoutOrNull(9000) { completed.await() })
        }
        try {
            SubscriptionReminders.enable(context, false).join()
            awaitState(context, "permission/setup-cancelled") {
                manager.activeNotifications.none { it.id == SubscriptionReminders.NOTIFICATION_ID }
            }
            setPermission(context, true)
            listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
                Intent.ACTION_MY_PACKAGE_REPLACED).forEach { action ->
                assertTrue("Manifest must restore reminders after $action",
                    context.packageManager.queryBroadcastReceivers(Intent(action).setPackage(context.packageName), 0)
                        .any { it.activityInfo.name == SubscriptionReminderReceiver::class.java.name })
            }
            dao.saveSubscription(Subscription(id, "权限恢复会员", 100, "MONTH", LocalDate.now().toString()))
            setPermission(context, false)
            SubscriptionReminders.enable(context, true).join()
            awaitState(context, "permission/denied-no-notification") {
                manager.activeNotifications.none { it.id == SubscriptionReminders.NOTIFICATION_ID }
            }
            setPermission(context, true)
            broadcast(SubscriptionReminders.ACTION_CHECK)
            awaitState(context, "permission/granted-receiver-posted") {
                manager.activeNotifications.any {
                    it.id == SubscriptionReminders.NOTIFICATION_ID &&
                        it.notification.extras.getCharSequence("android.bigText").toString().contains("权限恢复会员")
                }
            }
            assertTrue(manager.activeNotifications.single { it.id == SubscriptionReminders.NOTIFICATION_ID }
                .notification.extras.getCharSequence("android.text").toString().contains("权限恢复会员"))
            val posted = manager.activeNotifications.single { it.id == SubscriptionReminders.NOTIFICATION_ID }.postTime
            broadcast(SubscriptionReminders.ACTION_CHECK)
            assertEquals(posted, manager.activeNotifications.single { it.id == SubscriptionReminders.NOTIFICATION_ID }.postTime)
        } finally {
            dao.deleteSubscription(id)
            SubscriptionReminders.enable(context, false).join()
            setPermission(context, wasAllowed)
            SubscriptionReminders.enable(context, wasEnabled).join()
        }
    }
}

/** Test-only OS controls and diagnostics. No helper replaces the real NotificationManager. */
internal object SubscriptionNotificationTestSupport {
    private const val POST_PERMISSION = "android.permission.POST_NOTIFICATIONS"
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    fun state(context: Context): String {
        val manager = context.getSystemService(NotificationManager::class.java)
        val permission = if (Build.VERSION.SDK_INT >= 33) context.checkSelfPermission(POST_PERMISSION) else 0
        val active = manager.activeNotifications.filter { it.id == SubscriptionReminders.NOTIFICATION_ID }
        val sentCount = context.getSharedPreferences("subscription_reminders", Context.MODE_PRIVATE)
            .getStringSet("sent", emptySet()).orEmpty().size
        // Do not put users' subscription names, amounts or notification text in diagnostics.
        return "sdk=${Build.VERSION.SDK_INT}, runtimePermission=$permission, appAllowed=${manager.areNotificationsEnabled()}, " +
            "channelImportance=${manager.getNotificationChannel("subscription_due")?.importance}, " +
            "enabled=${SubscriptionReminders.isEnabled(context)}, sentCount=$sentCount, " +
            "active=${active.map { "${it.id}@${it.postTime}/flags=${it.notification.flags}" }}"
    }

    private fun report(context: Context, stage: String) {
        val message = "SubscriptionReminderTest $stage: ${state(context)}"
        Log.i("SubscriptionReminderTest", message)
        // Included in am instrument's saved output as well as logcat, even when the test times out.
        instrumentation.sendStatus(2, Bundle().apply { putString("stream", "\n$message\n") })
    }

    suspend fun awaitState(context: Context, stage: String, condition: () -> Boolean) {
        val start = SystemClock.elapsedRealtime()
        report(context, "$stage START")
        val matched = withTimeoutOrNull(5_000) {
            while (!condition()) delay(50)
            true
        } == true
        report(context, "$stage ${if (matched) "PASS" else "FAIL"} elapsedMs=${SystemClock.elapsedRealtime() - start}")
        assertTrue("$stage did not reach its expected OS state in 5000ms; ${state(context)}", matched)
    }

    suspend fun setPermission(context: Context, allowed: Boolean) {
        val automation = instrumentation.uiAutomation
        val userId = android.os.Process.myUid() / 100_000
        if (Build.VERSION.SDK_INT >= 33) {
            if (allowed) {
                // Remove the old test's package-level AppOps override; runtime permission owns the
                // UID mode on Android 13+. Changing AppOps alone never revoked this permission.
                shell("cmd appops set --user $userId ${context.packageName} POST_NOTIFICATION default")
                automation.grantRuntimePermission(context.packageName, POST_PERMISSION)
            } else {
                // Android's CTS/local-test API revokes the REAL permission without killing the
                // instrumented process. It is @TestApi (am instrument enables test API access).
                // Reflection keeps this helper out of production and needs no platform dependency.
                val type = Class.forName("android.permission.PermissionManager")
                val service = checkNotNull(context.getSystemService(type))
                val revoke = type.getMethod("revokePostNotificationPermissionWithoutKillForTest",
                    String::class.java, Int::class.javaPrimitiveType)
                automation.adoptShellPermissionIdentity(
                    "android.permission.REVOKE_POST_NOTIFICATIONS_WITHOUT_KILL",
                    "android.permission.REVOKE_RUNTIME_PERMISSIONS")
                try {
                    revoke.invoke(service, context.packageName, userId)
                } finally {
                    automation.dropShellPermissionIdentity()
                }
            }
        } else {
            shell("cmd appops set --user $userId ${context.packageName} POST_NOTIFICATION ${if (allowed) "allow" else "ignore"}")
        }
        awaitState(context, "permission/${if (allowed) "grant" else "revoke"}-observed") {
            val runtimeMatches = Build.VERSION.SDK_INT < 33 ||
                (context.checkSelfPermission(POST_PERMISSION) == PackageManager.PERMISSION_GRANTED) == allowed
            runtimeMatches && context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() == allowed
        }
    }

    private fun shell(command: String) {
        val output = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes().toString(Charsets.UTF_8) }
        check(output.isBlank()) { "Notification test setup command failed: $command\n$output" }
    }
}
