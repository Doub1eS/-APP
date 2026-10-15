package com.lianbei.daily

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
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
    private lateinit var db: DailyDatabase
    @Before fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("ui-test.db")
        db = Room.databaseBuilder(context, DailyDatabase::class.java, "ui-test.db").build()
        compose.setContent { MaterialTheme { DailyApp(db.records()) } }
    }
    @After fun close() { db.close() }

    private fun save() {
        compose.onNodeWithText("保存记录").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("保存记录").fetchSemanticsNodes().isEmpty() }
    }
    @Test fun allSixKindsSaveAndCanBeReadFromDisk() {
        compose.onNodeWithText("饮食").performClick()
        compose.onNodeWithText("早餐").performClick()
        save()
        compose.onNodeWithText("睡眠").performClick()
        save()
        compose.onNodeWithText("训练").performClick()
        compose.onNodeWithText("步行").performClick()
        compose.onNodeWithText("时长（分钟，可选）").performTextInput("30")
        save()
        compose.onNodeWithText("消费").performClick()
        compose.onNodeWithText("交通").performClick()
        compose.onNodeWithText("金额（元）").performTextInput("12.34")
        save()
        compose.onNodeWithText("今天状态").performClick()
        compose.onNodeWithText("平静").performClick()
        compose.onNodeWithText("精力充沛").performClick()
        save()
        compose.onNodeWithText("随手记").performClick()
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
}
