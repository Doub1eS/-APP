package com.lianbei.daily

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/**
 * Emulator system regression: uses the REAL store because :capture cannot see testRoot.
 * Does not open the inbox, drain payments, clear logcat, or force-stop the application.
 * A stopped service is not proof of success: audit both logcat and historical process exits.
 */
class PaymentWatchLifecycleSystemTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val manager get() = context.getSystemService(ActivityManager::class.java)
    private val component get() = ComponentName(context, PaymentWatchService::class.java)

    @Test fun rapidToggleAndPersistentStopDoNotCrashCaptureProcess() = runBlocking {
        check(CaptureStore.testRoot == null) { "System regression requires the real cross-process store" }
        val saved = CaptureStore.state(context)
        val startedAt = System.currentTimeMillis()
        val marker = "BEGIN ${UUID.randomUUID()}"
        Log.i(TAG, marker)
        try {
            PaymentCapture.setEnabled(context, false)
            awaitStopped()

            // Prove the OS permits this instrumentation to start the real FGS; a rejected
            // start must fail the test, not accidentally make the race appear fixed.
            PaymentCapture.setEnabled(context, true)
            awaitRunning()
            PaymentCapture.setEnabled(context, false)
            awaitStopped()

            // Same sequence as PaymentCaptureTest.oldOptInSessionCannotCaptureAfterReenable,
            // including immediate cleanup. No wait between start and stop, or before reenable.
            repeat(12) {
                PaymentCapture.setEnabled(context, true)
                val oldSession = PaymentSettings.session(context)
                PaymentCapture.setEnabled(context, false)
                PaymentCapture.setEnabled(context, true)
                assertNotEquals(oldSession, PaymentSettings.session(context))
                PaymentCapture.setEnabled(context, false)
                delay(40)
            }
            awaitStopped()

            // An old stop broadcast arriving after reenable must not cancel the newer start.
            PaymentCapture.setEnabled(context, true)
            awaitRunning()
            PaymentWatchService.stop(context)
            PaymentWatchService.start(context)
            awaitRunning()
            context.sendBroadcast(Intent(PaymentWatchService.ACTION_SETTINGS_CHANGED)
                .setPackage(context.packageName).addFlags(Intent.FLAG_RECEIVER_FOREGROUND))
            delay(500)
            assertTrue(CaptureStore.snapshot(context).watchRunning)
            assertTrue(watchExists())

            // Turning off only the watch leaves capture enabled, but reboot/update must respect
            // watch_wanted=false. A stop while absent must not start OR cancel any service.
            PaymentWatchService.stop(context)
            awaitStopped()
            assertTrue(PaymentSettings.enabled(context))
            val stopOnlyContext = StopOnlyContext(context)
            PaymentWatchService.stop(stopOnlyContext)
            PaymentBootReceiver().onReceive(stopOnlyContext, Intent(Intent.ACTION_BOOT_COMPLETED))
            PaymentBootReceiver().onReceive(stopOnlyContext, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
            assertEquals("Stop/boot must not start or cancel a service when watch is unwanted", 0,
                stopOnlyContext.serviceCalls)
            assertEquals("false", CaptureStore.state(context)["watch_wanted"])
            assertFalse(watchExists())

            // Simulate an already queued/restarted system start after the user has stopped it.
            // This deliberate test start must promote first, then self-stop without resurrection.
            assertNotNull(context.startForegroundService(Intent(context, PaymentWatchService::class.java)))
            delay(500)
            awaitStopped()
            assertEquals("false", CaptureStore.state(context)["watch_wanted"])
        } finally {
            try {
                PaymentCapture.setEnabled(context, false)
                try {
                    awaitStopped()
                } finally {
                    // Includes delayed RemoteServiceException delivery; do not finish immediately
                    // after watch=false. Main runner may increase this on a slow/configured device.
                    val observation = InstrumentationRegistry.getArguments().getString("fgsObservationMs")
                        ?.toLongOrNull()?.coerceAtLeast(35_000L) ?: 35_000L
                    delay(observation)
                    assertNoCaptureCrash(marker, startedAt)
                }
            } finally {
                // Restore preferences only, never overwrite diagnostics or payment queue state.
                CaptureStore.update(context) { values ->
                    for (key in listOf("enabled", "session", "enabled_at", "watch_wanted")) {
                        saved[key]?.let { values[key] = it } ?: values.remove(key)
                    }
                }
                if (saved["enabled"] == "true" && saved["watch_wanted"] != "false") {
                    PaymentWatchService.start(context)
                }
            }
        }
    }

    @Suppress("DEPRECATION") // Own-app services are still visible on API 26+.
    private fun watchExists() = manager.getRunningServices(Int.MAX_VALUE).any { it.service == component }

    private suspend fun awaitRunning() = withTimeout(15_000L) {
        while (!CaptureStore.snapshot(context).watchRunning || !watchExists()) delay(50)
        assertTrue("Watch must run in :capture", manager.runningAppProcesses.orEmpty().any {
            it.pid.toString() == CaptureStore.state(context)["watch_pid"] &&
                it.processName == "${context.packageName}:capture"
        })
    }

    private suspend fun awaitStopped() = withTimeout(15_000L) {
        while (watchExists() || CaptureStore.snapshot(context).watchRunning) delay(50)
        assertFalse(context.getSystemService(NotificationManager::class.java).activeNotifications
            .any { it.id == PaymentWatchService.ID })
    }

    private fun assertNoCaptureCrash(marker: String, startedAt: Long) {
        val log = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "logcat -b main -b system -b crash -d -v threadtime -s $TAG:I AndroidRuntime:E ActivityManager:W"
        )).bufferedReader().use { it.readText() }
        val window = log.substringAfter(marker, "")
        val exits = if (Build.VERSION.SDK_INT >= 30) {
            manager.getHistoricalProcessExitReasons(context.packageName, 0, 64).filter {
                it.timestamp >= startedAt && it.processName == "${context.packageName}:capture" &&
                    it.reason in listOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE)
            }.map { "pid=${it.pid} reason=${it.reason} time=${it.timestamp} ${it.description}" }
        } else emptyList()
        val report = File(context.getExternalFilesDir(null) ?: context.filesDir,
            "payment-watch-lifecycle-$startedAt.log")
        report.writeText(log + "\nCapture crash exits:\n" + exits.joinToString("\n"))
        Log.i(TAG, "Crash audit saved: ${report.absolutePath}")
        assertTrue("Log marker missing; cannot verify absence of a fatal. See $report", log.contains(marker))
        val capturePids = Regex("Process: ${Regex.escape(context.packageName)}:capture, PID: (\\d+)")
            .findAll(window).map { it.groupValues[1] }.toSet()
        val fatal = window.lineSequence().filter { line ->
            val pid = Regex("^\\S+\\s+\\S+\\s+(\\d+)\\s+").find(line)?.groupValues?.get(1)
            (pid in capturePids && line.contains("AndroidRuntime") &&
                (line.contains("FATAL EXCEPTION") || line.contains("ForegroundServiceDidNotStartInTimeException"))) ||
                (line.contains("Bringing down service while still waiting for start foreground") &&
                    line.contains(context.packageName))
        }.toList()
        assertTrue("Capture process crashed: $exits; $fatal. See $report", exits.isEmpty() && fatal.isEmpty())
    }

    private class StopOnlyContext(base: Context) : ContextWrapper(base) {
        var serviceCalls = 0
        override fun startService(service: Intent): ComponentName? { serviceCalls++; return null }
        override fun startForegroundService(service: Intent): ComponentName? { serviceCalls++; return null }
        override fun stopService(service: Intent): Boolean { serviceCalls++; return false }
    }

    private companion object { const val TAG = "PaymentWatchRace" }
}
