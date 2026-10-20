package com.lianbei.daily

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*

/** New regressions; CaptureRecoveryTest remains unchanged and must also run. */
class CaptureQueueManagementTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var db: DailyDatabase
    private val payment = PendingPayment("event-a", 1200, "待补充商户", "微信", "2026-10-20", "12:00")

    @Before fun setup() {
        root = File(context.cacheDir, "queue-management-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        CaptureStore.testRoot = root
        PaymentSettings.enable(context, true)
        db = Room.inMemoryDatabaseBuilder(context, DailyDatabase::class.java).build()
    }

    @After fun cleanup() {
        db.close()
        CaptureStore.testRoot = null
        root.deleteRecursively()
    }

    private fun capture(p: PendingPayment = payment, ids: List<String> = listOf(p.id)) =
        CaptureStore.capture(context, p, ids, listOf(p.id), PaymentSettings.session(context))

    @Test fun exactDuplicateDoesNotRewriteQueueIndexOrState() {
        capture()
        val files = listOf("capture-queue.jsonl", "capture-seen.tsv", "capture-state.properties").map { File(root, it) }
        val contents = files.map { it.readBytes() }
        val stamps = files.map { assertTrue(it.setLastModified(1_600_000_000_000L)); it.lastModified() }
        repeat(40) { assertEquals(CaptureStore.Commit.DUPLICATE, capture()) }
        files.forEachIndexed { i, file ->
            assertArrayEquals(contents[i], file.readBytes())
            assertEquals(stamps[i], file.lastModified())
        }
        assertEquals(1, CaptureStore.snapshot(context).queued)
    }

    @Test fun sameAmountWithDifferentIdentityIsNeverSuppressed() = runBlocking {
        capture()
        capture(payment.copy(id = "event-b"))
        assertEquals(2, CaptureStore.snapshot(context).queued)
        CaptureDrain.drain(context, db.records())
        assertEquals(2, db.records().pending().first().size)
    }

    @Test fun merchantAndTransactionEnrichmentReachRoomAndSurviveIndexEviction() = runBlocking {
        capture()
        capture(payment.copy(merchant = "早餐店"))
        capture(payment.copy(merchant = "早餐店"), listOf(payment.id, "transaction-alias"))
        assertEquals(3, CaptureStore.snapshot(context).queued)
        CaptureDrain.drain(context, db.records())
        assertEquals("早餐店", db.records().pending().first().single().merchant)
        db.records().confirm(payment.id)
        assertTrue(File(root, "capture-seen.tsv").delete())
        capture(payment.copy(id = "reposted", merchant = "早餐店"), listOf("reposted", "transaction-alias"))
        CaptureDrain.drain(context, db.records())
        assertTrue(db.records().pending().first().isEmpty())
        assertEquals(1, db.records().allRecords().size)
        assertEquals(payment.id, db.records().identityPayment("transaction-alias"))
    }

    @Test fun identityOrderAloneDoesNotCreateAnotherQueueRow() {
        capture(ids = listOf(payment.id, "alias"))
        capture(ids = listOf("alias", payment.id, "alias"))
        assertEquals(1, CaptureStore.snapshot(context).queued)
    }

    @Test fun fullPayloadChangesAreQueuedEvenWhenTheIdentityIsUnchanged() {
        capture()
        capture(payment.copy(cents = 1201))
        capture(payment.copy(source = "支付宝"))
        capture(payment.copy(date = "2026-10-21"))
        capture(payment.copy(time = "12:01"))
        assertEquals(5, CaptureStore.snapshot(context).queued)
    }

    @Test fun exactReplayAfterBoundedIndexEvictionIsStillDeduplicatedByRoom() = runBlocking {
        capture()
        CaptureDrain.drain(context, db.records())
        db.records().confirm(payment.id)
        repeat(20) { capture() }
        assertEquals(0, CaptureStore.snapshot(context).queued)
        // Force actual bounded-index eviction without deleting the cache file.
        capture(payment.copy(id = "padding"), (0..810).map { "padding-alias-$it" })
        assertFalse(File(root, "capture-seen.tsv").readLines().any { it.substringBefore('\t') == payment.id })
        CaptureDrain.drain(context, db.records())
        capture()
        assertEquals(1, CaptureStore.snapshot(context).queued)
        CaptureDrain.drain(context, db.records())
        assertEquals(listOf("padding"), db.records().pending().first().map { it.id })
        assertEquals(1, db.records().allRecords().size)
    }

    @Test fun malformedAndStorageFailuresExposeOnlyFixedReasonsAndCanRetry() = runBlocking {
        File(root, "capture-queue.jsonl").writeText("{private-marker-9384\n")
        capture()
        val unavailable = object : RecordDao by db.records() {
            override suspend fun capturePayment(pending: PendingPayment, identities: List<String>, legacyIds: List<String>): Boolean =
                throw java.io.IOException("private-database-marker")
        }
        CaptureDrain.drain(context, unavailable)
        val status = CaptureStore.queueStatus(context)
        assertEquals(2, status.failedCount)
        assertEquals(1, status.malformedCount)
        assertEquals(1, status.storageCount)
        assertFalse(status.toString().contains("private-"))
        CaptureDrain.retryFailed(context, db.records())
        assertEquals(1, db.records().pending().first().size)
        assertEquals(1, CaptureStore.queueStatus(context).failedCount)
        assertEquals(1, CaptureStore.snapshot(context).queued)
    }

    @Test fun discardPreservesNewQueueAndUnprocessedValidRows() = runBlocking {
        File(root, "capture-queue.jsonl").writeText("{broken\n")
        CaptureDrain.drain(context, db.records())
        val confirmation = CaptureStore.queueStatus(context)
        capture()
        // Simulate a crash after takeBatch merged arrivals but before applying the batch.
        CaptureStore.takeBatch(context)
        capture(payment.copy(id = "arrival-after-merge"))
        assertEquals(1, CaptureDrain.discardFailed(context, confirmation))
        assertEquals(0, CaptureStore.queueStatus(context).failedCount)
        assertEquals(2, CaptureStore.snapshot(context).queued)
        CaptureDrain.drain(context, db.records())
        assertEquals(2, db.records().pending().first().size)
    }

    @Test fun staleConfirmationCannotDiscardAdditionalFailures() = runBlocking {
        File(root, "capture-queue.jsonl").writeText("{first\n")
        CaptureDrain.drain(context, db.records())
        val confirmation = CaptureStore.queueStatus(context)
        File(root, "capture-queue.jsonl").writeText("{second\n")
        CaptureDrain.drain(context, db.records())
        assertTrue(runCatching { CaptureDrain.discardFailed(context, confirmation) }.isFailure)
        assertEquals(2, CaptureStore.queueStatus(context).failedCount)
    }

    @Test fun discardedPaymentCanBeCapturedAgainInsteadOfBeingSwallowedByCache() = runBlocking {
        capture()
        val unavailable = object : RecordDao by db.records() {
            override suspend fun capturePayment(pending: PendingPayment, identities: List<String>, legacyIds: List<String>): Boolean =
                throw java.io.IOException()
        }
        CaptureDrain.drain(context, unavailable)
        CaptureDrain.discardFailed(context, CaptureStore.queueStatus(context))
        capture()
        CaptureDrain.drain(context, db.records())
        assertEquals(1, db.records().pending().first().size)
    }

    @Test fun discardWaitsForDrainAndCannotDeleteArrivals() = runBlocking {
        File(root, "capture-queue.jsonl").writeText("{broken\n")
        CaptureDrain.drain(context, db.records())
        val confirmation = CaptureStore.queueStatus(context)
        capture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val paused = object : RecordDao by db.records() {
            override suspend fun capturePayment(pending: PendingPayment, identities: List<String>, legacyIds: List<String>): Boolean {
                entered.complete(Unit)
                release.await()
                return db.records().capturePayment(pending, identities, legacyIds)
            }
        }
        val drain = async(Dispatchers.IO) { CaptureDrain.drain(context, paused) }
        try {
            withTimeout(5_000) { entered.await() }
            val discard = async(Dispatchers.IO) { CaptureDrain.discardFailed(context, confirmation) }
            delay(100)
            assertFalse(discard.isCompleted)
            capture(payment.copy(id = "during-drain"))
            release.complete(Unit)
            withTimeout(5_000) { drain.await(); assertEquals(1, discard.await()) }
        } finally { release.complete(Unit) }
        CaptureDrain.drain(context, db.records())
        assertEquals(2, db.records().pending().first().size)
    }

    @Test fun unreadableStatusAndBatchReturnFixedErrorsWithoutDestroyingFiles() {
        val state = File(root, "capture-state.properties")
        assertTrue(state.delete())
        state.mkdirs()
        File(state, "private-marker").writeText("keep")
        val batch = File(root, "capture-queue.draining").apply { mkdirs() }
        File(batch, "private-marker").writeText("keep")
        val status = CaptureStore.snapshot(context)
        assertTrue(status.readError.isNotBlank())
        assertFalse(status.readError.contains("private-marker"))
        val queue = CaptureStore.queueStatus(context)
        assertTrue(queue.readError.isNotBlank())
        assertTrue(File(batch, "private-marker").exists())
    }
}
