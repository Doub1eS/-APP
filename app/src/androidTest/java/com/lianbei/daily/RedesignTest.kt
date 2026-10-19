package com.lianbei.daily

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.room.Room
import androidx.compose.runtime.mutableStateOf
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.time.LocalDate

class RedesignTest {
    @get:Rule val compose = createComposeRule()
    private val visible=mutableStateOf(true)
    private lateinit var db: DailyDatabase
    private var failPayments=false
    private val today=LocalDate.now()
    @Before fun setup() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        db=Room.inMemoryDatabaseBuilder(context,DailyDatabase::class.java).build()
        runBlocking {
            for(i in 0..6) {
                val date=today.minusDays(i.toLong()).toString()
                val rows=listOf(
                    DailyRecord("food$i","FOOD",date,"08:10","早餐","燕麦、鸡蛋和一杯牛奶"),
                    DailyRecord("sleep$i","SLEEP",date,"07:00","昨晚的睡眠",value=420L+i*10,extra="${java.time.LocalTime.of(7,0).minusMinutes(420L+i*10)}|07:00"),
                    DailyRecord("train$i","TRAINING",date,"18:00",if(i%2==0) "步行" else "力量训练","今天也动起来了",if(i==1) null else 30L+i*5),
                    DailyRecord("expense$i","EXPENSE",date,"12:30",if(i%2==0) "餐饮" else "交通", "日常消费",1234L+i*100),
                    DailyRecord("state$i","STATE",date,"20:00",if(i%2==0) "平静" else "开心",if(i%2==0) "精力一般" else "精力充沛"),
                    DailyRecord("note$i","NOTE",date,"21:00","留一点时间给自己","读完一章书，记住一段喜欢的话。",extra="读书")
                )
                rows.forEach {db.records().save(it)}
            }
            db.records().addCategory(CustomCategory("读书"))
            db.records().addPayment(PendingPayment("preview",2680,"街角咖啡","微信",today.toString(),"09:20"))
        }
        val dao=object:RecordDao by db.records() {
            override suspend fun confirm(id:String,category:String) {
                if(failPayments) throw IllegalStateException("Test write failure")
                db.records().confirm(id,category)
            }
        }
        compose.setContent { LianbeiTheme {if(visible.value) DailyApp(dao)} }
    }
    @After fun close() {compose.runOnIdle {visible.value=false}; compose.waitForIdle(); db.close()}
    private fun screenshot(name:String) {
        compose.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val resolver=instrumentation.targetContext.contentResolver
        val values=android.content.ContentValues().apply {
            val large=instrumentation.targetContext.resources.configuration.fontScale>1.1f
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,"${if(large) "large-" else ""}$name.png")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE,"image/png")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,"Pictures/Lianbei-ui-v5.1")
        }
        val uri=resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)!!
        instrumentation.uiAutomation.takeScreenshot().let { bitmap -> resolver.openOutputStream(uri)!!.use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle() }
    }
    @Test fun allScreensAndChartsUseRealRecords() {
        compose.waitUntil(10000) {compose.onAllNodesWithText("6 条日常").fetchSemanticsNodes().isNotEmpty()}
        screenshot("01-home")
        Kind.entries.forEach {kind ->
            compose.onNodeWithTag("daily-list").performScrollToNode(hasTestTag("entry-${kind.name}"))
            compose.onNodeWithTag("entry-${kind.name}").assertIsDisplayed().performClick()
            compose.onNodeWithText("保存记录").assertIsDisplayed()
            screenshot("editor-${kind.name.lowercase()}")
            compose.onNodeWithText("取消").performClick()
        }
        compose.onNodeWithText("回看").performClick()
        Kind.entries.forEach {kind ->
            compose.onNodeWithTag("insights").performScrollToIndex(0)
            compose.onNodeWithText(kind.label).performScrollTo().performClick()
            screenshot("insight-${kind.name.lowercase()}")
            compose.onNodeWithTag("insights").performScrollToNode(hasText(if(kind==Kind.SLEEP) "每日趋势" else if(kind==Kind.FOOD || kind==Kind.STATE || kind==Kind.NOTE) "记录日历" else "每日趋势"))
            screenshot("chart-${kind.name.lowercase()}")
        }
        compose.onNodeWithTag("insights").performScrollToIndex(0)
        compose.onNodeWithText("消费").performScrollTo().performClick()
        compose.onNodeWithTag("insights").performScrollToNode(hasText("消费去向"))
        compose.onNodeWithTag("group-食品酒水").performScrollTo().performClick()
        compose.onNodeWithTag("insights").performScrollToNode(hasText("清除筛选"))
        compose.onNodeWithText("清除筛选").assertIsDisplayed()
        screenshot("expense-breakdown")
        compose.onNodeWithText("我的").performClick()
        screenshot("my")
        compose.onNodeWithTag("daily-list").performScrollToNode(hasText("支付待确认 · 1"))
        compose.onNodeWithText("支付待确认 · 1").performScrollTo().performClick()
        screenshot("payments")
        compose.onNodeWithText("确认入账").performScrollTo().performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithText("待确认 0 笔").fetchSemanticsNodes().isNotEmpty()}
        runBlocking {assertEquals(2680L,db.records().observeAll().first().single {it.id=="payment-preview"}.value)}
    }
    @Test fun selectingDayScopesBreakdownAndClearsCategory() {
        compose.onNodeWithText("回看").performClick()
        compose.onNodeWithText("月").performClick()
        compose.onNodeWithTag("insights").performScrollToNode(hasTestTag("group-交通"))
        compose.onNodeWithTag("group-交通").performScrollTo().performClick()
        compose.onNodeWithTag("insights").performScrollToNode(hasText("每日趋势"))
        compose.onNodeWithContentDescription("$today，12.34 元").performScrollTo().performClick()
        compose.onNodeWithTag("insights").performScrollToNode(hasText("消费去向"))
        compose.onNodeWithTag("group-食品酒水").assertExists()
        compose.onNodeWithTag("group-交通").assertDoesNotExist()
        compose.onNodeWithText("¥12.34 · 100.0%").assertExists()
        screenshot("single-day-breakdown")
        compose.onNodeWithTag("insights").performScrollToNode(hasText("记录明细"))
        compose.onAllNodesWithText("¥12.34").onLast().assertExists()
        compose.onNodeWithText("清除筛选").performClick()
        compose.onNodeWithTag("insights").performScrollToNode(hasTestTag("group-交通"))
        compose.onNodeWithTag("group-交通").assertExists()
    }
    @Test fun foodHasNoDistributionAndSleepTrainingHaveOwnDetails() {
        compose.onNodeWithText("回看").performClick()
        compose.onNodeWithText("月").performClick()
        compose.onNodeWithText("饮食").performScrollTo().performClick()
        compose.onNodeWithTag("insights").performScrollToNode(hasText("记录明细"))
        compose.onNodeWithText("餐次分布").assertDoesNotExist()
        compose.onNodeWithTag("insights").performScrollToNode(hasText("燕麦、鸡蛋和一杯牛奶"))
        screenshot("food-detail")
        compose.onNodeWithTag("insights").performScrollToIndex(0)
        compose.onNodeWithText("睡眠").performScrollTo().performClick()
        compose.onNodeWithTag("insights").performScrollToNode(hasText("前一天入睡"))
        compose.onAllNodesWithText("起床").onFirst().assertExists()
        screenshot("sleep-detail")
        compose.onNodeWithTag("insights").performScrollToIndex(0)
        compose.onNodeWithText("训练").performScrollTo().performClick()
        compose.onNodeWithTag("insights").performScrollToNode(hasText("训练时长"))
        compose.onAllNodesWithText("训练时长").onFirst().assertExists()
        screenshot("training-detail")
    }
    @Test fun editsAndDeletesUpdateAnalytics() {
        compose.onNodeWithText("回看").performClick()
        compose.onNodeWithTag("insights").performScrollToNode(hasText("记录明细"))
        compose.onAllNodesWithText("编辑").onFirst().performScrollTo().performClick()
        compose.onNodeWithText("金额（元）").performTextReplacement("99.00")
        compose.onNodeWithText("保存记录").performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithText("取消").fetchSemanticsNodes().isEmpty()}
        compose.onNodeWithTag("insights").performScrollToNode(hasText("¥99.00"))
        compose.onNodeWithText("¥99.00").assertIsDisplayed()
        compose.waitUntil(10000) {compose.onAllNodesWithText("已记录").fetchSemanticsNodes().isEmpty()}
        compose.onAllNodesWithText("删除").onFirst().performScrollTo().assertIsDisplayed().performClick()
        compose.waitForIdle()
        screenshot("delete-dialog")
        compose.onNodeWithText("删除这条记录？").assertIsDisplayed()
        compose.onAllNodesWithText("删除").onLast().performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithText("删除这条记录？").fetchSemanticsNodes().isEmpty()}
        runBlocking { assertFalse(db.records().observeAll().first().any {it.value==9900L}) }
    }
    @Test fun periodNavigationAndCalendarFiltersWork() {
        compose.onNodeWithText("回看").performClick()
        compose.onNodeWithText("月").performClick()
        compose.onNodeWithText("上一期").performClick()
        compose.onNodeWithTag("insights").performScrollToNode(hasText("这一段日子，还没有消费记录"))
        compose.onNodeWithText("这一段日子，还没有消费记录").assertExists()
        screenshot("empty-period")
        compose.onNodeWithTag("insights").performScrollToIndex(0)
        compose.onNodeWithText("回到本期").performClick()
        compose.onNodeWithText("随手记").performScrollTo().performClick()
        compose.onNodeWithTag("insights").performScrollToNode(hasText("记录日历"))
        compose.onNodeWithContentDescription("$today，1条记录").performScrollTo().performClick()
        compose.onNodeWithTag("insights").performScrollToNode(hasText("清除筛选"))
        compose.onNodeWithText("清除筛选").assertIsDisplayed()
        compose.onNodeWithText(today.toString()).assertExists()
        screenshot("calendar-filter")
    }
    @Test fun failedPaymentIsVisibleWithoutOpeningSettings() {
        failPayments=true
        compose.waitUntil(10000) {compose.onAllNodesWithText("支付待确认 · 1").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("支付待确认 · 1").performClick()
        compose.onNodeWithText("确认入账").performScrollTo().performClick()
        compose.onNodeWithText("入账失败，请重试").assertIsDisplayed()
        compose.onNodeWithText("收起识别设置").assertDoesNotExist()
    }
    @Test fun untimedTrainingIsNotReportedAsMissing() {
        runBlocking {db.records().save(DailyRecord("train0","TRAINING",today.toString(),"18:00","步行"))}
        compose.onNodeWithText("回看").performClick()
        compose.onNodeWithText("训练").performScrollTo().performClick()
        compose.onNodeWithTag("insights").performScrollToNode(hasText("每日趋势"))
        compose.onNodeWithContentDescription("$today，已记录1次，未填写时长").performScrollTo().assertExists()
    }
    @Test fun invalidAmountRevealsItsFieldAndKeepsSaveVisible() {
        compose.onNodeWithTag("daily-list").performScrollToNode(hasTestTag("entry-EXPENSE"))
        compose.onNodeWithTag("entry-EXPENSE").assertIsDisplayed().performClick()
        compose.onNodeWithText("交通").performClick()
        compose.onNodeWithText("金额（元）").performTextInput("-5")
        compose.onNodeWithText("保存记录").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("金额（元）").assertIsFocused().assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Error))
        compose.onNodeWithText("保存记录").assertIsDisplayed()
        screenshot("validation")
    }
}
