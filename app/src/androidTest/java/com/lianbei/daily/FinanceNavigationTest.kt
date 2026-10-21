package com.lianbei.daily

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*

class FinanceNavigationTest {
    @get:Rule val compose=createComposeRule()
    private lateinit var db:DailyDatabase
    private val visible=mutableStateOf(true)
    @Before fun setup() {
        db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,DailyDatabase::class.java).build()
        compose.setContent { LianbeiTheme { if(visible.value) DailyApp(db.records()) } }
    }
    @After fun close() { compose.runOnIdle {visible.value=false};compose.waitForIdle();db.close() }
    @Test fun todayEntryOpensFinanceAndBottomNavigationLeavesIt() {
        compose.onNodeWithTag("daily-list").performScrollToNode(hasTestTag("open-finance"))
        compose.onNodeWithTag("open-finance").performClick()
        compose.onNodeWithTag("finance-screen").assertExists()
        compose.onNodeWithText("回看",useUnmergedTree=true).performClick()
        compose.onNodeWithTag("insights").assertExists()
        compose.onNodeWithTag("open-finance").performClick()
        compose.onNodeWithTag("finance-screen").assertExists()
        compose.onNodeWithText("我的",useUnmergedTree=true).performClick()
        compose.onNodeWithTag("daily-list").performScrollToNode(hasTestTag("open-finance"))
        compose.onNodeWithTag("open-finance").performClick()
        compose.onNodeWithTag("finance-screen").assertExists()
    }
}
