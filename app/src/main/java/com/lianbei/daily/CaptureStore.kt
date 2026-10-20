package com.lianbei.daily

import android.content.Context
import android.util.AtomicFile
import java.io.FileOutputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
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
    val refused: String = "",
    val readError: String = ""
)

/** Only counts, fixed messages and an opaque confirmation token may leave queue storage. */
data class CaptureQueueStatus(
    val failedCount: Int = 0,
    val malformedCount: Int = 0,
    val storageCount: Int = 0,
    val confirmationToken: String = "",
    val readError: String = ""
)

object CaptureStore {
    private const val STATE = "capture-state.properties"
    private const val QUEUE = "capture-queue.jsonl"
    private const val BATCH = "capture-queue.draining"
    private const val SEEN = "capture-seen.tsv"
    private const val LOCK = "capture.lock"
    private const val DRAIN_LOCK = "capture-drain.lock"
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

    // Held for the whole Room import or discard, including suspensions. Capture append uses its
    // own short lock so incoming payments can still be journaled while Room is busy.
    // CaptureDrain's Mutex prevents overlapping JVM locks; this lock also covers other processes.
    internal suspend fun <T> withDrainLock(ctx: Context, block: suspend () -> T): T =
        RandomAccessFile(file(ctx, DRAIN_LOCK), "rw").use { raf ->
            val held = raf.channel.lock()
            try { block() } finally { held.release() }
        }

    private fun load(ctx: Context): MutableMap<String, String> {
        val properties = Properties()
        val target = file(ctx, STATE)
        if (target.exists() || File(target.path + ".bak").exists()) AtomicFile(target).openRead().use { properties.load(it) }
        return properties.stringPropertyNames().associateWith { properties.getProperty(it) }.toMutableMap()
    }

    private fun save(ctx: Context, values: Map<String, String>) {
        val properties = Properties()
        values.forEach { (key, value) -> properties.setProperty(key, value) }
        val target = file(ctx, STATE)
        atomicWrite(target) { properties.store(it, null) }
    }

    private fun atomicWrite(target: File, write: (FileOutputStream) -> Unit) {
        val atomic = AtomicFile(target)
        val stream = atomic.startWrite()
        try { write(stream); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }

    fun state(ctx: Context): Map<String, String> = locked(ctx) { load(ctx) }

    fun update(ctx: Context, block: (MutableMap<String, String>) -> Unit) = updateIf(ctx) {
        block(it)
        true
    }

    /** Same as [update] but skips the file write when [block] reports no change. */
    fun updateIf(ctx: Context, block: (MutableMap<String, String>) -> Boolean) = locked(ctx) {
        val values = load(ctx)
        val changed = block(values)
        if (changed) save(ctx, values)
        changed
    }

    fun snapshot(ctx: Context): PaymentCaptureStatus {
        val values = try { state(ctx) }
        catch (_: Exception) { return PaymentCaptureStatus(readError = STATE_READ_ERROR) }
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

    enum class Commit { NEW, DUPLICATE, DISABLED }

    /**
     * Serialize opt-in validation, journal append and dedup publication across both processes.
     * The durable queue always precedes the index. A crash can replay an event, never claim an
     * event that was not queued. Room is the durable dedup authority after queue/cache eviction.
     */
    fun capture(ctx: Context, pending: PendingPayment, identities: List<String>, legacy: List<String>, session: String): Commit = locked(ctx) {
        val values = load(ctx)
        if (values["enabled"] != "true" || values["session"].orEmpty() != session) return@locked Commit.DISABLED
        val target = file(ctx, SEEN)
        val rows = if (target.exists() || File(target.path + ".bak").exists()) {
            AtomicFile(target).openRead().bufferedReader().use { it.readLines() }.filter { it.isNotBlank() }
        } else emptyList()
        val known = HashMap<String, String>()
        rows.forEach { row -> val parts = row.split('\t'); if (parts.size >= 2) known[parts[0]] = parts[1] }
        val orderedIdentities = (identities + legacy).distinct()
        val allIdentities = orderedIdentities.sorted()
        // Preserve primary-identity precedence; sorting is only for the canonical receipt.
        val existing = orderedIdentities.firstNotNullOfOrNull { known[it] }
        val owner = existing ?: pending.id
        // Every distinct full payload/identity set must reach Room, including merchant and
        // transaction enrichment. Only exact repeats can skip the journal. This cache is bounded;
        // after eviction Room remains the durable identity authority.
        val record = JSONObject().put("kind", "payment").put("id", owner)
            .put("cents", pending.cents).put("merchant", pending.merchant).put("source", pending.source)
            .put("date", pending.date).put("time", pending.time)
            .put("identities", JSONArray(allIdentities))
            .put("legacy", JSONArray((legacy + owner).distinct().sorted()))
        val serialized = record.toString()
        val payloadKey = "payload:" + digest(serialized)
        if (known[payloadKey] == owner) return@locked Commit.DUPLICATE
        RandomAccessFile(file(ctx, QUEUE), "rw").use { queue ->
            val length = queue.length()
            if (length > 0) {
                queue.seek(length - 1)
                // Preserve a torn final record as an independent failed row; don't concatenate
                // a new valid event onto it. Failed rows remain available for diagnosis/retry.
                if (queue.readByte().toInt() != 10) queue.writeByte(10)
            }
            queue.seek(queue.length())
            queue.write((serialized + "\n").toByteArray(Charsets.UTF_8))
            queue.fd.sync()
        }
        // Publish the payload receipt only after fsync. A failed append must remain retryable.
        val fresh = allIdentities.filter { it !in known } + payloadKey
        if (fresh.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val merged = (rows + fresh.map { "$it\t$owner\t$now" }).takeLast(MAX_SEEN)
            atomicWrite(target) { it.write((merged.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)) }
        }
        values["queued"] = ((values["queued"]?.toLongOrNull() ?: 0L) + 1).toString()
        save(ctx, values)
        if (existing == null) Commit.NEW else Commit.DUPLICATE
    }

    /**
     * Takes the pending records out of the shared queue as one batch. The batch file survives a
     * crash so nothing is dropped between reading and writing to Room.
     */
    fun takeBatch(ctx: Context): File? = locked(ctx) {
        val batch = file(ctx, BATCH)
        val queue = file(ctx, QUEUE)
        if (File(batch.path + ".bak").exists()) AtomicFile(batch).openRead().close()
        if (batch.exists() && batch.length() > 0L) {
            if (queue.exists() && queue.length() > 0L) {
                // Retained failures must not starve later valid payments. Replace the batch
                // before removing the new queue; a crash between these steps only replays rows.
                val retained = AtomicFile(batch).openRead().use { it.readBytes() }
                atomicWrite(batch) { out ->
                    out.write(retained)
                    if (retained.isNotEmpty() && retained.last().toInt() != 10) out.write(10)
                    queue.inputStream().use { it.copyTo(out) }
                }
                check(queue.delete()) { "Cannot finish merging capture queue" }
            }
            return@locked batch
        }
        if (!queue.exists() || queue.length() == 0L) return@locked null
        check(!batch.exists() || batch.delete()) { "Cannot remove empty batch" }
        check(queue.renameTo(batch)) { "Cannot prepare capture batch" }
        batch
    }

    fun finishBatch(ctx: Context, batch: File, keep: List<String>) = locked(ctx) {
        finishBatchLocked(ctx, batch, keep)
    }

    private fun finishBatchLocked(ctx: Context, batch: File, keep: List<String>) {
        val values = load(ctx)
        val queued = file(ctx, QUEUE).takeIf { it.exists() }?.useLines { lines -> lines.count { it.isNotBlank() } } ?: 0
        if (keep.isEmpty()) check(!batch.exists() || batch.delete()) { "Cannot finish capture batch" }
        else atomicWrite(batch) { it.write((keep.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)) }
        values["last_drain"] = System.currentTimeMillis().toString()
        values["queued"] = (keep.size + queued).toString()
        save(ctx, values)
    }

    private fun batchLines(ctx: Context): List<String> {
        val batch = file(ctx, BATCH)
        if (!batch.exists() && !File(batch.path + ".bak").exists()) return emptyList()
        return AtomicFile(batch).openRead().bufferedReader().use { it.readLines() }.filter { it.isNotBlank() }
    }

    private fun queueStatus(lines: List<String>): CaptureQueueStatus {
        val failed = lines.mapNotNull { line -> CaptureQueueCodec.failureReason(line)?.let { line to it } }
        return CaptureQueueStatus(
            failedCount = failed.size,
            malformedCount = failed.count { it.second == CaptureQueueCodec.MALFORMED },
            storageCount = failed.count { it.second == CaptureQueueCodec.STORAGE },
            confirmationToken = if (failed.isEmpty()) "" else digest(failed.joinToString("\n") { it.first })
        )
    }

    fun queueStatus(ctx: Context): CaptureQueueStatus = try {
        locked(ctx) { queueStatus(batchLines(ctx)) }
    } catch (_: Exception) { CaptureQueueStatus(readError = QUEUE_READ_ERROR) }

    /** Call only while holding the drain mutex + OS lock. Normal queued arrivals are never read
     * for deletion; valid unprocessed rows in a crash-recovered batch are also retained. */
    internal fun discardFailed(ctx: Context, expected: CaptureQueueStatus): Int = locked(ctx) {
        check(expected.readError.isEmpty() && expected.failedCount > 0 && expected.confirmationToken.isNotEmpty()) {
            "失败队列无法确认，请刷新后重试"
        }
        val lines = batchLines(ctx)
        val current = queueStatus(lines)
        check(current.confirmationToken == expected.confirmationToken && current.failedCount == expected.failedCount) {
            "失败队列已变化，请重新确认"
        }
        val keep = lines.filter { CaptureQueueCodec.failureReason(it) == null }
        // Invalidate receipts before discarding data so a future redelivery can be captured.
        // Clearing this bounded optimization is safe: Room still owns durable identity dedup.
        atomicWrite(file(ctx, SEEN)) { }
        finishBatchLocked(ctx, file(ctx, BATCH), keep)
        current.failedCount
    }

    fun refuse(ctx: Context, reason: String) = update(ctx) { it["refused"] = reason }

    /** One-time copy of the 0.6 SharedPreferences switches so upgrading keeps the user's choice. */
    fun migrateLegacy(ctx: Context) {
        val legacy = ctx.getSharedPreferences("payments", 0)
        val diagnostics = ctx.getSharedPreferences("payment_diagnostics", 0)
        if (!legacy.contains("enabled") && diagnostics.all.isEmpty()) return
        locked(ctx) {
            val state = file(ctx, STATE)
            // A recoverable AtomicFile backup is already migrated state, not a fresh install.
            if (state.exists() || File(state.path + ".bak").exists()) return@locked
            val values = load(ctx)
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
            save(ctx, values)
        }
    }

    const val CAPTURED = "已加入待确认"
    const val STATE_READ_ERROR = "采集状态读取失败，请稍后重试"
    const val QUEUE_READ_ERROR = "失败队列读取失败，数量暂不可用，请稍后重试"

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/**
 * Failed rows stay in the same atomic batch, avoiding a second file that could drift after a
 * crash. The envelope retains the original parsed payment for retry, never exception messages.
 * Old unwrapped malformed rows are recognized too; valid unprocessed rows are not failures.
 */
internal object CaptureQueueCodec {
    const val MALFORMED = "malformed"
    const val STORAGE = "storage"
    private const val FAILED = "capture_failure"

    data class Entry(val id: String, val merchant: String, val pending: PendingPayment?,
                     val identities: List<String> = emptyList(), val legacy: List<String> = emptyList())

    fun payload(line: String): String {
        val row = JSONObject(line)
        return if (row.optString("kind") == FAILED) row.getString("payload") else line
    }

    fun failed(line: String, reason: String): String =
        JSONObject().put("kind", FAILED).put("reason", reason).put("payload", line).toString()

    fun failureReason(line: String): String? = try {
        val row = JSONObject(line)
        if (row.optString("kind") == FAILED) {
            if (row.optString("reason") == STORAGE) STORAGE else MALFORMED
        } else { decode(line); null }
    } catch (_: Exception) { MALFORMED }

    fun decode(line: String): Entry {
        val row = JSONObject(line)
        val id = row.getString("id").also { require(it.isNotBlank()) }
        val merchant = row.getString("merchant")
        if (row.optString("kind") == "merchant") return Entry(id, merchant, null)
        require(row.optString("kind") == "payment")
        val cents = row.getLong("cents").also { require(it > 0) }
        val source = row.getString("source").also { require(it.isNotBlank()) }
        val date = row.getString("date").also { java.time.LocalDate.parse(it) }
        val time = row.getString("time").also { java.time.LocalTime.parse(it) }
        return Entry(id, merchant, PendingPayment(id, cents, merchant, source, date, time),
            strings(row, "identities"), strings(row, "legacy"))
    }

    private fun strings(row: JSONObject, key: String): List<String> {
        if (!row.has(key)) return emptyList() // Old journal rows predate identity arrays.
        val array = row.getJSONArray(key)
        return (0 until array.length()).map { array.getString(it).also { value -> require(value.isNotBlank()) } }
    }
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
