package com.lianbei.daily

import android.app.Notification
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Parses one payment notification and, when it is a supported, unambiguous, new payment, appends it
 * to the capture queue. It runs in the `:capture` process and never opens the database: Room stays
 * owned by the UI process, which drains the queue.
 */
object PaymentCapture {
    suspend fun setEnabled(context: Context, value: Boolean) = withContext(Dispatchers.IO) {
        PaymentSettings.enable(context, value)
        if (value) {
            PaymentWatchService.start(context)
            PaymentListener.requestRebindNow(context)
        } else {
            PaymentWatchService.stop(context)
        }
    }

    fun content(notification: Notification): Pair<String, String> {
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val parts = buildList {
            add(extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty())
            add(extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty())
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { add(it.toString()) }
        }.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sortedByDescending { it.length }
        // Some apps repeat their collapsed text inside bigText and textLines.
        val kept = mutableListOf<String>()
        parts.forEach { part -> if (kept.none { it.contains(part) }) kept.add(part) }
        return title to kept.joinToString("\n")
    }

    suspend fun accept(
        context: Context,
        pkg: String,
        key: String,
        postedAt: Long,
        notification: Notification,
        session: String
    ): String = withContext(Dispatchers.IO) {
        val state = CaptureStore.state(context)
        if (state["enabled"] != "true" || session != state["session"].orEmpty()) return@withContext "识别已关闭"
        val source = PaymentParser.sources[pkg] ?: return@withContext "来源未支持"
        fun result(reason: String): String {
            PaymentDiagnostics.record(context, source, reason)
            return reason
        }
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return@withContext result("通知分组汇总，已跳过")
        try {
            val (title, body) = content(notification)
            val parsed = PaymentParser.inspect(pkg, title, body)
            val payment = parsed.payment ?: return@withContext result(parsed.reason)
            val eventTime = notification.`when`.takeIf { it > 0 && it <= postedAt + 60_000 } ?: postedAt
            val id = PaymentParser.fingerprint(pkg, key, eventTime, payment.copy(transactionId = null, merchant = ""))
            val identities = listOfNotNull(id, payment.transactionId?.let { PaymentParser.fingerprint(pkg, key, eventTime, payment) })
            // Honour payments created by the previous postTime-based parser on upgrade.
            val legacy = listOf(
                PaymentParser.fingerprint(pkg, key, postedAt, payment.copy(transactionId = null)),
                PaymentParser.fingerprint(pkg, key, eventTime, payment.copy(transactionId = null))
            )

            val time = Instant.ofEpochMilli(eventTime).atZone(ZoneId.systemDefault())
            val pending = PendingPayment(id, payment.cents, payment.merchant, payment.source, time.toLocalDate().toString(), time.format(DateTimeFormatter.ofPattern("HH:mm")))
            when (CaptureStore.capture(context, pending, identities, legacy, session)) {
                CaptureStore.Commit.DISABLED -> return@withContext "识别已关闭"
                CaptureStore.Commit.DUPLICATE -> return@withContext result("重复通知，已跳过")
                CaptureStore.Commit.NEW -> Unit
            }
            // A disabled reminder must never prevent the payment from appearing in the inbox.
            runCatching { PaymentNotifications.show(context, pending) }
            result("已加入待确认")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            runCatching { result("处理失败，请检查待确认列表后重试") }
            "处理失败，请检查待确认列表后重试"
        }
    }
}

/** Writes queued captures into Room. Runs in the default process, which owns the database. */
object CaptureDrain {
    private val mutex = Mutex()
    suspend fun drain(context: Context, dao: RecordDao): Int = withContext(Dispatchers.IO) { mutex.withLock {
        var applied = 0
        var batches = 0
        while (batches < 8) {
            val batch = CaptureStore.takeBatch(context) ?: break
            batches++
            val lines = android.util.AtomicFile(batch).openRead().bufferedReader().use { it.readLines() }.filter { it.isNotBlank() }
            val keep = mutableListOf<String>()
            lines.forEach { line ->
                try { apply(dao, line) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { keep.add(line) }
            }
            CaptureStore.finishBatch(context, batch, keep)
            applied += lines.size - keep.size
            if (keep.isNotEmpty()) break
        }
        applied
    } }

    private suspend fun apply(dao: RecordDao, line: String) {
        val row = org.json.JSONObject(line)
        val id = row.getString("id")
        if (row.optString("kind") == "merchant") {
            dao.enrichPayment(id, row.getString("merchant"))
            return
        }
        val pending = PendingPayment(
            id,
            row.getLong("cents"),
            row.getString("merchant"),
            row.getString("source"),
            row.getString("date"),
            row.getString("time")
        )
        dao.capturePayment(pending, strings(row, "identities"), strings(row, "legacy"))
    }

    private fun strings(row: org.json.JSONObject, key: String): List<String> {
        val array = row.optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optString(it).takeIf { value -> value.isNotBlank() } }
    }
}
