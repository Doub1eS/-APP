package com.lianbei.daily

import org.junit.Assert.*
import org.junit.Test

class CaptureLivenessTest {
    private val process = CaptureProcess(42, 10001, "com.lianbei.daily:capture")
    private fun active(values: Map<String, String>, key: String, processes: List<CaptureProcess>? = listOf(process)) =
        CaptureLiveness.isActive(values, key, processes, 10001, "com.lianbei.daily:capture")

    @Test fun persistedFlagsCannotKeepADeadProcessAlive() {
        val values = mapOf("connected" to "true", "connected_pid" to "42", "watch" to "true", "watch_pid" to "42")
        for (processes in listOf(null, emptyList<CaptureProcess>())) {
            assertFalse(active(values, "connected", processes))
            assertFalse(active(values, "watch", processes))
        }
    }

    @Test fun processMustMatchPidUidAndExactCaptureName() {
        val values = mapOf("connected" to "true", "connected_pid" to "42")
        assertTrue(active(values, "connected"))
        for (other in listOf(
            process.copy(pid = 43), process.copy(uid = 10002),
            process.copy(name = "com.lianbei.daily"),
            process.copy(name = "other:capture"),
            process.copy(name = "com.lianbei.daily:capture:worker")
        )) assertFalse(active(values, "connected", listOf(other)))
    }

    @Test fun missingInvalidOrLegacyPidIsConservativelyInactive() {
        for (pid in listOf(null, "", "0", "-1", "bad", "999999999999999")) {
            val values = mutableMapOf("watch" to "true")
            if (pid != null) values["watch_pid"] = pid
            assertFalse(active(values, "watch"))
        }
        assertFalse(active(mapOf("watch" to "false", "watch_pid" to "42"), "watch"))
    }

    @Test fun listenerAndWatchHaveIndependentOwnership() {
        val values = mapOf("connected" to "true", "connected_pid" to "42", "watch" to "true", "watch_pid" to "99")
        assertTrue(active(values, "connected"))
        assertFalse(active(values, "watch"))
        assertTrue(active(mapOf("connected" to "true", "connected_pid" to "42", "watch" to "false"), "connected"))
    }

    @Test fun serviceCreationClearsStaleFlagsEvenWhenPidIsReused() {
        val values = mutableMapOf("connected" to "true", "connected_pid" to "42", "watch" to "true", "watch_pid" to "42", "received" to "123")
        CaptureLiveness.resetForProcess(values, 42, connected = false, watchRunning = false)
        assertFalse(active(values, "connected"))
        assertFalse(active(values, "watch"))
        assertFalse(values.containsKey("connected_pid"))
        assertFalse(values.containsKey("watch_pid"))
        assertEquals("123", values["received"])
    }

    @Test fun creatingOneServicePreservesTheOtherLiveService() {
        val values = mutableMapOf<String, String>()
        CaptureLiveness.resetForProcess(values, 42, connected = true, watchRunning = false)
        assertTrue(active(values, "connected"))
        assertFalse(active(values, "watch"))
        CaptureLiveness.resetForProcess(values, 42, connected = false, watchRunning = true)
        assertFalse(active(values, "connected"))
        assertTrue(active(values, "watch"))
    }

    @Test fun disconnectAndStopRemovePidWithoutClearingTheOtherService() {
        val values = mutableMapOf<String, String>()
        CaptureLiveness.resetForProcess(values, 42, connected = true, watchRunning = true)
        CaptureLiveness.record(values, "connected", false, 42)
        assertFalse(active(values, "connected"))
        assertFalse(values.containsKey("connected_pid"))
        assertTrue(active(values, "watch"))
        CaptureLiveness.record(values, "watch", false, 42)
        assertFalse(active(values, "watch"))
        assertFalse(values.containsKey("watch_pid"))
    }
}
