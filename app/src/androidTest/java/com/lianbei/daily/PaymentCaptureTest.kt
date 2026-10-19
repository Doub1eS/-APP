package com.lianbei.daily

import android.app.Notification
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

/**
 * Capture and storage are separate processes now: the listener only queues, and the UI process
 * drains the queue into Room. These tests therefore always run capture followed by a drain and
 * assert that nothing reaches Room before the drain.
 */
class PaymentCaptureTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    // Identities are hashed and persisted, so each run needs its own keys.
    private val run = java.util.UUID.randomUUID().toString().take(8)

    /**
     * The live app on the same device keeps its own queue in files/, so tests must not share it.
     * [CaptureStore.testRoot] redirects every state, queue and dedup file to a scratch directory.
     */
    @org.junit.Before fun isolateStore() {
        CaptureStore.testRoot = java.io.File(context.cacheDir, "capture-test-${java.util.UUID.randomUUID()}").apply { mkdirs() }
    }

    @org.junit.After fun releaseStore() {
        CaptureStore.testRoot?.deleteRecursively()
        CaptureStore.testRoot = null
    }

    private fun notification(time: Long = 1000, title: String = "微信支付", body: String = "付款成功 12元\n商户：早餐店") =
        Notification.Builder(context, "test").setContentTitle(title).setContentText(body).setWhen(time).build()

    private suspend fun accept(dao: RecordDao, pkg: String, key: String, postedAt: Long, n: Notification, session: String = PaymentSettings.session(context)): String {
        val outcome = PaymentCapture.accept(context, pkg, key, postedAt, n, session)
        CaptureDrain.drain(context, dao)
        return outcome
    }

    @Test fun xiaomiCompactNotificationCreatesOnePendingExpense() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DailyDatabase::class.java).build()
        val before = PaymentSettings.enabled(context)
        PaymentSettings.enable(context, true)
        try {
            val n = notification(body = "已支付¥22.00")
            val dao = db.records()
            assertEquals("已加入待确认", PaymentCapture.accept(context, "com.tencent.mm", "$run-xiaomi", 1001, n, PaymentSettings.session(context)))
            // The capture process must not touch the database before the UI process drains.
            assertTrue(dao.allRecords().isEmpty())
            assertTrue(dao.pending().first().isEmpty())
            assertEquals(1, CaptureDrain.drain(context, dao))
            assertEquals(0, CaptureDrain.drain(context, dao))
            assertEquals(2200L, dao.pending().first().single().cents)
            assertEquals("重复通知，已跳过", accept(dao, "com.tencent.mm", "$run-xiaomi", 1002, n))
            assertEquals(1, dao.pending().first().size)
            assertTrue(dao.allRecords().isEmpty())
        } finally { PaymentSettings.enable(context, before); db.close() }
    }

    @Test fun notificationUpdateAndReconnectDoNotCreateAnotherPayment() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DailyDatabase::class.java).build()
        val before = PaymentSettings.enabled(context)
        PaymentSettings.enable(context, true)
        try {
            val n = notification()
            val dao = db.records()
            assertEquals("已加入待确认", accept(dao, "com.tencent.mm", "$run-key", 1001, n))
            assertEquals("重复通知，已跳过", accept(dao, "com.tencent.mm", "$run-key", 1500, n))
            val p = dao.pending().first().single()
            dao.confirm(p.id, "餐饮")
            accept(dao, "com.tencent.mm", "$run-key", 1700, n)
            assertTrue(dao.pending().first().isEmpty())
            assertEquals(1, dao.allRecords().size)
            accept(dao, "com.tencent.mm", "$run-key-2", 3001, notification(3000))
            assertEquals(1, dao.pending().first().size)
        } finally { PaymentSettings.enable(context, before); db.close() }
    }

    @Test fun expandedTextAndLinesUseSamePipelineAndDisabledCaptureStoresNothing() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DailyDatabase::class.java).build()
        val before = PaymentSettings.enabled(context)
        PaymentSettings.enable(context, true)
        try {
            val n = notification(body = "付款成功 12元")
            n.extras.putCharSequence(Notification.EXTRA_BIG_TEXT, "付款成功 12元\n商户：早餐店")
            n.extras.putCharSequenceArray(Notification.EXTRA_TEXT_LINES, arrayOf("付款成功 12元", "商户：早餐店"))
            assertEquals("已加入待确认", accept(db.records(), "com.tencent.mm", "$run-a", 1001, n))
            assertEquals("早餐店", db.records().pending().first().single().merchant)
            PaymentSettings.enable(context, false)
            assertEquals("识别已关闭", accept(db.records(), "com.tencent.mm", "$run-b", 2001, notification(2000)))
            assertEquals(1, db.records().pending().first().size)
        } finally { PaymentSettings.enable(context, before); db.close() }
    }

    @Test fun diagnosticDoesNotPersistNotificationContent() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DailyDatabase::class.java).build()
        val before = PaymentSettings.enabled(context)
        PaymentSettings.enable(context, true)
        try {
            val marker = "private-chat-marker-9384"
            accept(db.records(), "com.tencent.mm", "$run-a", 1001, notification(title = "朋友", body = marker))
            assertTrue(db.records().pending().first().isEmpty())
            val state = PaymentDiagnostics.snapshot(context)
            assertEquals("不是支持的支付服务标题", state.reason)
            assertFalse(CaptureStore.state(context).values.joinToString().contains(marker))
            assertFalse(context.getSharedPreferences("payment_diagnostics", 0).all.toString().contains(marker))
        } finally { PaymentSettings.enable(context, before); db.close() }
    }

    @Test fun enrichedNotificationAndRepostedTransactionStayOnePayment() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DailyDatabase::class.java).build()
        val before = PaymentSettings.enabled(context)
        PaymentSettings.enable(context, true)
        try {
            val dao = db.records()
            val transaction = "TX${run}0001"
            accept(dao, "com.tencent.mm", "$run-key", 1001, notification(body = "付款成功 12元"))
            val first = dao.pending().first().single()
            assertEquals(PaymentParser.MISSING_MERCHANT, first.merchant)
            dao.confirm(first.id)
            val enriched = notification(body = "付款成功 12元\n商户：早餐店\n交易单号：$transaction")
            accept(dao, "com.tencent.mm", "$run-key", 1500, enriched)
            accept(dao, "com.tencent.mm", "$run-key-new", 2000, enriched)
            assertTrue(dao.pending().first().isEmpty())
            assertEquals(1, dao.allRecords().size)
        } finally { PaymentSettings.enable(context, before); db.close() }
    }

    @Test fun oldOptInSessionCannotCaptureAfterReenable() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DailyDatabase::class.java).build()
        val before = PaymentSettings.enabled(context)
        try {
            PaymentCapture.setEnabled(context, true)
            val previous = PaymentSettings.session(context)
            PaymentCapture.setEnabled(context, false)
            PaymentCapture.setEnabled(context, true)
            assertEquals("识别已关闭", accept(db.records(), "com.tencent.mm", "$run-key", 1001, notification(), previous))
            assertTrue(db.records().pending().first().isEmpty())
        } finally { PaymentCapture.setEnabled(context, before); db.close() }
    }
}
