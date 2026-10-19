package com.lianbei.daily

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.Properties

/**
 * Cross-process state for payment capture.
 *
 * The notification listener and its watch service run in the `:capture` process, while the UI,
 * Room and the navigation live in the default process. SharedPreferences caches its values per
 * process and its listeners never fire across processes, so both sides share a small file guarded
 * by an OS file lock instead. Only switches, connection stamps and fixed diagnostic reasons live
 * here; notification text is never written.
 */
data class PaymentCaptureStatus(
    val enabled: Boolean = false,
    val connected: Boolean = false,
    val connectedAt: Long = 0,
    val receivedAt: Long = 0,
    val source: String = "",
    val reason: String = "",
    val capturedAt: Long = 0,
    val selfRequestedAt: Long = 0,
    val selfReceivedAt: Long = 0,
    val watchRunning: Boolean = false,
    val watchSince: Long = 0,
    val queued: Int = 0,
    val lastDrainAt: Long = 0,
    val refused: String = ""
)

object CaptureStore {
    private const val STATE = "capture-state.properties"
    private const val QUEUE = "capture-queue.jsonl"
    private const val BATCH = "capture-queue.draining"
    private const val SEEN = "capture-seen.tsv"
    private const val LOCK = "capture.lock"
    private const val MAX_SEEN = 800

    // FileLock is per-process: two threads in the same process must not race for it.
    private val guard = Any()

    /**
     * Tests point this at a scratch directory so they never read or drain the live queue that the
     * `:capture` process is writing for the real user on the same device.
     */
    @Volatile
    internal var testRoot: File? = null

    private fun file(ctx: Context, name: String) = File(testRoot ?: ctx.applicationContext.filesDir, name)

    private fun <T> locked(ctx: Context, block: () -> T): T = synchronized(guard) {
        RandomAccessFile(file(ctx, LOCK), "rw").use { raf ->
            val channel = raf.channel
            val held = channel.lock()
            try {
                block()
            } finally {
                held.release()
            }
        }
    }

    private fun load(ctx: Context): MutableMap<String, String> {
        val properties = Properties()
        val target = file(ctx, STATE)
        if (target.exists()) runCatching { target.inputStream().use { properties.load(it) } }
        return properties.stringPropertyNames().associateWith { properties.getProperty(it) }.toMutableMap()
    }

    private fun save(ctx: Context, values: Map<String, String>) {
        val properties = Properties()
        values.forEach { (key, value) -> properties.setProperty(key, value) }
        val target = file(ctx, STATE)
        val temp = File(target.parentFile, "$STATE.tmp")
        temp.outputStream().use { properties.store(it, null) }
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }

    fun state(ctx: Context): Map<String, String> = locked(ctx) { load(ctx) }

    fun update(ctx: Context, block: (MutableMap<String, String>) -> Unit) = updateIf(ctx) {
        block(it)
        true
    }

    /** Same as [update] but skips the file write when [block] reports no change. */
    fun updateIf(ctx: Context, block: (MutableMap<String, String>) -> Boolean) = locked(ctx) {
        val values = load(ctx)
        if (block(values)) save(ctx, values)
    }

    fun snapshot(ctx: Context): PaymentCaptureStatus {
        val values = state(ctx)
        fun long(key: String) = values[key]?.toLongOrNull() ?: 0L
        return PaymentCaptureStatus(
            enabled = values["enabled"] == "true",
            connected = values["connected"] == "true",
            connectedAt = long("connected_at"),
            receivedAt = long("received"),
            source = values["source"].orEmpty(),
            reason = values["reason"].orEmpty(),
            capturedAt = long("captured"),
            selfRequestedAt = long("self_requested"),
            selfReceivedAt = long("self_received"),
            watchRunning = values["watch"] == "true",
            watchSince = long("watch_since"),
            queued = long("queued").toInt(),
            lastDrainAt = long("last_drain"),
            refused = values["refused"].orEmpty()
        )
    }

    fun clearDiagnostics(ctx: Context) = update(ctx) { values ->
        listOf("received", "source", "reason", "captured", "self_requested", "self_received", "refused").forEach { values.remove(it) }
    }

    fun record(ctx: Context, source: String, reason: String) {
        val now = System.currentTimeMillis()
        updateIf(ctx) { values ->
            // A busy chat app must not trigger a file write per notification.
            val lastAt = values["received"]?.toLongOrNull() ?: 0L
            if (values["reason"] == reason && values["source"] == source && now - lastAt < 30_000L) {
                false
            } else {
                values["received"] = now.toString()
                values["source"] = source
                values["reason"] = reason
                if (reason == CAPTURED) values["captured"] = now.toString()
                true
            }
        }
    }

    /** Marks the identities as handled and reports the payment they already belong to. */
    fun claim(ctx: Context, identities: List<String>, paymentId: String): String? = locked(ctx) {
        val target = file(ctx, SEEN)
        val rows = runCatching { target.takeIf { it.exists() }?.readLines().orEmpty() }.getOrDefault(emptyList())
            .filter { it.isNotBlank() }
        val known = HashMap<String, String>()
        rows.forEach { row ->
            val parts = row.split('\t')
            if (parts.size >= 2) known[parts[0]] = parts[1]
        }
        val existing = identities.firstNotNullOfOrNull { known[it] }
        // A duplicate may arrive with identities the first notification did not carry (for example a
        // transaction id that shows up in an update). They must still be remembered, otherwise a
        // later re-post under a new notification key would create a second payment.
        val fresh = identities.filter { it !in known }
        if (fresh.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val owner = existing ?: paymentId
            val merged = rows + fresh.map { "$it\t$owner\t$now" }
            val trimmed = if (merged.size > MAX_SEEN) merged.takeLast(MAX_SEEN) else merged
            runCatching { target.writeText(trimmed.joinToString("\n") + "\n") }
        }
        existing
    }

    fun enqueuePayment(ctx: Context, pending: PendingPayment, identities: List<String>, legacy: List<String>) = locked(ctx) {
        val record = JSONObject()
            .put("kind", "payment")
            .put("id", pending.id)
            .put("cents", pending.cents)
            .put("merchant", pending.merchant)
            .put("source", pending.source)
            .put("date", pending.date)
            .put("time", pending.time)
            .put("identities", JSONArray(identities))
            .put("legacy", JSONArray(legacy))
        append(ctx, record)
    }

    fun enqueueMerchant(ctx: Context, id: String, merchant: String) = locked(ctx) {
        append(ctx, JSONObject().put("kind", "merchant").put("id", id).put("merchant", merchant))
    }

    private fun append(ctx: Context, record: JSONObject) {
        val target = file(ctx, QUEUE)
        target.appendText(record.toString() + "\n")
        val values = load(ctx)
        values["queued"] = ((values["queued"]?.toLongOrNull() ?: 0L) + 1).toString()
        save(ctx, values)
    }

    /**
     * Takes the pending records out of the shared queue as one batch. The batch file survives a
     * crash so nothing is dropped between reading and writing to Room.
     */
    fun takeBatch(ctx: Context): File? = locked(ctx) {
        val batch = file(ctx, BATCH)
        if (batch.exists() && batch.length() > 0L) return@locked batch
        val queue = file(ctx, QUEUE)
        if (!queue.exists() || queue.length() == 0L) return@locked null
        batch.delete()
        if (queue.renameTo(batch)) batch else null
    }

    fun finishBatch(ctx: Context, batch: File, keep: List<String>) = locked(ctx) {
        if (keep.isEmpty()) batch.delete()
        else batch.writeText(keep.joinToString("\n") + "\n")
        val values = load(ctx)
        values["last_drain"] = System.currentTimeMillis().toString()
        values["queued"] = keep.size.toString()
        save(ctx, values)
    }

    fun refuse(ctx: Context, reason: String) = update(ctx) { it["refused"] = reason }

    /** One-time copy of the 0.6 SharedPreferences switches so upgrading keeps the user's choice. */
    fun migrateLegacy(ctx: Context) {
        if (file(ctx, STATE).exists()) return
        val legacy = ctx.getSharedPreferences("payments", 0)
        val diagnostics = ctx.getSharedPreferences("payment_diagnostics", 0)
        if (!legacy.contains("enabled") && diagnostics.all.isEmpty()) return
        update(ctx) { values ->
            values["enabled"] = legacy.getBoolean("enabled", false).toString()
            legacy.getString("session", null)?.let { values["session"] = it }
            val enabledAt = legacy.getLong("enabled_at", 0L)
            if (enabledAt > 0) values["enabled_at"] = enabledAt.toString()
            listOf("received", "captured", "self_requested", "self_received").forEach { key ->
                val value = diagnostics.getLong(key, 0L)
                if (value > 0) values[key] = value.toString()
            }
            diagnostics.getString("source", null)?.let { values["source"] = it }
            diagnostics.getString("reason", null)?.let { values["reason"] = it }
        }
    }

    const val CAPTURED = "已加入待确认"
}

/** User facing switch and session, stored in [CaptureStore] so both processes read one value. */
object PaymentSettings {
    fun enabled(c: Context) = CaptureStore.state(c)["enabled"] == "true"
    fun session(c: Context) = CaptureStore.state(c)["session"].orEmpty()
    fun enabledAt(c: Context) = CaptureStore.state(c)["enabled_at"]?.toLongOrNull() ?: 0L

    fun enable(c: Context, value: Boolean) {
        val now = System.currentTimeMillis()
        CaptureStore.update(c) { values ->
            val was = values["enabled"] == "true"
            values["enabled"] = value.toString()
            values["session"] = java.util.UUID.randomUUID().toString()
            if (value && !was) values["enabled_at"] = now.toString()
            if (value) values["watch_wanted"] = "true"
            if (!value) values["connected"] = "false"
        }
    }
}

object PaymentDiagnostics {
    fun snapshot(c: Context): PaymentCaptureStatus = CaptureStore.snapshot(c)
    fun record(c: Context, source: String, reason: String) = CaptureStore.record(c, source, reason)
    fun clear(c: Context) = CaptureStore.clearDiagnostics(c)
}
