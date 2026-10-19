package com.lianbei.daily
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
class SubscriptionStorageTest {
 private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
 @Test fun deletedExpenseCanBeConfirmedAgain()=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(context,DailyDatabase::class.java).build()
  try {
   val dao=db.records();dao.saveSubscription(Subscription("repair","会员",100,"MONTH","2026-01-01"))
   dao.confirmSubscription("repair","2026-01-01",100,"2026-01-01")
   dao.delete(dao.observeAll().first().single())
   dao.confirmSubscription("repair","2026-01-01",200,"2026-01-02")
   assertEquals(200L,dao.observeAll().first().singleOrNull()?.value)
  }finally {db.close()}
 }
 @Test fun confirmationIsAtomicAndExistingExpenseCannotBeReused()=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(context,DailyDatabase::class.java).build()
  try {
   val dao=db.records(); val s=Subscription("s","会员",1200,"MONTH","2026-01-31")
   dao.saveSubscription(s)
   coroutineScope { repeat(5) {launch {dao.confirmSubscription("s","2026-01-31",1200,"2026-01-31")}} }
   assertEquals(1,dao.observeAll().first().size)
   val old=DailyRecord("old","EXPENSE","2026-02-28","12:00","会员","",1200)
   dao.save(old)
   dao.confirmSubscription("s","2026-02-28",1200,"2026-02-28","old")
   assertEquals(2,dao.observeAll().first().size)
   assertTrue(runCatching {dao.confirmSubscription("s","2026-03-31",1200,"2026-03-31","old")}.isFailure)
   dao.deleteSubscription("s")
   assertEquals(2,dao.observeAll().first().size)
  } finally {db.close()}
 }
 @Test fun migrationPreservesV2DataAndLinks()=runBlocking {
  val name="subscription-migration.db";context.deleteDatabase(name)
  context.openOrCreateDatabase(name,0,null).use { sql->
   sql.execSQL("CREATE TABLE records (id TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, date TEXT NOT NULL, time TEXT NOT NULL, title TEXT NOT NULL, detail TEXT NOT NULL, value INTEGER, extra TEXT NOT NULL, createdAt INTEGER NOT NULL)")
   sql.execSQL("CREATE TABLE categories (name TEXT NOT NULL PRIMARY KEY)")
   sql.execSQL("CREATE TABLE payments (id TEXT NOT NULL PRIMARY KEY, cents INTEGER NOT NULL, merchant TEXT NOT NULL, source TEXT NOT NULL, date TEXT NOT NULL, time TEXT NOT NULL, status TEXT NOT NULL)")
   sql.execSQL("INSERT INTO records VALUES ('old','NOTE','2026-10-14','12:00','保留记录','',NULL,'',1)")
   sql.execSQL("INSERT INTO categories VALUES ('旅行')")
   sql.execSQL("INSERT INTO payments VALUES ('p',100,'店铺','微信','2026-10-14','12:00','pending')")
   sql.version=2
  }
  val db=Room.databaseBuilder(context,DailyDatabase::class.java,name).addMigrations(DailyDatabase.MIGRATION_2_3, DailyDatabase.MIGRATION_3_4, DailyDatabase.MIGRATION_4_5).build()
  try {val dao=db.records();assertEquals("保留记录",dao.observeAll().first().single().title);assertEquals("旅行",dao.categories().first().single().name);assertEquals(1,dao.pending().first().size)
   dao.saveSubscription(Subscription("s","会员",100,"MONTH","2026-10-16",paymentUrl="https://example.com/pay"))
   assertEquals("https://example.com/pay",dao.subscriptions().first().single().paymentUrl)
  }finally {db.close();context.deleteDatabase(name)}
 }
}
