package com.lianbei.daily

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.time.LocalDate

class PaymentEditingUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: DailyDatabase
    private val visible = mutableStateOf(true)
    private val dao get() = db.records()

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, DailyDatabase::class.java
        ).build()
    }

    @After fun close() {
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        db.close()
    }

    private fun seed(id: String, category: String = "其他") = runBlocking {
        dao.addPayment(PendingPayment(id, 1200, "店铺$id", "微信", "2026-01-01", "08:00", category = category))
    }

    private fun open(inboxDao: RecordDao = dao) {
        compose.setContent { LianbeiTheme { if (visible.value) PaymentInbox(inboxDao) {} } }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("多选") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun clickCardAction(tag: String, visibleNotice: String? = null) {
        compose.onNodeWithTag("payment-list").performScrollToNode(hasTestTag(tag))
        val action = compose.onNodeWithTag(tag).performScrollTo().assertIsEnabled().assertIsDisplayed()
        if (visibleNotice != null) {
            // Keep the notice visible: waiting for it to disappear would hide the touch obstruction.
            compose.onNodeWithText(visibleNotice).assertIsDisplayed()
            val buttonBounds = action.fetchSemanticsNode().boundsInRoot
            val noticeBounds = compose.onNodeWithTag("payment-notice").fetchSemanticsNode().boundsInRoot
            assertTrue(
                "提示不应遮挡单条操作：button=$buttonBounds, notice=$noticeBounds",
                buttonBounds.bottom <= noticeBounds.top || buttonBounds.top >= noticeBounds.bottom ||
                    buttonBounds.right <= noticeBounds.left || buttonBounds.left >= noticeBounds.right
            )
        }
        // A real touch checks hit testing; a semantics OnClick would bypass an overlapping notice.
        action.performClick()
        if (tag.startsWith("payment-edit-")) compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("payment-edit-save").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun replace(tag: String, value: String) {
        compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)
    }

    private fun waitForPayment(id: String, predicate: (PendingPayment) -> Boolean) {
        compose.waitUntil(10_000) { runBlocking { dao.payment(id)?.let(predicate) == true } }
    }

    @Test fun editSavesAllFieldsWithoutConfirmingAndSingleConfirmationUsesSavedCategory() {
        seed("a")
        open()
        clickCardAction("payment-edit-a")
        replace("payment-edit-amount", "23.45")
        replace("payment-edit-merchant", " 修改后的商户 ")
        replace("payment-edit-date", "2026-01-02")
        replace("payment-edit-time", "19:35")
        compose.onNode(hasTestTag("expense-parent-交通") and hasAnyAncestor(hasTestTag("payment-editor")))
            .performScrollTo().performClick()
        compose.onNode(hasTestTag("expense-child-地铁") and hasAnyAncestor(hasTestTag("payment-editor")))
            .performScrollTo().performClick()
        compose.onNodeWithTag("payment-edit-save").performClick()
        waitForPayment("a") { it.cents == 2345L }
        runBlocking {
            val p = dao.payment("a")!!
            assertEquals("修改后的商户", p.merchant)
            assertEquals("2026-01-02", p.date)
            assertEquals("19:35", p.time)
            assertEquals("交通 / 地铁", p.category)
            assertEquals("pending", p.status)
            assertTrue(dao.allRecords().isEmpty())
        }
        clickCardAction("payment-confirm-a", visibleNotice = "修改已保存，仍待确认。")
        waitForPayment("a") { it.status == "confirmed" }
        runBlocking {
            val record = dao.allRecords().single()
            assertEquals(2345L, record.value)
            assertEquals("交通", record.title)
            assertEquals("地铁", record.extra)
        }
    }

    @Test fun invalidAmountMerchantDateAndTimeShowErrorsWithoutWriting() {
        seed("a")
        open()
        clickCardAction("payment-edit-a")
        val invalidFields = listOf(
            Triple("payment-edit-amount", "0", "12.00"),
            Triple("payment-edit-amount", "1.001", "12.00"),
            Triple("payment-edit-amount", "92233720368547758.08", "12.00"),
            Triple("payment-edit-merchant", "  ", "店铺a"),
            Triple("payment-edit-merchant", "店".repeat(201), "店铺a"),
            Triple("payment-edit-date", LocalDate.now().plusDays(1).toString(), "2026-01-01"),
            Triple("payment-edit-date", "2026-02-30", "2026-01-01"),
            Triple("payment-edit-time", "24:60", "08:00")
        )
        invalidFields.forEach { (tag, invalid, valid) ->
            replace(tag, invalid)
            compose.onNodeWithTag("payment-edit-save").performClick()
            compose.onNodeWithTag("payment-edit-error").assertIsDisplayed()
            runBlocking {
                assertEquals(1200L, dao.payment("a")!!.cents)
                assertEquals("pending", dao.payment("a")!!.status)
                assertTrue(dao.allRecords().isEmpty())
            }
            replace(tag, valid)
        }
    }

    @Test fun processedPaymentCannotBeSavedFromAnAlreadyOpenEditor() {
        seed("a")
        open()
        clickCardAction("payment-edit-a")
        replace("payment-edit-amount", "99")
        runBlocking { dao.confirmMany(listOf("a")) }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("这笔账目已处理或已不存在，无法再修改。").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("payment-edit-save").assertIsNotEnabled()
        runBlocking {
            assertEquals(1200L, dao.payment("a")!!.cents)
            assertEquals(1200L, dao.allRecords().single().value)
        }
    }

    @Test fun cardCategorySurvivesReopeningAndBatchConfirmationUsesEachSavedCategory() {
        seed("a")
        seed("b", "食品酒水 / 早餐")
        open()
        compose.onNodeWithTag("payment-list").performScrollToNode(hasTestTag("payment-card-a"))
        compose.onNode(hasTestTag("expense-parent-交通") and hasAnyAncestor(hasTestTag("payment-card-a")))
            .performScrollTo().performClick()
        waitForPayment("a") { it.category == "交通" }
        compose.onNode(hasTestTag("expense-child-地铁") and hasAnyAncestor(hasTestTag("payment-card-a")))
            .performScrollTo().performClick()
        waitForPayment("a") { it.category == "交通 / 地铁" }
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { visible.value = true }
        compose.waitForIdle()
        clickCardAction("payment-edit-a")
        compose.onNode(hasTestTag("expense-child-地铁") and hasAnyAncestor(hasTestTag("payment-editor")))
            .performScrollTo().assertIsSelected()
        compose.onNodeWithTag("payment-edit-cancel").performClick()
        compose.onNodeWithText("多选").performClick()
        compose.onNodeWithText("全选").performClick()
        compose.onNodeWithTag("payment-batch-confirm").performClick()
        compose.onNodeWithText("确认入账 2 笔？").assertIsDisplayed()
        runBlocking { assertTrue(dao.allRecords().isEmpty()) }
        compose.onNodeWithTag("payment-batch-accept").performClick()
        compose.waitUntil(10_000) { runBlocking { dao.allRecords().size == 2 } }
        runBlocking {
            assertEquals("地铁", dao.allRecords().single { it.id == "payment-a" }.extra)
            assertEquals("早餐", dao.allRecords().single { it.id == "payment-b" }.extra)
        }
    }

    @Test fun selectionCanBeClearedAndIgnoreRequiresCountConfirmation() {
        seed("a")
        seed("b")
        open()
        compose.onNodeWithText("多选").performClick()
        compose.onNodeWithTag("payment-batch-ignore").assertIsNotEnabled()
        compose.onNodeWithText("全选").performClick()
        compose.onNodeWithText("取消选择").performClick()
        compose.onNodeWithTag("payment-batch-ignore").assertIsNotEnabled()
        clickCardAction("payment-select-a")
        compose.onNodeWithTag("payment-batch-ignore").performClick()
        compose.onNodeWithText("忽略 1 笔账目？").assertIsDisplayed()
        compose.onNodeWithTag("payment-batch-cancel").performClick()
        runBlocking { assertEquals("pending", dao.payment("a")!!.status) }
        compose.onNodeWithTag("payment-batch-ignore").performClick()
        compose.onNodeWithTag("payment-batch-accept").performClick()
        waitForPayment("a") { it.status == "ignored" }
        runBlocking {
            assertEquals("pending", dao.payment("b")!!.status)
            assertTrue(dao.allRecords().isEmpty())
        }
        // Single-item actions remain available while selection mode is active.
        clickCardAction("payment-confirm-b", visibleNotice = "已忽略 1 笔。")
        waitForPayment("b") { it.status == "confirmed" }
        runBlocking { assertEquals(1, dao.allRecords().size) }
    }

    @Test fun batchSnapshotExcludesNewPaymentsAndSkipsPaymentsProcessedElsewhere() {
        seed("a")
        seed("b")
        open()
        compose.onNodeWithText("多选").performClick()
        compose.onNodeWithText("全选").performClick()
        compose.onNodeWithTag("payment-batch-confirm").performClick()
        seed("c")
        runBlocking { dao.ignoreMany(listOf("a")) }
        compose.onNodeWithTag("payment-batch-accept").performClick()
        waitForPayment("b") { it.status == "confirmed" }
        compose.onNodeWithText("已确认 1 笔，另有 1 笔已处理或已不存在。").assertExists()
        runBlocking {
            assertEquals("ignored", dao.payment("a")!!.status)
            assertEquals("pending", dao.payment("c")!!.status)
            assertEquals(1, dao.allRecords().size)
        }
    }

    @Test fun failedSaveRetainsDraftAndRetryDoesNotConfirm() {
        seed("a")
        val realDao = dao
        var fail = true
        // Only the failing storage boundary is substituted; successful retries use real Room.
        val failingDao = object : RecordDao by realDao {
            override suspend fun updatePending(p: PendingPayment): Boolean {
                if (fail) throw IllegalStateException("storage unavailable")
                return realDao.updatePending(p)
            }
        }
        open(failingDao)
        clickCardAction("payment-edit-a")
        replace("payment-edit-amount", "34.56")
        compose.onNodeWithTag("payment-edit-save").performClick()
        compose.onNodeWithTag("payment-edit-error").assertIsDisplayed()
        compose.onNodeWithTag("payment-edit-amount").assertTextContains("34.56")
        runBlocking { assertEquals(1200L, dao.payment("a")!!.cents) }
        compose.runOnIdle { fail = false }
        compose.onNodeWithTag("payment-edit-save").performClick()
        waitForPayment("a") { it.cents == 3456L }
        runBlocking {
            assertEquals("pending", dao.payment("a")!!.status)
            assertTrue(dao.allRecords().isEmpty())
        }
    }

    @Test fun batchSubmissionIsDisabledWhileStorageIsRunning() {
        seed("a")
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val realDao = dao
        var submissions = 0
        val delayedDao = object : RecordDao by realDao {
            override suspend fun confirmMany(ids: List<String>): Int {
                submissions++
                entered.complete(Unit)
                gate.await()
                return realDao.confirmMany(ids)
            }
        }
        open(delayedDao)
        compose.onNodeWithText("多选").performClick()
        compose.onNodeWithText("全选").performClick()
        compose.onNodeWithTag("payment-batch-confirm").performClick()
        compose.onNodeWithTag("payment-batch-accept").performClick()
        try {
            compose.waitUntil(10_000) { entered.isCompleted }
            compose.onNodeWithTag("payment-batch-accept").assertIsNotEnabled().performClick()
            compose.onNodeWithTag("payment-batch-cancel").assertIsNotEnabled()
            compose.runOnIdle { assertEquals(1, submissions) }
            runBlocking { assertTrue(dao.allRecords().isEmpty()) }
        } finally { gate.complete(Unit) }
        waitForPayment("a") { it.status == "confirmed" }
        runBlocking { assertEquals(1, dao.allRecords().size) }
    }

    @Test fun categoryChangesCanFollowEachOtherWhileStorageIsSlowAndConfirmationWaits() {
        seed("a")
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val realDao = dao
        val delayedDao = object : RecordDao by realDao {
            override suspend fun updatePending(p: PendingPayment): Boolean {
                entered.complete(Unit)
                gate.await()
                return realDao.updatePending(p)
            }
        }
        open(delayedDao)
        compose.onNodeWithTag("payment-list").performScrollToNode(hasTestTag("expense-parent-交通"))
        compose.onNodeWithTag("expense-parent-交通").performScrollTo().performClick()
        try {
            compose.waitUntil(10_000) { entered.isCompleted }
            compose.onNodeWithTag("expense-child-地铁").performScrollTo().performClick()
            compose.onNodeWithTag("payment-confirm-a").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("payment-edit-a").assertIsNotEnabled()
        } finally { gate.complete(Unit) }
        waitForPayment("a") { it.category == "交通 / 地铁" }
        clickCardAction("payment-confirm-a")
        waitForPayment("a") { it.status == "confirmed" }
        runBlocking { assertEquals("地铁", dao.allRecords().single().extra) }
    }

    @Test fun batchFailureRemainsVisibleAndCanBeRetriedWithoutLosingSelection() {
        seed("a")
        val realDao = dao
        var fail = true
        val failingDao = object : RecordDao by realDao {
            override suspend fun ignoreMany(ids: List<String>): Int {
                if (fail) throw IllegalStateException("storage unavailable")
                return realDao.ignoreMany(ids)
            }
        }
        open(failingDao)
        compose.onNodeWithText("多选").performClick()
        compose.onNodeWithText("全选").performClick()
        compose.onNodeWithTag("payment-batch-ignore").performClick()
        compose.onNodeWithTag("payment-batch-accept").performClick()
        compose.onNodeWithText("批量处理失败，请重试；所选账目已保留。").assertIsDisplayed()
        runBlocking { assertEquals("pending", dao.payment("a")!!.status) }
        compose.runOnIdle { fail = false }
        compose.onNodeWithTag("payment-batch-accept").performClick()
        waitForPayment("a") { it.status == "ignored" }
        runBlocking { assertTrue(dao.allRecords().isEmpty()) }
    }
}
