package com.lianbei.daily

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.compose.runtime.mutableStateOf
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class DailyAppTest {
    @get:Rule val compose = createComposeRule()
    private val visible=mutableStateOf(true)
    private lateinit var db: DailyDatabase
    @Before fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("ui-test.db")
        db = Room.databaseBuilder(context, DailyDatabase::class.java, "ui-test.db").build()
        compose.setContent { MaterialTheme { if(visible.value) DailyApp(db.records()) } }
    }
    @After fun close() { compose.runOnIdle {visible.value=false}; compose.waitForIdle(); db.close() }

    private fun save() {
        compose.waitForIdle()
        compose.onNodeWithText("保存记录").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitUntil(10000) { compose.onAllNodesWithText("取消").fetchSemanticsNodes().isEmpty() }
    }
    @Test fun allSixKindsSaveAndCanBeReadFromDisk() {
        compose.onNodeWithTag("daily-list").performScrollToNode(hasTestTag("entry-FOOD"))
        compose.onNodeWithTag("entry-FOOD").assertIsDisplayed().performClick()
        compose.onNodeWithText("早餐").performClick()
        save()
        compose.onNodeWithTag("daily-list").performScrollToNode(hasTestTag("entry-SLEEP"))
        compose.onNodeWithTag("entry-SLEEP").assertIsDisplayed().performClick()
        save()
        compose.onNodeWithTag("daily-list").performScrollToNode(hasTestTag("entry-TRAINING"))
        compose.onNodeWithTag("entry-TRAINING").assertIsDisplayed().performClick()
        compose.onNodeWithText("步行").performClick()
        compose.onNodeWithText("时长（分钟，可选）").performTextInput("30")
        save()
        compose.onNodeWithTag("daily-list").performScrollToNode(hasTestTag("entry-EXPENSE"))
        compose.onNodeWithTag("entry-EXPENSE").assertIsDisplayed().performClick()
        compose.onNodeWithText("交通").performClick()
        compose.onNodeWithText("金额（元）").performTextInput("12.34")
        save()
        compose.onNodeWithTag("daily-list").performScrollToNode(hasTestTag("entry-STATE"))
        compose.onNodeWithTag("entry-STATE").assertIsDisplayed().performClick()
        compose.onNodeWithText("平静").performClick()
        compose.onNodeWithText("精力充沛").performClick()
        save()
        compose.onNodeWithTag("daily-list").performScrollToNode(hasTestTag("entry-NOTE"))
        compose.onNodeWithTag("entry-NOTE").assertIsDisplayed().performClick()
        compose.onNodeWithText("想记下什么").performTextInput("Read today")
        compose.onNodeWithText("自定义类别（可选，例如读书）").performTextInput("Reading")
        save()
        runBlocking {
            val rows = db.records().observeAll().first()
            assertEquals(6, rows.size)
            assertEquals(Kind.entries.map { it.name }.toSet(), rows.map { it.kind }.toSet())
            assertEquals(1234L, rows.single { it.kind == "EXPENSE" }.value)
            assertEquals(480L, rows.single { it.kind == "SLEEP" }.value)
            assertEquals("Reading", db.records().categories().first().single().name)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val reopened = Room.databaseBuilder(context, DailyDatabase::class.java, "ui-test.db").build()
            assertEquals(6, reopened.records().observeAll().first().size)
            reopened.close()
        }
    }

    @Test fun repositoryEditDeleteAndDatePreservation() = runBlocking {
        val original = DailyRecord("one", "NOTE", LocalDate.now().minusDays(1).toString(), "12:00", "Before")
        db.records().save(original)
        db.records().save(original.copy(title = "After"))
        val saved = db.records().observeAll().first().single()
        assertEquals("After", saved.title)
        assertEquals(original.date, saved.date)
        db.records().delete(saved)
        assertTrue(db.records().observeAll().first().isEmpty())
    }

    @Test fun pendingPaymentRequiresConfirmation() {
        runBlocking { db.records().addPayment(PendingPayment("ui-payment", 1234, "测试店铺", "微信", LocalDate.now().toString(), "12:00")) }
        compose.waitUntil(10000) { compose.onAllNodesWithText("支付待确认 · 1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("支付待确认 · 1").performClick()
        compose.onNodeWithText("确认入账").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("待确认 0 笔").fetchSemanticsNodes().isNotEmpty() }
        runBlocking {
            assertEquals(1234L, db.records().observeAll().first().single().value)
            assertEquals("confirmed", db.records().payment("ui-payment")?.status)
        }
    }
}
