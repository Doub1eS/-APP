package com.lianbei.daily

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.time.LocalDate

class ExpenseCategoryUiTest {
    @get:Rule val compose=createComposeRule()
    private val visible=mutableStateOf(true)
    private lateinit var db:DailyDatabase
    @Before fun setup() {
        db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,DailyDatabase::class.java).build()
    }
    @After fun close() {compose.runOnIdle {visible.value=false};compose.waitForIdle();db.close()}
    @Test fun manualExpenseSavesParentAndChild() {
        var saved:DailyRecord?=null
        compose.setContent {LianbeiTheme {if(visible.value) RecordEditor(Kind.EXPENSE,LocalDate.now().toString(),null,emptyList(),{}) {r,_->saved=r}}}
        compose.onNodeWithTag("expense-parent-交通").performScrollTo().performClick()
        compose.onNodeWithTag("expense-child-地铁").performScrollTo().performClick()
        compose.onNodeWithText("金额（元）").performScrollTo().performTextInput("6")
        compose.onNodeWithText("保存记录").performClick()
        compose.runOnIdle {assertEquals("交通",saved?.title);assertEquals("地铁",saved?.extra);assertEquals(600L,saved?.value)}
    }
    @Test fun paymentConfirmationStoresSelectedChild() {
        runBlocking {db.records().addPayment(PendingPayment("category-test",1200,"早餐店","微信",LocalDate.now().toString(),"08:00"))}
        compose.setContent {LianbeiTheme {if(visible.value) PaymentInbox(db.records()) {}}}
        compose.onNodeWithTag("expense-parent-食品酒水").performScrollTo().performClick()
        compose.onNodeWithTag("expense-child-早餐").performScrollTo().performClick()
        compose.onNodeWithText("确认入账").performScrollTo().performClick()
        compose.waitUntil(15000) {runBlocking {db.records().allRecords().isNotEmpty()}}
        runBlocking {val r=db.records().allRecords().single();assertEquals("食品酒水",r.title);assertEquals("早餐",r.extra)}
    }
}
