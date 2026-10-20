package com.lianbei.daily

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CaptureStatusLivenessTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private val storedStatus = PaymentCaptureStatus(
        enabled = true, connected = true, connectedAt = 101, receivedAt = 102,
        source = "微信", reason = "已加入待确认", capturedAt = 103,
        selfRequestedAt = 104, selfReceivedAt = 105, watchRunning = true,
        watchSince = 106, queued = 7, lastDrainAt = 107, refused = "诊断保留"
    )

    @Before fun setup() {
        root = File(context.cacheDir, "liveness-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        CaptureStore.testRoot = root
        CaptureStore.update(context) {
            it["connected"] = "true"
            it["connected_pid"] = Process.myPid().toString()
            it["watch"] = "true"
            it["watch_pid"] = Process.myPid().toString()
        }
    }

    @After fun cleanup() {
        CaptureStore.testRoot = null
        root.deleteRecursively()
    }

    @Test fun liveUiProcessCannotValidateCaptureFlagsAndOtherStatusFieldsArePreserved() {
        val checked = QueryContext()
        assertEquals(storedStatus.copy(connected = false, watchRunning = false), settledStatus(checked, storedStatus))
        assertEquals(0, checked.notificationQueries)
        assertEquals(0, checked.serviceStarts)
    }

    @Test fun unavailableProcessListCannotFallBackToPersistedTrue() {
        for (throws in listOf(false, true)) {
            val checked = QueryContext(unavailable = true, throws = throws)
            assertEquals(storedStatus.copy(connected = false, watchRunning = false), settledStatus(checked, storedStatus))
            assertEquals(0, checked.notificationQueries)
            assertEquals(0, checked.serviceStarts)
        }
    }

    @Test fun listenerWithoutWatchStillRequiresALiveCaptureProcess() {
        CaptureStore.update(context) { it["watch"] = "false" }
        assertFalse(settledStatus(QueryContext(), storedStatus.copy(watchRunning = false)).connected)
    }

    private inner class QueryContext(
        private val unavailable: Boolean = false,
        private val throws: Boolean = false
    ) : ContextWrapper(context) {
        var notificationQueries = 0
        var serviceStarts = 0

        override fun getSystemService(name: String): Any? {
            if (name == Context.NOTIFICATION_SERVICE) {
                notificationQueries++
                error("Status must not depend on notification visibility")
            }
            if (name == Context.ACTIVITY_SERVICE && unavailable) {
                if (throws) throw SecurityException("Process list unavailable")
                return null
            }
            return super.getSystemService(name)
        }

        override fun startService(service: Intent): ComponentName? {
            serviceStarts++
            error("Status must not start a service")
        }

        override fun startForegroundService(service: Intent): ComponentName? {
            serviceStarts++
            error("Status must not start a foreground service")
        }

        override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
            serviceStarts++
            error("Status must not bind a service")
        }
    }
}
