package com.lianbei.daily

/** A process list is evidence of liveness; persisted flags alone are never evidence. */
internal data class CaptureProcess(val pid: Int, val uid: Int, val name: String)

internal object CaptureLiveness {
    fun isActive(
        values: Map<String, String>,
        key: String,
        processes: List<CaptureProcess>?,
        uid: Int,
        processName: String
    ): Boolean {
        if (values[key] != "true") return false
        val pid = values["${key}_pid"]?.toIntOrNull()?.takeIf { it > 0 } ?: return false
        return processes?.any { it.pid == pid && it.uid == uid && it.name == processName } == true
    }

    /** Called from service creation using only this process's live instances, including PID reuse. */
    fun resetForProcess(values: MutableMap<String, String>, pid: Int, connected: Boolean, watchRunning: Boolean) {
        record(values, "connected", connected, pid)
        record(values, "watch", watchRunning, pid)
    }

    fun record(values: MutableMap<String, String>, key: String, active: Boolean, pid: Int) {
        values[key] = active.toString()
        if (active) values["${key}_pid"] = pid.toString() else values.remove("${key}_pid")
    }
}
