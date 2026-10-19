package com.lianbei.daily

import android.app.NotificationManager
import android.content.ComponentName
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*

/**
 * End to end check against the real system: the listener lives in the `:capture` process, so the
 * connection state is read back from the shared store instead of process memory.
 */
class PaymentListenerSystemTest {
    @Test fun systemDeliversSelfCheckToRealListener() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        val component = ComponentName(context, PaymentListener::class.java)
        val alreadyGranted = manager.isNotificationListenerAccessGranted(component)
        val wasEnabled = PaymentSettings.enabled(context)
        fun shell(command: String) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        try {
            shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
            PaymentSettings.enable(context, true)
            shell("cmd notification allow_listener ${component.flattenToString()}")
            withTimeout(20000) { while (!CaptureStore.snapshot(context).connected) delay(100) }
            assertTrue(PaymentNotifications.selfTest(context))
            withTimeout(15000) {
                while (CaptureStore.snapshot(context).let { it.selfReceivedAt < it.selfRequestedAt }) delay(100)
            }
            assertTrue(CaptureStore.snapshot(context).selfReceivedAt > 0)
        } finally {
            PaymentSettings.enable(context, wasEnabled)
            if (!alreadyGranted) shell("cmd notification disallow_listener ${component.flattenToString()}")
        }
    }
}
