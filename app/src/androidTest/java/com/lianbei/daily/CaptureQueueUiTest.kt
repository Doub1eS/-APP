package com.lianbei.daily

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class CaptureQueueUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var db: DailyDatabase
    private val visible = mutableStateOf(true)

    @Before fun setup() {
        root = File(context.cacheDir, "queue-ui-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        CaptureStore.testRoot = root
        PaymentSettings.enable(context, true)
        db = Room.inMemoryDatabaseBuilder(context, DailyDatabase::class.java).build()
    }

    @After fun cleanup() {
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        db.close()
        CaptureStore.testRoot = null
        root.deleteRecursively()
    }

    @Test fun discardRequiresExplicitIrrecoverableConfirmationAndKeepsNewQueue() = runBlocking {
        File(root, "capture-queue.jsonl").writeText("{private-notification-marker\n")
        CaptureDrain.drain(context, db.records())
        val p = PendingPayment("normal-arrival", 100, "正常店铺", "微信", "2026-10-20", "12:00")
        CaptureStore.capture(context, p, listOf(p.id), emptyList(), PaymentSettings.session(context))
        compose.setContent { LianbeiTheme { if (visible.value) CaptureQueuePanel(db.records()) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("capture-discard").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("private-notification-marker", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("capture-discard").performClick()
        compose.onNodeWithTag("capture-discard-confirm").assertTextContains("不可恢复", substring = true)
        compose.onNodeWithTag("capture-discard-cancel").performClick()
        assertEquals(1, CaptureStore.queueStatus(context).failedCount)
        compose.onNodeWithTag("capture-discard").performClick()
        compose.onNodeWithTag("capture-discard-confirm").performClick()
        compose.waitUntil(5_000) { CaptureStore.queueStatus(context).failedCount == 0 }
        assertEquals(1, CaptureStore.snapshot(context).queued)
        assertTrue(File(root, "capture-queue.jsonl").readText().contains("normal-arrival"))
    }

    @Test fun retryButtonRestoresStorageFailureWithoutShowingPayload() = runBlocking {
        val p = PendingPayment("failed", 100, "private-merchant-marker", "微信", "2026-10-20", "12:00")
        CaptureStore.capture(context, p, listOf(p.id), emptyList(), PaymentSettings.session(context))
        val unavailable = object : RecordDao by db.records() {
            override suspend fun capturePayment(pending: PendingPayment, identities: List<String>, legacyIds: List<String>): Boolean =
                throw java.io.IOException("private-exception-marker")
        }
        CaptureDrain.drain(context, unavailable)
        compose.setContent { LianbeiTheme { if (visible.value) CaptureQueuePanel(db.records()) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("capture-retry").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("private-", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("capture-retry").performClick()
        compose.waitUntil(5_000) {
            CaptureStore.queueStatus(context).failedCount == 0 &&
                runBlocking { db.records().payment("failed") != null }
        }
        assertEquals(0, CaptureStore.queueStatus(context).failedCount)
    }

    @Test fun unreadableQueueIsVisibleAndCannotOfferDestructiveAction() {
        val batch = File(root, "capture-queue.draining").apply { mkdirs() }
        File(batch, "retained").writeText("keep")
        compose.setContent { LianbeiTheme { if (visible.value) CaptureQueuePanel(db.records()) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("capture-queue-error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("capture-discard").assertDoesNotExist()
        assertTrue(File(batch, "retained").exists())
    }
}
