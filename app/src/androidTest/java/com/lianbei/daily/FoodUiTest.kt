package com.lianbei.daily
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*

class FoodUiTest {
 @get:Rule val compose=createComposeRule()
 private lateinit var db:DailyDatabase
 private val visible=mutableStateOf(true)
 private lateinit var restoration:androidx.compose.ui.test.junit4.StateRestorationTester
 @Before fun setup(){db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,DailyDatabase::class.java).build();restoration=androidx.compose.ui.test.junit4.StateRestorationTester(compose);restoration.setContent {LianbeiTheme {if(visible.value)DailyApp(db.records())}}}
 @After fun close(){compose.runOnIdle {visible.value=false};compose.waitForIdle();db.close()}
 @Test fun inFlightPhotoImportResumesAfterStateRestoration(){
  val instrumentation=InstrumentationRegistry.getInstrumentation()
  val monitor=object:android.app.Instrumentation.ActivityMonitor(){override fun onStartActivity(intent:android.content.Intent):android.app.Instrumentation.ActivityResult? {
   if(intent.action==android.content.Intent.ACTION_OPEN_DOCUMENT)return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK,android.content.Intent().setData(android.net.Uri.parse("content://com.lianbei.daily.test.slowphotos/image")))
   return null
  }}
  instrumentation.addMonitor(monitor)
  try {
   compose.onNodeWithTag("daily-list").performScrollToNode(hasTestTag("entry-FOOD"));compose.onNodeWithTag("entry-FOOD").performClick()
   compose.onNodeWithText("从相册选图").performScrollTo().performClick()
   compose.onNodeWithText("正在保存照片…").assertExists()
   restoration.emulateSavedInstanceStateRestore()
   compose.waitUntil(15000){compose.onAllNodesWithContentDescription("饮食照片，点击放大").fetchSemanticsNodes().isNotEmpty()}
  }finally {instrumentation.removeMonitor(monitor)}
 }
 @Test fun cameraPhotoCanBeSavedWithoutTextOrPrice(){
  val instrumentation=InstrumentationRegistry.getInstrumentation()
  val monitor=object:android.app.Instrumentation.ActivityMonitor(){override fun onStartActivity(intent:android.content.Intent):android.app.Instrumentation.ActivityResult? {
   if(intent.action==android.provider.MediaStore.ACTION_IMAGE_CAPTURE){
    val uri=intent.getParcelableExtra<android.net.Uri>(android.provider.MediaStore.EXTRA_OUTPUT)!!
    val bitmap=android.graphics.Bitmap.createBitmap(600,400,android.graphics.Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(android.graphics.Color.rgb(240,200,140))
    instrumentation.targetContext.contentResolver.openOutputStream(uri)!!.use {bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,90,it)};bitmap.recycle()
    return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK,null)
   };return null
  }}
  instrumentation.addMonitor(monitor)
  try {
   compose.onNodeWithTag("daily-list").performScrollToNode(hasTestTag("entry-FOOD"));compose.onNodeWithTag("entry-FOOD").performClick()
   compose.onNodeWithText("拍照").performScrollTo().performClick()
   compose.waitUntil(10000){compose.onAllNodesWithContentDescription("饮食照片，点击放大").fetchSemanticsNodes().isNotEmpty()}
   restoration.emulateSavedInstanceStateRestore()
   compose.waitUntil(10000){compose.onAllNodesWithContentDescription("饮食照片，点击放大").fetchSemanticsNodes().isNotEmpty()}
   compose.onNodeWithText("保存记录").performClick()
   compose.waitUntil(10000){compose.onAllNodesWithText("取消").fetchSemanticsNodes().isEmpty()}
   runBlocking {assertEquals(1,db.records().allRecords().size);assertTrue(java.io.File(db.records().allFoodEntries().single().photoPath).exists())}
  }finally {instrumentation.removeMonitor(monitor)}
 }
 @Test fun demoButtonsAddAndClearOnlyExamples(){
  runBlocking {db.records().save(DailyRecord("my-note","NOTE",java.time.LocalDate.now().toString(),"08:00","个人记录"))}
  compose.onNodeWithText("我的").performClick()
  compose.onNodeWithTag("daily-list").performScrollToNode(hasText("添加示例数据"))
  compose.onNodeWithText("添加示例数据").performClick()
  compose.waitUntil(10000){runBlocking {db.records().allSubscriptions().size==3}}
  compose.waitUntil(10000){compose.onAllNodesWithText("示例数据已添加").fetchSemanticsNodes().isEmpty()}
  compose.onNodeWithTag("daily-list").performScrollToNode(hasText("清除示例数据"))
  compose.onNodeWithText("清除示例数据").performClick()
  compose.onNodeWithText("确认清除").performClick()
  compose.waitUntil(10000){runBlocking {db.records().allRecords().size==1}}
  runBlocking {assertEquals("my-note",db.records().allRecords().single().id)}
 }
 @Test fun mealAndPriceSaveTogetherAndRemainVisible(){
  compose.onNodeWithTag("daily-list").performScrollToNode(hasTestTag("entry-FOOD"))
  compose.onNodeWithTag("entry-FOOD").performClick()
  compose.onNodeWithText("午餐").performClick()
  compose.onNodeWithText("吃了什么（可选）").performTextInput("牛肉面")
  compose.onNodeWithText("价格（元，可选）").performScrollTo().performTextInput("18")
  compose.onNodeWithText("保存记录").performClick()
  compose.waitUntil(10000){compose.onAllNodesWithText("取消").fetchSemanticsNodes().isEmpty()}
  runBlocking {val rows=db.records().observeAll().first();assertEquals(2,rows.size);assertEquals(1800L,rows.single{it.kind=="EXPENSE"}.value)}
  compose.onNodeWithTag("daily-list").performScrollToNode(hasText("牛肉面"))
  compose.onNodeWithText("牛肉面").assertExists()
  compose.onNodeWithText("回看").performClick()
  compose.onNodeWithText("饮食").performScrollTo().performClick()
  compose.onNodeWithTag("insights").performScrollToNode(hasText("餐费 ¥18.00 · 已计入消费"))
  compose.onNodeWithText("餐费 ¥18.00 · 已计入消费").assertIsDisplayed()
 }
}
