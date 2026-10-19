package com.lianbei.daily

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.Assert.*

class FoodStorageTest {
 private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
 private fun food(id:String="meal")=DailyRecord(id,"FOOD","2026-10-18","12:00","午餐","牛肉面")
 @Test fun replacingGeneratedExpenseWithExistingOneDoesNotDoubleCount()=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(context,DailyDatabase::class.java).build()
  try {val dao=db.records();dao.saveFood(food(),"",1800,null)
   dao.save(DailyRecord("real-payment","EXPENSE","2026-10-18","12:00","餐饮",value=1800))
   dao.saveFood(food(),"",null,"real-payment")
   assertEquals(1,dao.allRecords().count {it.kind=="EXPENSE"})
  }finally {db.close()}
 }
 @Test fun detachedDemoExpenseIsAlsoRemovedByDemoCleanup()=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(context,DailyDatabase::class.java).build()
  try {val dao=db.records();DemoData.add(dao,java.time.LocalDate.of(2026,10,18))
   val f=dao.allRecords().first {it.kind=="FOOD"};dao.saveFood(f,"",null,null)
   DemoData.clear(dao);assertTrue(dao.allRecords().isEmpty())
  }finally {db.close()}
 }
 @Test fun migrationPreservesV3SubscriptionsAndRecords()=runBlocking {
  val name="food-v3-migration.db";context.deleteDatabase(name)
  val old=Room.databaseBuilder(context,DailyDatabase::class.java,name).build()
  old.records().save(food());old.records().saveSubscription(Subscription("old-sub","旧订阅",1500,"MONTH","2026-01-01"));old.close()
  context.openOrCreateDatabase(name,0,null).use {it.execSQL("DROP TABLE food_entries");it.version=3}
  val upgraded=Room.databaseBuilder(context,DailyDatabase::class.java,name).addMigrations(DailyDatabase.MIGRATION_3_4, DailyDatabase.MIGRATION_4_5).build()
  try {assertEquals("牛肉面",upgraded.records().allRecords().single().detail);assertEquals("旧订阅",upgraded.records().allSubscriptions().single().name);assertTrue(upgraded.records().allFoodEntries().isEmpty())}
  finally {upgraded.close();context.deleteDatabase(name)}
 }
 @Test fun demoDataIsIdempotentAndCleanupPreservesPersonalRecords()=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(context,DailyDatabase::class.java).build()
  try {val dao=db.records();dao.save(food("personal"))
   DemoData.add(dao,java.time.LocalDate.of(2026,10,18))
   val count=dao.observeAll().first().size
   DemoData.add(dao,java.time.LocalDate.of(2026,10,18))
   assertEquals(count,dao.observeAll().first().size)
   assertTrue(dao.foodEntries().first().isNotEmpty())
   DemoData.clear(dao)
   assertEquals("personal",dao.observeAll().first().single().id)
   assertTrue(dao.subscriptions().first().isEmpty())
  }finally {db.close()}
 }
 @Test fun priceCreatesOneExpenseAndEditsUpdateIt()=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(context,DailyDatabase::class.java).build()
  try {val dao=db.records()
   dao.saveFood(food(),"",1800,null)
   dao.saveFood(food(),"",2000,null)
   val rows=dao.observeAll().first();assertEquals(2,rows.size)
   assertEquals(2000L,rows.single{it.kind=="EXPENSE"}.value)
   assertEquals(null,rows.single{it.kind=="FOOD"}.value)
   assertEquals("食品酒水",rows.single{it.kind=="EXPENSE"}.title)
   dao.delete(food());assertEquals(1,dao.observeAll().first().size)
   assertTrue(dao.foodEntries().first().isEmpty())
  }finally {db.close()}
 }
 @Test fun existingExpenseIsLinkedWithoutDuplicateAndCanBeUnlinked()=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(context,DailyDatabase::class.java).build()
  try {val dao=db.records();dao.save(DailyRecord("paid","EXPENSE","2026-10-18","12:00","餐饮",value=1800))
   dao.saveFood(food(),"",null,"paid")
   assertEquals(2,dao.observeAll().first().size)
   assertTrue(runCatching {dao.saveFood(food("second"),"",null,"paid")}.isFailure)
   dao.saveFood(food(),"",null,null)
   assertEquals(2,dao.observeAll().first().size)
   assertNull(dao.foodEntries().first().single().expenseId)
  }finally {db.close()}
 }
 @Test fun deletingExpenseClearsFoodAssociationAndBlankPriceStaysUnknown()=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(context,DailyDatabase::class.java).build()
  try {val dao=db.records();dao.saveFood(food(),"",null,null)
   assertEquals(1,dao.observeAll().first().size)
   dao.saveFood(food(),"",1800,null)
   dao.delete(dao.observeAll().first().single {it.kind=="EXPENSE"})
   assertNull(dao.foodEntries().first().single().expenseId)
  }finally {db.close()}
 }
}
