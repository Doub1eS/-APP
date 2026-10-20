package com.lianbei.daily

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class PaymentsTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun notificationActionConfirmsExactlyOnceAndIslandPayloadHasAction() = runBlocking {
        val dao = DailyDatabase.get(context).records()
        val id = "notification-test-${java.util.UUID.randomUUID()}"
        val p = PendingPayment(id, 1250, "测试商户", "演示", "2026-10-15", "10:00")
        dao.addPayment(p)
        try {
            val notification = PaymentNotifications.build(context, p, 3)
            val params = org.json.JSONObject(notification.extras.getString("miui.focus.param")!!).getJSONObject("param_v2")
            assertEquals(2, params.getJSONObject("hintInfo").getInt("type"))
            assertEquals("miui.focus.action_confirm", params.getJSONObject("hintInfo").getJSONObject("actionInfo").getString("action"))
            assertNotNull(notification.extras.getBundle("miui.focus.actions"))
            notification.actions[0].actionIntent.send()
            withTimeout(10000) { while (dao.payment(id)?.status != "confirmed") delay(50) }
            notification.actions[0].actionIntent.send()
            assertEquals(1, dao.observeAll().first().count { it.id == "payment-$id" })
            assertFalse(PaymentNotifications.build(context, p, 0).extras.containsKey("miui.focus.param"))
        } finally { dao.observeAll().first().filter { it.id == "payment-$id" }.forEach { dao.delete(it) } }
    }
    @Test fun confirmationIsAtomicAndIgnoredPaymentsStayIgnored() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DailyDatabase::class.java).build()
        try {
            val dao = db.records()
            val p = PendingPayment("test", 1234, "店铺", "微信", "2026-10-14", "10:00")
            assertTrue(dao.addPayment(p) != -1L)
            assertEquals(-1L, dao.addPayment(p))
            assertTrue(dao.observeAll().first().isEmpty())
            coroutineScope { repeat(5) { launch { dao.confirm(p.id, "餐饮") } } }
            assertEquals(1, dao.observeAll().first().size)
            assertEquals(1234L, dao.observeAll().first().single().value)
            assertTrue(dao.pending().first().isEmpty())
            dao.delete(dao.observeAll().first().single())
            dao.confirm(p.id)
            assertTrue(dao.observeAll().first().isEmpty())
            dao.addPayment(p.copy(id = "ignored")); dao.resolve("ignored", "ignored"); dao.confirm("ignored")
            assertTrue(dao.observeAll().first().isEmpty())
        } finally { db.close() }
    }
    @Test fun migrationKeepsV1Records() = runBlocking {
        val name = "migration-payment-test.db"
        context.deleteDatabase(name)
        context.openOrCreateDatabase(name, 0, null).use { sql ->
            sql.execSQL("CREATE TABLE records (id TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, date TEXT NOT NULL, time TEXT NOT NULL, title TEXT NOT NULL, detail TEXT NOT NULL, value INTEGER, extra TEXT NOT NULL, createdAt INTEGER NOT NULL)")
            sql.execSQL("CREATE TABLE categories (name TEXT NOT NULL PRIMARY KEY)")
            sql.execSQL("INSERT INTO records VALUES ('old','NOTE','2026-10-14','12:00','保留记录','',NULL,'',1)")
            sql.version = 1
        }
        val db = Room.databaseBuilder(context, DailyDatabase::class.java, name).addMigrations(DailyDatabase.MIGRATION_1_2, DailyDatabase.MIGRATION_2_3, DailyDatabase.MIGRATION_3_4, DailyDatabase.MIGRATION_4_5, DailyDatabase.MIGRATION_5_6).build()
        try {
            assertEquals("保留记录", db.records().observeAll().first().single().title)
            assertTrue(db.records().pending().first().isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
