package com.lianbei.daily

import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CapturePerformanceTest {
    /**
     * Reproducible disk experiment, not a timing gate. The baseline reproduces the old durable
     * journal append + state rewrite for each delivery, excluding its additional index reads.
     * Both arms drain through the real Room path. Times are logged, never asserted as a speedup.
     * Raw measurements survive scratch cleanup in externalFilesDir/capture-performance.jsonl.
     */
    @Test fun repeatedDeliveryReportsQueueSizeAndEnqueueAndDrainTime() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val deliveries = 200
        val measurements = JSONObject().put("deliveries", deliveries)
            .put("baseline", "durable append + state rewrite; excludes old index reads")
            .put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        for (baseline in listOf(true, false)) {
            val root = File(context.cacheDir, "capture-perf-${java.util.UUID.randomUUID()}").apply { mkdirs() }
            val db = Room.inMemoryDatabaseBuilder(context, DailyDatabase::class.java).build()
            CaptureStore.testRoot = root
            try {
                PaymentSettings.enable(context, true)
                val session = PaymentSettings.session(context)
                val p = PendingPayment("same-event", 1200, "测试商户", "微信", "2026-10-20", "12:00")
                val start = SystemClock.elapsedRealtimeNanos()
                CaptureStore.capture(context, p, listOf(p.id), listOf(p.id), session)
                val queue = File(root, "capture-queue.jsonl")
                val row = queue.readBytes()
                repeat(deliveries - 1) {
                    if (baseline) {
                        RandomAccessFile(queue, "rw").use { file ->
                            file.seek(file.length())
                            file.write(row)
                            file.fd.sync()
                        }
                        CaptureStore.update(context) { it["queued"] = ((it["queued"]?.toInt() ?: 0) + 1).toString() }
                    } else CaptureStore.capture(context, p, listOf(p.id), listOf(p.id), session)
                }
                val enqueueNs = SystemClock.elapsedRealtimeNanos() - start
                val rows = queue.readLines().size
                val bytes = queue.length()
                assertEquals(if (baseline) deliveries else 1, rows)
                val drainStart = SystemClock.elapsedRealtimeNanos()
                val applied = CaptureDrain.drain(context, db.records())
                val drainNs = SystemClock.elapsedRealtimeNanos() - drainStart
                assertEquals(rows, applied)
                assertEquals(1, db.records().pending().first().size)
                measurements.put(if (baseline) "before" else "after", JSONObject()
                    .put("queueRows", rows).put("queueBytes", bytes)
                    .put("enqueueNs", enqueueNs).put("drainNs", drainNs))
            } finally {
                db.close()
                CaptureStore.testRoot = null
                root.deleteRecursively()
            }
        }
        val output = File(context.getExternalFilesDir(null) ?: context.filesDir, "capture-performance.jsonl")
        output.appendText(measurements.put("measuredAt", System.currentTimeMillis()).toString() + "\n")
        Log.i("CapturePerformance", measurements.toString())
        Log.i("CapturePerformance", "rawResults=${output.absolutePath}")
        Unit // JUnit4 requires a void test method; Log.i returns an Int.
    }
}
