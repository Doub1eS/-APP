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
class SubscriptionUiTest {
 @get:Rule val compose=createComposeRule()
 private lateinit var db:DailyDatabase
 private val visible=mutableStateOf(true)
 private lateinit var restoration:androidx.compose.ui.test.junit4.StateRestorationTester
 @Before fun setup(){db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,DailyDatabase::class.java).build();restoration=androidx.compose.ui.test.junit4.StateRestorationTester(compose);restoration.setContent {LianbeiTheme {if(visible.value) DailyApp(db.records())}}}
 @After fun close(){compose.runOnIdle {visible.value=false};compose.waitForIdle();db.close()}
 @Test fun inactiveEditSurvivesStateRestoration(){
  runBlocking {db.records().saveSubscription(Subscription("paused","停用会员",100,"MONTH","2026-01-01",active=false))}
  compose.onNodeWithText("订阅").performClick()
  compose.onNodeWithTag("subscriptions-list").performScrollToNode(hasText("全部"))
  compose.onNodeWithText("全部").performClick()
  compose.onNodeWithTag("subscriptions-list").performScrollToNode(hasText("编辑"))
  compose.onNodeWithText("编辑").performClick()
  restoration.emulateSavedInstanceStateRestore()
  compose.onNodeWithText("保存订阅").performClick()
  compose.waitUntil(10000){compose.onAllNodesWithText("保存订阅").fetchSemanticsNodes().isEmpty()}
  runBlocking {assertFalse(db.records().subscriptions().first().single().active)}
 }
 @Test fun missingBrowserErrorIsVisibleAtScrolledCard(){
  runBlocking {
   repeat(8){db.records().saveSubscription(Subscription("filler-$it","A$it",100,"MONTH","2026-01-01"))}
   db.records().saveSubscription(Subscription("missing","网页会员",100,"MONTH","2026-01-01",paymentUrl="https://example.com"))
  }
  val instrumentation=InstrumentationRegistry.getInstrumentation()
  val monitor=object:android.app.Instrumentation.ActivityMonitor(){override fun onStartActivity(intent:android.content.Intent):android.app.Instrumentation.ActivityResult? {if(intent.action==android.content.Intent.ACTION_VIEW)throw android.content.ActivityNotFoundException();return null}}
  instrumentation.addMonitor(monitor)
  try {
   compose.onNodeWithText("订阅").performClick()
   compose.onNodeWithTag("subscriptions-list").performScrollToNode(hasText("去缴费"))
   compose.onNodeWithText("去缴费").performClick()
   compose.onNodeWithText("无法打开缴费链接，请检查网址或安装浏览器。").assertIsDisplayed()
  }finally {instrumentation.removeMonitor(monitor)}
 }
 private fun screenshot(name:String){
  compose.waitForIdle()
  val instrumentation=InstrumentationRegistry.getInstrumentation()
  val values=android.content.ContentValues().apply {put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,"$name.png");put(android.provider.MediaStore.Images.Media.MIME_TYPE,"image/png");put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,"Pictures/Lianbei-subscriptions")}
  val resolver=instrumentation.targetContext.contentResolver
  val uri=resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)!!
  resolver.openOutputStream(uri)!!.use {instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
 }
 @Test fun paymentLinkOpensExactUrlWithoutRecordingPayment(){
  val instrumentation=InstrumentationRegistry.getInstrumentation()
  val url="https://example.com/pay?plan=monthly"
  runBlocking {db.records().saveSubscription(Subscription("link","缴费测试",100,"MONTH",java.time.LocalDate.now().toString(),paymentUrl=url))}
  var captured:android.content.Intent?=null
  val monitor=object:android.app.Instrumentation.ActivityMonitor(){override fun onStartActivity(intent:android.content.Intent):android.app.Instrumentation.ActivityResult? {
   if(intent.action==android.content.Intent.ACTION_VIEW){captured=intent;return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED,null)}
   return null
  }}
  instrumentation.addMonitor(monitor)
  try {
   compose.onNodeWithText("订阅").performClick()
   compose.onNodeWithTag("subscriptions-list").performScrollToNode(hasText("去缴费"))
   compose.onNodeWithText("去缴费").performClick()
   compose.runOnIdle {assertEquals(url,captured?.dataString)}
   runBlocking {assertTrue(db.records().observeAll().first().isEmpty());assertTrue(db.records().subscriptionPayments().first().isEmpty())}
  } finally {instrumentation.removeMonitor(monitor)}
 }
 @Test fun confirmPauseResumeEditDeleteAndCharts(){
  val today=java.time.LocalDate.now().toString()
  runBlocking {db.records().saveSubscription(Subscription("music","音乐会员",1500,"MONTH",today,category="娱乐",paymentUrl="https://example.com/pay"))}
  compose.onNodeWithText("订阅").performClick()
  screenshot("overview")
  compose.onNodeWithTag("subscriptions-list").performScrollToNode(hasText("确认付款"))
  screenshot("card")
  compose.onNodeWithText("确认付款").performClick()
  screenshot("confirm")
  compose.onNodeWithText("确认并记账").performClick()
  compose.waitUntil(10000){runBlocking {db.records().observeAll().first().size==1}}
  compose.waitUntil(10000){compose.onAllNodesWithText("确认并记账").fetchSemanticsNodes().isEmpty()}
  compose.onNodeWithTag("subscriptions-list").performScrollToNode(hasText("编辑"))
  compose.onNodeWithText("编辑").performClick()
  compose.onNodeWithText("金额（元）").performTextReplacement("20")
  compose.onNodeWithText("保存订阅").performClick()
  compose.waitUntil(10000){runBlocking {db.records().subscriptions().first().single().cents==2000L}}
  compose.waitUntil(10000){compose.onAllNodesWithText("保存订阅").fetchSemanticsNodes().isEmpty()}
  compose.onNodeWithTag("subscriptions-list").performScrollToNode(hasTestTag("subscription-toggle-music"))
  compose.onNodeWithTag("subscription-toggle-music").performClick()
  compose.waitUntil(10000){runBlocking {!db.records().subscriptions().first().single().active}}
  compose.onNodeWithTag("subscriptions-list").performScrollToNode(hasText("全部"))
  compose.onNodeWithText("全部").performClick()
  compose.onNodeWithTag("subscriptions-list").performScrollToNode(hasText("恢复"))
  compose.onNodeWithText("恢复").performClick()
  compose.waitUntil(10000){runBlocking {db.records().subscriptions().first().single().active}}
  compose.onNodeWithTag("subscriptions-list").performScrollToNode(hasText("统计"))
  compose.onNodeWithText("统计").performClick()
  compose.onNodeWithTag("subscriptions-list").performScrollToNode(hasText("${java.time.LocalDate.now().year}年月度预计扣费"))
  screenshot("trends")
  compose.onNodeWithTag("subscriptions-list").performScrollToNode(hasText("${java.time.LocalDate.now().monthValue}月分类占比"))
  screenshot("categories")
  compose.onNodeWithTag("subscriptions-list").performScrollToNode(hasText("日历"))
  compose.onNodeWithText("日历").performClick()
  compose.onNodeWithTag("subscriptions-list").performScrollToNode(hasText("扣费日历"))
  screenshot("calendar")
  compose.onNodeWithTag("subscriptions-list").performScrollToNode(hasText("列表"))
  compose.onNodeWithText("列表").performClick()
  compose.onNodeWithTag("subscriptions-list").performScrollToNode(hasText("删除"))
  compose.onNodeWithText("删除").performClick()
  compose.onNodeWithText("删除订阅").performClick()
  compose.waitUntil(10000){runBlocking {db.records().subscriptions().first().isEmpty()}}
  runBlocking {assertEquals(1500L,db.records().observeAll().first().single().value)}
 }
 @Test fun addSubscriptionWithPaymentLinkAndViewCalendar(){
  compose.onNodeWithText("订阅").performClick()
  compose.onNodeWithText("添加订阅").performClick()
  compose.onNodeWithText("订阅名称").performTextInput("音乐会员")
  compose.onNodeWithText("金额（元）").performTextInput("15")
  compose.onNodeWithText("缴费链接（可选）").performScrollTo().performTextInput("https://example.com/pay")
  compose.onNodeWithText("保存订阅").performClick()
  compose.waitUntil(10000){runBlocking {db.records().subscriptions().first().isNotEmpty()}}
  compose.waitUntil(10000){compose.onAllNodesWithText("保存订阅").fetchSemanticsNodes().isEmpty()}
  compose.onNodeWithTag("subscriptions-list").performScrollToNode(hasText("去缴费"))
  compose.onNodeWithText("去缴费").assertIsDisplayed()
  runBlocking {assertEquals("https://example.com/pay",db.records().subscriptions().first().single().paymentUrl)}
  compose.onNodeWithTag("subscriptions-list").performScrollToNode(hasText("日历"))
  compose.onNodeWithText("日历").performClick()
  compose.onNodeWithTag("subscriptions-list").performScrollToNode(hasText("扣费日历"))
  compose.onNodeWithText("扣费日历").assertIsDisplayed()
 }
}
