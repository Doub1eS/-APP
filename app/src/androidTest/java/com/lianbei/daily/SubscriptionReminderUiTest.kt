package com.lianbei.daily

import android.app.Activity
import android.app.Instrumentation
import android.app.NotificationManager
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.time.LocalDate
import com.lianbei.daily.SubscriptionNotificationTestSupport.awaitState
import com.lianbei.daily.SubscriptionNotificationTestSupport.setPermission

/** Requires the parent integration: receiver in Manifest and MainActivity's subscriptions extra. */
class SubscriptionReminderUiTest {
    // ActivityScenario matches lifecycle callbacks against the original intent's action/categories.
    // Launch with the same explicit identity as the notification: onNewIntent calls setIntent, so
    // the default MAIN/LAUNCHER rule would stop observing RESUMED/DESTROYED after a real tap.
    @get:Rule val compose = AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>(
        activityRule = ActivityScenarioRule<MainActivity>(
            Intent(InstrumentationRegistry.getInstrumentation().targetContext, MainActivity::class.java)),
        activityProvider = { rule ->
            lateinit var activity: MainActivity
            rule.scenario.onActivity { activity = it }
            activity
        })
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var wasEnabled = false
    private var wasAllowed = false
    @Before fun setup() = runBlocking {
        wasEnabled = SubscriptionReminders.isEnabled(context)
        wasAllowed = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        SubscriptionReminders.enable(context, false).join()
        awaitState(context, "ui/setup-cancelled") {
            context.getSystemService(NotificationManager::class.java).activeNotifications
                .none { it.id == SubscriptionReminders.NOTIFICATION_ID }
        }
        setPermission(context, true)
    }
    @After fun restore() = runBlocking {
        SubscriptionReminders.enable(context, false).join()
        setPermission(context, wasAllowed)
        SubscriptionReminders.enable(context, wasEnabled).join()
    }

    @Test fun notificationTapOpensSubscriptionScreenForRunningActivity(): Unit = runBlocking {
        val originalActivity = compose.activity
        compose.onNodeWithTag("subscriptions-list").assertDoesNotExist()
        val dao = DailyDatabase.get(context).records()
        val id = "tap-${System.nanoTime()}"
        val manager = context.getSystemService(NotificationManager::class.java)
        try {
            dao.saveSubscription(Subscription(id, "通知跳转会员", 100, "MONTH", LocalDate.now().toString()))
            SubscriptionReminders.enable(context, true).join()
            awaitState(context, "ui/tap-notification-posted") {
                manager.activeNotifications.any {
                    it.id == SubscriptionReminders.NOTIFICATION_ID &&
                        it.notification.extras.getCharSequence("android.bigText").toString().contains("通知跳转会员")
                }
            }
            val actualNotification = manager.activeNotifications.single { it.id == SubscriptionReminders.NOTIFICATION_ID }
            actualNotification.notification.contentIntent.send()
            compose.onNodeWithTag("subscriptions-list").assertIsDisplayed()
            compose.onNodeWithTag("subscriptions-list").performScrollToNode(hasTestTag("subscription-card-$id"))
            compose.onNodeWithText("通知跳转会员").assertIsDisplayed()
            assertSame("Notification must reuse the running activity", originalActivity, compose.activity)
            compose.runOnIdle {
                assertTrue("The real notification must deliver the subscriptions extra",
                    originalActivity.intent.getBooleanExtra("subscriptions", false))
                assertEquals(Lifecycle.State.RESUMED, originalActivity.lifecycle.currentState)
            }
            assertEquals("ActivityScenario must still track lifecycle after onNewIntent",
                Lifecycle.State.RESUMED, compose.activityRule.scenario.state)
        } finally {
            dao.deleteSubscription(id)
        }
    }

    @Test fun switchPersistsShowsDeniedPermissionAndOpensSystemSettings() {
        compose.onNodeWithText("订阅").performClick()
        compose.onNodeWithTag("subscriptions-list").performScrollToNode(hasTestTag("subscription-reminder-switch"))
        compose.onNodeWithTag("subscription-reminder-switch").assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { assertTrue(SubscriptionReminders.isEnabled(context)) }
        // Revoke after enabling, then resume as if returning from system settings. This tests the
        // real permission hint without leaving a runtime permission dialog over the Compose window.
        runBlocking { setPermission(context, false) }
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.onNodeWithTag("subscriptions-list").performScrollToNode(hasTestTag("subscription-reminder-permission"))
        compose.onNodeWithTag("subscription-reminder-permission").assertIsDisplayed()
        var opened: Intent? = null
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Settings.ACTION_APP_NOTIFICATION_SETTINGS) return null
                opened = intent
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            compose.onNodeWithTag("subscriptions-list").performScrollToNode(hasTestTag("subscription-notification-settings"))
            compose.onNodeWithTag("subscription-notification-settings").performClick()
            compose.runOnIdle { assertEquals(context.packageName, opened?.getStringExtra(Settings.EXTRA_APP_PACKAGE)) }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
        compose.onNodeWithTag("subscriptions-list").performScrollToNode(hasTestTag("subscription-reminder-switch"))
        compose.onNodeWithTag("subscription-reminder-switch").performClick().assertIsOff()
        compose.runOnIdle { assertFalse(SubscriptionReminders.isEnabled(context)) }
    }
}
