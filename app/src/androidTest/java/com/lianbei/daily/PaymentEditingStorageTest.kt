package com.lianbei.daily

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class PaymentEditingStorageTest {
    private lateinit var db: DailyDatabase
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, DailyDatabase::class.java).build() }
    @After fun close() { db.close() }
    private fun pending(id:String) = PendingPayment(id, 1200, "店铺", "微信", "2026-01-01", "12:00")

    @Test fun editPersistsButDoesNotConfirmAndBatchUsesSavedCategory() = runBlocking {
        val dao=db.records()
        dao.addPayment(pending("a"))
        assertTrue(dao.updatePending(pending("a").copy(cents=2330,merchant="午餐店",category="食品酒水 / 午餐")))
        assertTrue(dao.allRecords().isEmpty())
        assertEquals(1, dao.confirmMany(listOf("a","a")))
        assertEquals("午餐", dao.record("payment-a")?.extra)
        assertEquals(2330L, dao.record("payment-a")?.value)
        assertFalse(dao.updatePending(pending("a")))
        assertEquals(0, dao.ignoreMany(listOf("a")))
    }

    @Test fun invalidEditsDoNotChangePaymentAndIgnoredRowsCannotConfirm() = runBlocking {
        val dao=db.records();dao.addPayment(pending("a"));dao.addPayment(pending("b"))
        assertTrue(runCatching { dao.updatePending(pending("a").copy(cents=0)) }.isFailure)
        assertTrue(runCatching { dao.updatePending(pending("a").copy(date="2026-02-30")) }.isFailure)
        assertEquals(1200L,dao.payment("a")?.cents)
        assertEquals(2,dao.ignoreMany(listOf("a","b")))
        assertEquals(0,dao.confirmMany(listOf("a","b")))
        assertTrue(dao.allRecords().isEmpty())
    }
}
