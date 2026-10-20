package com.lianbei.daily

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

class BackupTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var source: DailyDatabase
    private lateinit var target: DailyDatabase
    @Before fun setup() {
        source = Room.inMemoryDatabaseBuilder(context, DailyDatabase::class.java).build()
        target = Room.inMemoryDatabaseBuilder(context, DailyDatabase::class.java).build()
    }
    @After fun close() { source.close(); target.close() }

    @Test fun completeBackupRestoresLinksAndPaymentDedupAndIsIdempotent() = runBlocking {
        val dao = source.records()
        dao.save(DailyRecord("food", "FOOD", "2026-01-01", "12:00", "午餐"))
        dao.save(DailyRecord("expense", "EXPENSE", "2026-01-01", "12:00", "食品酒水", value=1200))
        dao.putFoodEntry(FoodEntry("food", expenseId="expense", ownsExpense=true))
        dao.addCategory(CustomCategory("阅读"))
        dao.saveSubscription(Subscription("sub", "会员", 2000, "MONTH", "2026-01-01"))
        dao.confirmSubscription("sub", "2026-01-01", 2000, "2026-01-01")
        dao.capturePayment(PendingPayment("p", 500, "商户", "微信", "2026-01-01", "12:00"), listOf("alias"), emptyList())
        dao.confirm("p")
        val data = Backup.parse(Backup.export(context, dao))
        Backup.restore(context, target.records(), data)
        val repeated = Backup.restore(context, target.records(), data)
        assertEquals(0, repeated.added)
        assertEquals(dao.allRecords().toSet(), target.records().allRecords().toSet())
        assertEquals("expense", target.records().foodEntry("food")?.expenseId)
        assertEquals(1, target.records().allSubscriptionPayments().size)
        assertEquals("confirmed", target.records().payment("p")?.status)
        assertEquals("p", target.records().identityPayment("alias"))
    }

    @Test fun existingLocalRecordWinsWithoutDuplicatingOrRelinking() = runBlocking {
        source.records().save(DailyRecord("same", "NOTE", "2026-01-01", "12:00", "备份内容"))
        target.records().save(DailyRecord("same", "NOTE", "2026-01-01", "12:00", "本机内容"))
        val result = Backup.restore(context, target.records(), Backup.parse(Backup.export(context, source.records())))
        assertEquals(0, result.added)
        assertEquals("本机内容", target.records().record("same")?.title)
    }

    @Test fun legacyVersionOneAndThreeAreReadable() = runBlocking {
        source.records().save(DailyRecord("old", "NOTE", "2026-01-01", "12:00", "旧记录"))
        val json = JSONObject(Backup.export(context, source.records()))
        json.put("version", 1).remove("payments")
        json.remove("identities"); json.remove("foodEntries"); json.remove("subscriptions"); json.remove("subscriptionPayments")
        Backup.restore(context, target.records(), Backup.parse(json.toString()))
        assertEquals("旧记录", target.records().record("old")?.title)
        assertEquals(3, Backup.parse(json.put("version", 3).toString()).version)
    }

    @Test fun futureVersionBadDatesAndBrokenReferencesAreRejectedBeforeWriting() = runBlocking {
        val json = JSONObject(Backup.export(context, source.records()))
        assertTrue(runCatching { Backup.parse(json.put("version", 999).toString()) }.isFailure)
        source.records().save(DailyRecord("x", "FOOD", "2026-01-01", "12:00", "午餐"))
        source.records().putFoodEntry(FoodEntry("x", expenseId="missing", ownsExpense=true))
        assertTrue(runCatching { Backup.parse(Backup.export(context, source.records())) }.isFailure)
        assertTrue(target.records().allRecords().isEmpty())
    }

    @Test fun invalidPhotoRollsBackAllImportedRows() = runBlocking {
        source.records().save(DailyRecord("photo", "FOOD", "2026-01-01", "12:00", "午餐"))
        source.records().putFoodEntry(FoodEntry("photo"))
        val json = JSONObject(Backup.export(context, source.records()))
        json.getJSONArray("foodEntries").getJSONObject(0).put("photoBase64", "bm90LWFuLWltYWdl")
        assertTrue(runCatching { Backup.restore(context, target.records(), Backup.parse(json.toString())) }.isFailure)
        assertTrue(target.records().allRecords().isEmpty())
        assertTrue(target.records().allFoodEntries().isEmpty())
    }

    @Test fun realPhotoRestoresIntoPrivateStorageAndExportsAgain() = runBlocking {
        val bitmap=android.graphics.Bitmap.createBitmap(16,16,android.graphics.Bitmap.Config.ARGB_8888)
        val bytes=java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,bytes);bitmap.recycle()
        source.records().save(DailyRecord("picture","FOOD","2026-01-01","12:00","午餐"))
        source.records().putFoodEntry(FoodEntry("picture"))
        val json=JSONObject(Backup.export(context,source.records()))
        json.getJSONArray("foodEntries").getJSONObject(0).put("photoBase64",android.util.Base64.encodeToString(bytes.toByteArray(),android.util.Base64.NO_WRAP))
        try {
            Backup.restore(context,target.records(),Backup.parse(json.toString()))
            val entry=target.records().foodEntry("picture")!!
            assertNotNull(FoodPhotos.file(context,entry.photoPath))
            assertTrue(JSONObject(Backup.export(context,target.records())).getJSONArray("foodEntries").getJSONObject(0).getString("photoBase64").isNotEmpty())
            assertEquals(0,Backup.restore(context,target.records(),Backup.parse(json.toString())).added)
        } finally { target.records().foodEntry("picture")?.photoPath?.let { java.io.File(it).delete() } }
    }

    @Test fun localExpenseLinkConflictDoesNotStealAssociation() = runBlocking {
        val expense=DailyRecord("e","EXPENSE","2026-01-01","12:00","食品酒水",value=1000,createdAt=1)
        source.records().save(expense)
        source.records().save(DailyRecord("backupFood","FOOD","2026-01-01","12:00","午餐"))
        source.records().putFoodEntry(FoodEntry("backupFood",expenseId="e"))
        target.records().save(expense)
        target.records().save(DailyRecord("localFood","FOOD","2026-01-01","12:00","早餐"))
        target.records().putFoodEntry(FoodEntry("localFood",expenseId="e"))
        val result=Backup.restore(context,target.records(),Backup.parse(Backup.export(context,source.records())))
        assertEquals("localFood",target.records().foodForExpense("e")?.foodId)
        assertTrue(result.conflicts>0)
        assertNotNull(target.records().record("backupFood"))
        assertNull(target.records().foodEntry("backupFood")?.expenseId)
    }

    @Test fun sameTransactionWithDifferentPaymentIdDoesNotDuplicateExpense() = runBlocking {
        val a=PendingPayment("a",1200,"店铺","微信","2026-01-01","12:00")
        target.records().capturePayment(a,listOf("transaction"),emptyList());target.records().confirm("a")
        source.records().capturePayment(a.copy(id="b"),listOf("transaction","new-alias"),emptyList());source.records().confirm("b")
        Backup.restore(context,target.records(),Backup.parse(Backup.export(context,source.records())))
        assertEquals(1,target.records().allRecords().size)
        assertNull(target.records().payment("b"))
        assertEquals("a",target.records().identityPayment("new-alias"))
    }

    @Test fun sameSubscriptionPeriodDoesNotImportSecondExpense() = runBlocking {
        val s=Subscription("same-sub","会员",1000,"MONTH","2026-01-01")
        source.records().saveSubscription(s);target.records().saveSubscription(s)
        source.records().confirmSubscription(s.id,"2026-01-01",1000,"2026-01-01")
        target.records().save(DailyRecord("local-pay","EXPENSE","2026-01-01","12:00","其他",value=1000))
        target.records().confirmSubscription(s.id,"2026-01-01",1000,"2026-01-01","local-pay")
        Backup.restore(context,target.records(),Backup.parse(Backup.export(context,source.records())))
        assertEquals(listOf("local-pay"),target.records().allRecords().map{it.id})
    }

    @Test fun editedLocalPaymentPreservesFieldsButReceivesMissingIdentity() = runBlocking {
        val p=PendingPayment("same",1000,"待补充商户","微信","2026-01-01","12:00")
        source.records().capturePayment(p,listOf("transaction"),emptyList())
        target.records().addPayment(p.copy(merchant="本机修改"));target.records().confirm(p.id)
        Backup.restore(context,target.records(),Backup.parse(Backup.export(context,source.records())))
        assertEquals("本机修改",target.records().payment(p.id)?.merchant)
        assertEquals("same",target.records().identityPayment("transaction"))
        assertFalse(target.records().capturePayment(p.copy(id="new"),listOf("transaction"),emptyList()))
    }

    @Test fun wrongJsonTypesAndOverflowVersionAreRejected() = runBlocking {
        val text=Backup.export(context,source.records())
        assertTrue(runCatching{Backup.parse(JSONObject(text).put("categories",JSONObject()).toString())}.isFailure)
        assertTrue(runCatching{Backup.parse(JSONObject(text).put("version","4").toString())}.isFailure)
        assertTrue(runCatching{Backup.parse(JSONObject(text).put("version",4294967300L).toString())}.isFailure)
        source.records().saveSubscription(Subscription("bad","会员",100,"MONTH","2026-01-01"))
        val bad=JSONObject(Backup.export(context,source.records()))
        bad.getJSONArray("subscriptions").getJSONObject(0).put("active",JSONObject())
        assertTrue(runCatching{Backup.parse(bad.toString())}.isFailure)
    }

    @Test fun invalidPhotoOnConflictingRecordStillRejectsWholeImport() = runBlocking {
        val f=DailyRecord("existing","FOOD","2026-01-01","12:00","午餐")
        source.records().save(f);source.records().putFoodEntry(FoodEntry(f.id));target.records().save(f)
        source.records().save(DailyRecord("new","NOTE","2026-01-01","12:00","新记录"))
        val bad=JSONObject(Backup.export(context,source.records()))
        bad.getJSONArray("foodEntries").getJSONObject(0).put("photoBase64","bm90LWFuLWltYWdl")
        assertTrue(runCatching{Backup.restore(context,target.records(),Backup.parse(bad.toString()))}.isFailure)
        assertNull(target.records().record("new"))
    }

    @Test fun longPreviouslyValidNotesRoundTrip() = runBlocking {
        source.records().save(DailyRecord("long","NOTE","2026-01-01","12:00","标题".repeat(700),"内容".repeat(11_000)))
        Backup.restore(context,target.records(),Backup.parse(Backup.export(context,source.records())))
        assertEquals(source.records().record("long"),target.records().record("long"))
    }

    @Test fun ignoredLocalPaymentDoesNotRegainBackupExpense() = runBlocking {
        val p=PendingPayment("ignored",1000,"店铺","微信","2026-01-01","12:00")
        source.records().addPayment(p);source.records().confirm(p.id)
        target.records().addPayment(p.copy(status="ignored"))
        Backup.restore(context,target.records(),Backup.parse(Backup.export(context,source.records())))
        assertEquals("ignored",target.records().payment(p.id)?.status)
        assertNull(target.records().record("payment-${p.id}"))
    }

    @Test fun legacyBackupRespectsLocallyIgnoredPayment() = runBlocking {
        val p=PendingPayment("old-ignored",1000,"店铺","微信","2026-01-01","12:00")
        source.records().addPayment(p);source.records().confirm(p.id)
        target.records().addPayment(p.copy(status="ignored"))
        val json=JSONObject(Backup.export(context,source.records())).put("version",3)
        json.remove("payments");json.remove("identities")
        Backup.restore(context,target.records(),Backup.parse(json.toString()))
        assertNull(target.records().record("payment-${p.id}"))
    }

    @Test fun renamedLocalSubscriptionRestoresPaidPeriodWithoutDuplicateCharge() = runBlocking {
        val s=Subscription("renamed","旧名称",1000,"MONTH","2026-01-01")
        source.records().saveSubscription(s);source.records().confirmSubscription(s.id,"2026-01-01",1000,"2026-01-01")
        target.records().saveSubscription(s.copy(name="本机名称"))
        Backup.restore(context,target.records(),Backup.parse(Backup.export(context,source.records())))
        assertNotNull(target.records().subscriptionPayment(s.id,"2026-01-01"))
        assertEquals("本机名称",target.records().subscription(s.id)?.name)
        target.records().confirmSubscription(s.id,"2026-01-01",1000,"2026-01-01")
        assertEquals(1,target.records().allRecords().size)
    }

    @Test fun canonicalPaymentExpenseRetainsIncomingSubscriptionAssociation() = runBlocking {
        val p=PendingPayment("a",1000,"会员","微信","2026-01-01","12:00")
        target.records().capturePayment(p,listOf("same-transaction"),emptyList());target.records().confirm(p.id)
        source.records().capturePayment(p.copy(id="b"),listOf("same-transaction"),emptyList());source.records().confirm("b")
        source.records().saveSubscription(Subscription("linked","会员",1000,"MONTH","2026-01-01"))
        source.records().confirmSubscription("linked","2026-01-01",1000,"2026-01-01","payment-b")
        Backup.restore(context,target.records(),Backup.parse(Backup.export(context,source.records())))
        assertEquals("payment-a",target.records().subscriptionPayment("linked","2026-01-01")?.recordId)
        target.records().confirmSubscription("linked","2026-01-01",1000,"2026-01-01")
        assertEquals(1,target.records().allRecords().size)
    }

    @Test fun cleanupWaitsForPhotoPublicationAndDatabaseCommit() = runBlocking {
        val bitmap=android.graphics.Bitmap.createBitmap(16,16,android.graphics.Bitmap.Config.ARGB_8888)
        val bytes=java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,bytes);bitmap.recycle()
        source.records().save(DailyRecord("race","FOOD","2026-01-01","12:00","午餐"))
        source.records().putFoodEntry(FoodEntry("race"))
        val json=JSONObject(Backup.export(context,source.records()))
        json.getJSONArray("foodEntries").getJSONObject(0).put("photoBase64",android.util.Base64.encodeToString(bytes.toByteArray(),android.util.Base64.NO_WRAP))
        val published=CompletableDeferred<Unit>()
        val release=CompletableDeferred<Unit>()
        val dao=object:RecordDao by target.records() {
            override suspend fun mergeBackup(data:BackupData):RestoreResult {
                published.complete(Unit)
                release.await()
                return target.records().mergeBackup(data)
            }
        }
        val restore=async(Dispatchers.Default){Backup.restore(context,dao,Backup.parse(json.toString()))}
        try {
            withTimeout(5000){published.await()}
            val cleaning=CompletableDeferred<Unit>()
            val cleanup=async(Dispatchers.Default){cleaning.complete(Unit);FoodPhotos.cleanStored(context,target.records())}
            cleaning.await()
            try { assertNull(withTimeoutOrNull(100){cleanup.await();true}) }
            finally { release.complete(Unit) }
            withTimeout(5000){restore.await();cleanup.await()}
            assertNotNull(FoodPhotos.file(context,target.records().foodEntry("race")!!.photoPath))
        } finally {
            release.complete(Unit)
            restore.await()
            target.records().foodEntry("race")?.photoPath?.let{java.io.File(it).delete()}
        }
    }
}
