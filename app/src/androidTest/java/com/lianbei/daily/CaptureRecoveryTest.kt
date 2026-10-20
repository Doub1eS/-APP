package com.lianbei.daily

import android.app.Notification
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*

class CaptureRecoveryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var db: DailyDatabase
    @Before fun setup() {
        root = File(context.cacheDir, "recovery-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        CaptureStore.testRoot = root
        PaymentSettings.enable(context, true)
        db = Room.inMemoryDatabaseBuilder(context, DailyDatabase::class.java).build()
    }
    @After fun cleanup() { db.close(); CaptureStore.testRoot = null; root.deleteRecursively() }
    private suspend fun capture(key: String = "key", body: String = "已支付¥12.00") = PaymentCapture.accept(
        context, "com.tencent.mm", key, 1001,
        Notification.Builder(context, "test").setContentTitle("微信支付").setContentText(body).setWhen(1000).build(),
        PaymentSettings.session(context))

    @Test fun failedQueueWriteDoesNotConsumePaymentIdentity() = runBlocking {
        val queue = File(root, "capture-queue.jsonl").apply { mkdirs() }
        assertTrue(capture().startsWith("处理失败"))
        assertTrue(queue.delete())
        assertEquals(CaptureStore.CAPTURED, capture())
        CaptureDrain.drain(context, db.records())
        assertEquals(1200L, db.records().pending().first().single().cents)
    }

    @Test fun enrichedIdentitySurvivesCaptureCacheEviction() = runBlocking {
        capture()
        CaptureDrain.drain(context, db.records())
        db.records().confirm(db.records().pending().first().single().id)
        capture(body = "已支付¥12.00\n交易单号：TX202610200001")
        CaptureDrain.drain(context, db.records())
        File(root, "capture-seen.tsv").delete() // bounded cache eviction must not erase DB identity
        capture(key = "new-key", body = "已支付¥12.00\n交易单号：TX202610200001")
        CaptureDrain.drain(context, db.records())
        assertTrue(db.records().pending().first().isEmpty())
        assertEquals(1, db.records().allRecords().size)
    }

    @Test fun queueCountIncludesArrivalsWhileBatchIsDraining() = runBlocking {
        capture("first")
        val batch = CaptureStore.takeBatch(context)!!
        capture("second")
        CaptureStore.finishBatch(context, batch, emptyList())
        assertEquals(1, CaptureStore.snapshot(context).queued)
        CaptureDrain.drain(context, db.records())
        assertEquals(0, CaptureStore.snapshot(context).queued)
    }

    @Test fun concurrentDrainsDoNotApplyOneBatchTwice() = runBlocking {
        capture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val dao = object : RecordDao by db.records() {
            override suspend fun capturePayment(pending: PendingPayment, identities: List<String>, legacyIds: List<String>): Boolean {
                calls.incrementAndGet()
                entered.complete(Unit)
                release.await()
                return db.records().capturePayment(pending, identities, legacyIds)
            }
        }
        val first = async(Dispatchers.IO) { CaptureDrain.drain(context, dao) }
        entered.await()
        val second = async(Dispatchers.IO) { CaptureDrain.drain(context, dao) }
        delay(200)
        release.complete(Unit)
        awaitAll(first, second)
        assertEquals(1, calls.get())
        assertEquals(1, db.records().pending().first().size)
    }

    @Test fun unreadableBatchIsReportedAndPreserved() = runBlocking {
        val batch = File(root, "capture-queue.draining").apply { mkdirs() }
        File(batch, "retained").writeText("keep")
        assertTrue(runCatching { CaptureDrain.drain(context, db.records()) }.isFailure)
        assertTrue(File(batch, "retained").exists())
    }

    @Test fun cancelledDrainCanReplayWithoutLosingPayments() = runBlocking {
        capture()
        val entered = CompletableDeferred<Unit>()
        val dao = object : RecordDao by db.records() {
            override suspend fun capturePayment(pending: PendingPayment, identities: List<String>, legacyIds: List<String>): Boolean {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val job = launch(Dispatchers.IO) { CaptureDrain.drain(context, dao) }
        entered.await()
        job.cancelAndJoin()
        CaptureDrain.drain(context, db.records())
        assertEquals(1, db.records().pending().first().size)
    }

    @Test fun malformedRetainedRowDoesNotBlockLaterPayments() = runBlocking {
        File(root, "capture-queue.jsonl").writeText("{torn\n")
        CaptureDrain.drain(context, db.records())
        capture("after-malformed")
        CaptureDrain.drain(context, db.records())
        assertEquals(1, db.records().pending().first().size)
        assertEquals(1, CaptureStore.snapshot(context).queued)
    }

    @Test fun legacyMigrationPreservesRecoveredOptOutAndSession() {
        val legacyContext = object : android.content.ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) =
                context.getSharedPreferences("${root.name}-$name", mode)
        }
        val legacy = legacyContext.getSharedPreferences("payments", 0)
        try {
            legacy.edit().putBoolean("enabled", true).putString("session", "old-session").commit()
            CaptureStore.update(context) { it["enabled"] = "false"; it["session"] = "new-session" }
            val state = File(root, "capture-state.properties")
            assertTrue(state.renameTo(File(root, "capture-state.properties.bak")))
            CaptureStore.migrateLegacy(legacyContext)
            val recovered = CaptureStore.state(context)
            assertEquals("false", recovered["enabled"])
            assertEquals("new-session", recovered["session"])
        } finally { legacy.edit().clear().commit() }
    }
}
