package com.lianbei.daily

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/** Run on an emulator only. Every test owns an in-memory database. */
class FinanceUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: DailyDatabase
    private lateinit var restoration: StateRestorationTester
    private val visible = mutableStateOf(true)
    private val month = YearMonth.now().toString()

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, DailyDatabase::class.java
        ).build()
        restoration = StateRestorationTester(compose)
    }

    private fun show() {
        restoration.setContent { LianbeiTheme { if (visible.value) FinanceScreen(db.records(), {}) } }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("finance-loaded").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @After fun close() {
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        db.close()
    }

    private fun scroll(tag: String) = compose.onNodeWithTag("finance-list")
        .performScrollToNode(hasTestTag(tag))

    private fun openPayroll() {
        scroll("payroll-open")
        compose.onNodeWithTag("payroll-open").performClick()
    }

    private fun awaitEditorClosed(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }
    }

    private fun awaitEditor(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun delayedProfileKeepsAllFinancialEditorsUnavailableUntilLoaded() {
        val delayed = MutableSharedFlow<SalaryProfile?>(replay = 1)
        val dao = object : RecordDao by db.records() {
            override fun salaryProfile(): Flow<SalaryProfile?> = delayed
        }
        restoration.setContent { LianbeiTheme { if (visible.value) FinanceScreen(dao, {}) } }
        compose.onNodeWithTag("finance-loading").assertExists()
        compose.onNodeWithTag("payroll-open").assertDoesNotExist()
        compose.onNodeWithTag("profile-open").assertDoesNotExist()
        compose.onNodeWithTag("opening-open").assertDoesNotExist()
        compose.onNodeWithTag("budget-open-total").assertDoesNotExist()
        runBlocking { delayed.emit(null) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("finance-loaded").fetchSemanticsNodes().isNotEmpty() }
        openPayroll()
        compose.onNodeWithTag("terms-gross").performScrollTo().assertTextContains("17000.00")
        compose.onNodeWithTag("terms-base-0").assertDoesNotExist()
    }

    @Test fun saveFailureRemainsInlineWithDraftAndBusyDisablesRepeatSubmission() {
        val busy = mutableStateOf(false)
        val error = mutableStateOf("")
        var submissions = 0
        restoration.setContent {
            LianbeiTheme {
                if (visible.value) PayrollEditor(month, null, SalaryTerms(), busy.value, error.value, {}, {
                    submissions++
                    busy.value = true
                })
            }
        }
        compose.onNodeWithTag("payroll-actual-net").performTextReplacement("+123.45")
        compose.onNodeWithTag("payroll-save").performClick()
        compose.onNodeWithTag("payroll-save").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, submissions)
            busy.value = false
            error.value = "保存失败，输入已保留，请重试。"
        }
        compose.onNodeWithTag("payroll-error").assertTextContains("保存失败，输入已保留，请重试。")
        compose.onNodeWithTag("payroll-actual-net").assertTextContains("+123.45")
        compose.onNodeWithTag("payroll-save").assertIsEnabled()
    }

    @Test fun signedActualAmountSurvivesRestorationAndEditingDoesNotDuplicateMonth() {
        show()
        openPayroll()
        compose.onNodeWithTag("payroll-actual-net").performTextReplacement("+123.45")
        restoration.emulateSavedInstanceStateRestore()
        awaitEditor("payroll-save")
        compose.onNodeWithTag("payroll-actual-net").assertTextContains("+123.45")
        compose.onNodeWithTag("payroll-save").performClick()
        awaitEditorClosed("payroll-save")
        runBlocking { assertEquals(12_345L, db.records().allPayrolls().single().actualNetCents) }
        openPayroll()
        compose.onNodeWithTag("payroll-actual-net").performTextReplacement("234.56")
        compose.onNodeWithTag("payroll-save").performClick()
        awaitEditorClosed("payroll-save")
        runBlocking {
            val saved = db.records().allPayrolls().single()
            assertEquals(month, saved.month)
            assertEquals(23_456L, saved.actualNetCents)
        }
    }

    @Test fun rejectsExtraDecimalsAndDatesOutsideSelectedMonthWithoutLosingInput() {
        show()
        openPayroll()
        compose.onNodeWithTag("payroll-actual-net").performTextReplacement("12.345")
        compose.onNodeWithTag("payroll-save").performClick()
        compose.onNodeWithTag("payroll-error").assertIsDisplayed()
        runBlocking { assertTrue(db.records().allPayrolls().isEmpty()) }
        compose.onNodeWithTag("payroll-actual-net").performTextReplacement("+123.45")
        compose.onNodeWithTag("payroll-date").performScrollTo()
            .performTextReplacement(YearMonth.now().minusMonths(1).atDay(1).toString())
        compose.onNodeWithTag("payroll-save").performClick()
        compose.onNodeWithTag("payroll-error").assertTextContains("到账日期需在发薪月份内", substring = true)
        compose.onNodeWithTag("payroll-date").performTextReplacement("not-a-date")
        restoration.emulateSavedInstanceStateRestore()
        awaitEditor("payroll-save")
        compose.onNodeWithTag("payroll-date").performScrollTo().assertTextContains("not-a-date")
        runBlocking { assertTrue(db.records().allPayrolls().isEmpty()) }
    }

    @Test fun zeroBudgetIsDistinctFromAbsentAndCategoriesAreIndependent() {
        show()
        scroll("budget-open-total")
        compose.onNodeWithTag("budget-open-total").performClick()
        compose.onNodeWithTag("budget-limit").performTextReplacement("0")
        compose.onNodeWithTag("budget-save").performClick()
        awaitEditorClosed("budget-save")
        val category = ExpenseCategories.groups.keys.first()
        scroll("budget-open-$category")
        compose.onNodeWithTag("budget-open-$category").performClick()
        compose.onNodeWithTag("budget-limit").performTextReplacement("120.50")
        compose.onNodeWithTag("budget-save").performClick()
        awaitEditorClosed("budget-save")
        runBlocking {
            val budgets = db.records().allBudgets()
            assertEquals(0L, budgets.single { it.category.isEmpty() }.limitCents)
            assertEquals(12_050L, budgets.single { it.category == category }.limitCents)
        }
        scroll("budget-open-total")
        compose.onNodeWithTag("budget-open-total").performClick()
        compose.onNodeWithTag("budget-limit").performTextClearance()
        compose.onNodeWithTag("budget-save").performClick()
        awaitEditorClosed("budget-save")
        runBlocking { assertEquals(category, db.records().allBudgets().single().category) }
    }

    @Test fun templateChangePreservesHistoricalSnapshotAndDeleteRequiresConfirmation() {
        val old = SalaryTerms(grossCents = 1_500_000, specialDeductionCents = 10_000, deductionsConfirmed = true)
        runBlocking { db.records().savePayroll(PayrollRecord(month, LocalDate.now().toString(), 1_200_000, old)) }
        show()
        scroll("profile-open")
        compose.onNodeWithTag("profile-open").performClick()
        compose.onNodeWithTag("terms-gross").performTextReplacement("18000")
        compose.onNodeWithTag("profile-save").performClick()
        awaitEditorClosed("profile-save")
        openPayroll()
        compose.onNodeWithTag("terms-gross").performScrollTo().assertTextContains("15000.00")
        compose.onNodeWithTag("payroll-cancel").performClick()
        runBlocking { assertEquals(old, db.records().allPayrolls().single().terms) }
        scroll("payroll-delete")
        compose.onNodeWithTag("payroll-delete").performClick()
        runBlocking { assertEquals(1, db.records().allPayrolls().size) }
        compose.onNodeWithTag("payroll-delete-confirm").performClick()
        compose.waitUntil(10_000) { runBlocking { db.records().allPayrolls().isEmpty() } }
    }

    @Test fun actualAndEstimatedTaxAreDisplayedSeparately() {
        val row = PayrollRecord(month, LocalDate.now().toString(), 1_200_000, actualTaxCents = 54_321)
        runBlocking { db.records().savePayroll(row) }
        show()
        scroll("payroll-actual-tax-value")
        compose.onNodeWithTag("payroll-actual-tax-value").assertTextEquals("¥543.21")
        scroll("payroll-estimated-tax-value")
        val calculated = FinanceRules.calculatePayrolls(listOf(row), emptyList()).single()
        compose.onNodeWithTag("payroll-estimated-tax-value").assertTextEquals(financeCash(calculated.taxCents))
        runBlocking { assertEquals(54_321L, db.records().allPayrolls().single().actualTaxCents) }
    }

    @Test fun historicalOpeningSupportsSignedTaxableAndExplicitConfirmation() {
        show()
        scroll("finance-month")
        compose.onNodeWithTag("finance-month").performClick()
        compose.onNodeWithTag("month-input").performTextReplacement("2024-07")
        compose.onNodeWithTag("month-save").performClick()
        scroll("opening-open")
        compose.onNodeWithTag("opening-open").performClick()
        compose.onNodeWithTag("opening-start-month").performTextReplacement("7")
        compose.onNodeWithTag("opening-taxable").performScrollTo().performTextReplacement("-123.45")
        compose.onNodeWithTag("opening-paid-tax").performScrollTo().performTextReplacement("15")
        compose.onNodeWithTag("opening-save").performClick()
        awaitEditorClosed("opening-save")
        runBlocking {
            val opening = db.records().allTaxOpenings().single()
            assertEquals(2024, opening.year)
            assertEquals(7, opening.startMonth)
            assertEquals(-12_345L, opening.taxableCents)
            assertFalse(opening.confirmed)
        }
        scroll("opening-open")
        compose.onNodeWithTag("opening-open").performClick()
        compose.onNodeWithTag("opening-confirmed").performScrollTo().performClick()
        compose.onNodeWithTag("opening-save").performClick()
        awaitEditorClosed("opening-save")
        runBlocking { assertTrue(db.records().allTaxOpenings().single().confirmed) }
    }
}
