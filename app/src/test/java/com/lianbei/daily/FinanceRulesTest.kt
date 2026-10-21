package com.lianbei.daily

import org.junit.Assert.*
import org.junit.Test

class FinanceRulesTest {
    private val confirmed = SalaryTerms(deductionsConfirmed = true)
    private fun row(month: String, terms: SalaryTerms = confirmed, net: Long = 1_480_000,
                    tax: Long? = null) = PayrollRecord(month, "$month-20", net, terms, tax)
    private fun opening(year: Int = 2025) = TaxOpening(year, confirmed = true)
    private fun expense(id: String, cents: Long?, category: String = "餐饮",
                        date: String = "2025-01-02", kind: String = "EXPENSE") =
        DailyRecord(id, kind, date, "12:00", category, value = cents)
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Invalid input must be rejected") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun contributionsRoundEachComponentToCents() {
        val c = FinanceRules.contributions(confirmed)
        assertEquals(Contributions(106400, 6650, 2660, 66500), c)
        assertEquals(115710L, c.social)
        assertEquals(182210L, c.total)
        val small = confirmed.copy(pensionBaseCents = 5, pensionRateBp = 1000,
            medicalBaseCents = 4, medicalRateBp = 1000,
            unemploymentBaseCents = 15, unemploymentRateBp = 1000,
            fundBaseCents = 14, fundRateBp = 1000)
        assertEquals(Contributions(1, 0, 2, 1), FinanceRules.contributions(small))
    }

    @Test fun monthlyTaxRoundingAndBracketTransitionMatchPayslips() {
        val rows = (1..5).map { row("2025-0$it") }
        val result = FinanceRules.calculatePayrolls(rows.reversed(), listOf(opening()))
        assertEquals(rows, result.map { it.record })
        assertEquals(listOf(30534L, 30533L, 30534L, 63515L, 101779L), result.map { it.taxCents })
        assertEquals(1_017_790L, result.first().cumulativeTaxableCents)
        assertEquals(256895L, result.last().cumulativeTaxCents)
        assertEquals(1_487_256L, result.first().estimatedNetCents)
        assertEquals(-7256L, result.first().differenceCents)
        assertTrue(result.all { !it.estimated && it.warnings.isEmpty() })
    }

    @Test fun sevenTaxBandsAndEveryBoundaryUseExactCents() {
        val cases = listOf(0L to 0L, 10000L to 300L,
            3_599_999L to 108000L, 3_600_000L to 108000L, 3_600_010L to 108001L,
            14_399_999L to 1188000L, 14_400_000L to 1188000L, 14_400_010L to 1188002L,
            29_999_999L to 4308000L, 30_000_000L to 4308000L, 30_000_010L to 4308003L,
            41_999_999L to 7308000L, 42_000_000L to 7308000L, 42_000_010L to 7308003L,
            65_999_999L to 14508000L, 66_000_000L to 14508000L, 66_000_010L to 14508004L,
            95_999_999L to 25008000L, 96_000_000L to 25008000L, 96_000_010L to 25008005L)
        cases.forEach { (base, expected) -> assertEquals("taxable=$base", expected, FinanceRules.cumulativeTax(base)) }
        assertEquals(0L, FinanceRules.cumulativeTax(Long.MIN_VALUE))
        assertEquals(4_150_517_416_566_457_113L, FinanceRules.cumulativeTax(Long.MAX_VALUE))
    }

    @Test fun negativeMonthlyTaxableIsCarriedAndNeverRefunded() {
        val rows = listOf(row("2025-01"), row("2025-02", confirmed.copy(specialDeductionCents = 2_000_000)), row("2025-03"))
        val r = FinanceRules.calculatePayrolls(rows, listOf(opening()))
        assertEquals(35580L, r[1].cumulativeTaxableCents)
        assertEquals(1067L, r[1].cumulativeTaxCents)
        assertEquals(0L, r[1].taxCents)
        assertEquals(1067L, r[2].taxCents)
        val belowZero = FinanceRules.calculatePayrolls(listOf(rows[1].copy(month = "2025-01", date = "2025-01-20"), row("2025-02")), listOf(opening()))
        assertEquals(-982210L, belowZero[0].cumulativeTaxableCents)
        assertEquals(1067L, belowZero[1].taxCents)
    }

    @Test fun actualTaxCalibratesLaterMonthsWithoutChangingActualRecords() {
        val rows = listOf(row("2025-01", tax = 40000), row("2025-02", tax = 0), row("2025-03"))
        val r = FinanceRules.calculatePayrolls(rows, listOf(opening()))
        assertEquals(listOf(30534L, 21067L, 51601L), r.map { it.taxCents })
        assertEquals(rows, r.map { it.record })
    }

    @Test fun editsRecalculateLaterMonthsAndOtherDeductionsOnlyAffectNet() {
        val jan = row("2025-01", tax = 30534)
        val feb = row("2025-02").copy(otherDeductionCents = 12345)
        val before = FinanceRules.calculatePayrolls(listOf(jan, feb), listOf(opening()))
        val after = FinanceRules.calculatePayrolls(listOf(jan.copy(terms = confirmed.copy(grossCents = 2_700_000)), feb), listOf(opening()))
        assertEquals(30533L, before[1].taxCents)
        assertEquals(60533L, after[1].taxCents)
        assertEquals(1_444_912L, after[1].estimatedNetCents)
        assertEquals(feb, after[1].record)
        assertEquals(30534L, after[0].record.actualTaxCents)
    }

    @Test fun absentAndUnconfirmedOpeningsNeverClaimExactness() {
        listOf(emptyList(), listOf(TaxOpening(2025))).forEach { openings ->
            val r = FinanceRules.calculatePayrolls(listOf(row("2025-01"), row("2025-02")), openings)
            assertTrue(r.all { it.estimated && it.warnings.isNotEmpty() })
        }
    }

    @Test fun missingMonthsAndUnconfirmedDeductionsPropagate() {
        val r = FinanceRules.calculatePayrolls(listOf(row("2025-01"), row("2025-03"), row("2025-04")), listOf(opening()))
        assertFalse(r[0].estimated)
        assertTrue(r.drop(1).all { it.estimated && it.warnings.isNotEmpty() })
        assertEquals(30533L, r[1].taxCents) // No fictional February allowance.
        val unconfirmed = FinanceRules.calculatePayrolls(listOf(row("2025-01", SalaryTerms()), row("2025-02")), listOf(opening()))
        assertTrue(unconfirmed.all { it.estimated && it.warnings.isNotEmpty() })
        assertTrue(FinanceRules.calculatePayrolls(listOf(row("2025-05")), listOf(opening())).single().estimated)
    }

    @Test fun yearsResetBothTaxAndMissingHistoryWarnings() {
        val r = FinanceRules.calculatePayrolls(listOf(row("2024-12"), row("2025-01")), listOf(opening()))
        assertTrue(r[0].estimated)
        assertFalse(r[1].estimated)
        assertEquals(30534L, r[1].taxCents)
    }

    @Test fun confirmedMidyearOpeningSeedsTaxAndActualPaid() {
        val o = TaxOpening(2025, 5, 4_071_160, 155116, true)
        val r = FinanceRules.calculatePayrolls(listOf(row("2025-05")), listOf(o)).single()
        assertEquals(101779L, r.taxCents)
        assertEquals(5_088_950L, r.cumulativeTaxableCents)
        assertFalse(r.estimated)
        assertTrue(FinanceRules.calculatePayrolls(listOf(row("2025-05")), listOf(o.copy(confirmed = false))).single().estimated)
        assertTrue(FinanceRules.calculatePayrolls(listOf(row("2025-06")), listOf(o)).single().estimated)
    }

    @Test fun overlappingHistoryIsSeparateFromOpeningAndExplicitlyEstimated() {
        val o = TaxOpening(2025, 5, 4_071_160, 155116, true)
        val r = FinanceRules.calculatePayrolls(listOf(row("2025-01", tax = 99999), row("2025-05"), row("2025-06")), listOf(o))
        assertEquals(30534L, r[0].taxCents)
        assertEquals(101779L, r[1].taxCents)
        assertEquals(101779L, r[2].taxCents)
        assertTrue(r.all { it.estimated && it.warnings.any { warning -> warning.contains("重叠") } })
    }

    @Test fun negativeOpeningTaxableIsPreservedAndOverpaidTaxNeverRefunds() {
        val r = FinanceRules.calculatePayrolls(listOf(row("2025-05")), listOf(TaxOpening(2025, 5, -2_000_000, 90000, true))).single()
        assertEquals(-982210L, r.cumulativeTaxableCents)
        assertEquals(0L, r.taxCents)
    }

    @Test fun monthlyCountsActualIncomeAndEachExpenseOnlyOnce() {
        val foodExpense = expense("expense", 1200, "食品酒水 / 午餐")
        val records = listOf(foodExpense, foodExpense, expense("food", 1200, kind = "FOOD"),
            expense("subscription", 2000, "娱乐"), expense("note", 10000, kind = "NOTE"),
            expense("next-month", 9000, date = "2025-02-01"))
        val payroll = row("2025-01", net = 1_000_000)
        val r = FinanceRules.monthly("2025-01", records, listOf(payroll, payroll, row("2025-02")), emptyList())
        assertEquals(1_000_000L, r.incomeCents)
        assertEquals(3200L, r.expenseCents)
        assertEquals(996800L, r.balanceCents)
        assertEquals(mapOf("食品酒水" to 1200L, "休闲娱乐" to 2000L), r.categoryExpenses)
        assertTrue(r.budgetProgress.isEmpty())
    }

    @Test fun independentTotalAndCategoryBudgetsIncludeZeroAndNegativeRemaining() {
        val total = MonthlyBudget("2025-01", limitCents = 5000)
        val food = MonthlyBudget("2025-01", "食品酒水", 0)
        val travel = MonthlyBudget("2025-01", "交通", 100)
        val r = FinanceRules.monthly("2025-01", listOf(expense("a", 1200), expense("b", 6000, "住房")), emptyList(),
            listOf(total, food, travel, MonthlyBudget("2025-02", limitCents = 0)))
        assertEquals(listOf(BudgetProgress(total, 7200), BudgetProgress(food, 1200), BudgetProgress(travel, 0)), r.budgetProgress)
        assertEquals(listOf(-2200L, -1200L, 100L), r.budgetProgress.map { it.remainingCents })
        assertEquals(-7200L, r.balanceCents)
    }

    @Test fun emptyMonthsDoNotInventBudgetsOrIncome() {
        val r = FinanceRules.monthly("2025-01", emptyList(), emptyList(), emptyList())
        assertEquals(MonthlyFinance(0, 0, emptyMap(), emptyList()), r)
        assertTrue(FinanceRules.calculatePayrolls(emptyList(), emptyList()).isEmpty())
    }

    @Test fun duplicatesAreCollapsedButConflictingKeysAreRejected() {
        val p = row("2025-01")
        assertEquals(1, FinanceRules.calculatePayrolls(listOf(p, p), listOf(opening(), opening())).size)
        rejected { FinanceRules.calculatePayrolls(listOf(p, p.copy(actualNetCents = 1)), emptyList()) }
        rejected { FinanceRules.calculatePayrolls(listOf(p), listOf(opening(), TaxOpening(2025))) }
        val b = MonthlyBudget("2025-01", limitCents = 0)
        assertEquals(1, FinanceRules.monthly("2025-01", emptyList(), emptyList(), listOf(b, b)).budgetProgress.size)
        rejected { FinanceRules.monthly("2025-01", emptyList(), emptyList(), listOf(b, b.copy(limitCents = 1))) }
        rejected { FinanceRules.monthly("2025-01", listOf(expense("x", 1), expense("x", 2)), emptyList(), emptyList()) }
    }

    @Test fun invalidAmountsRatesDatesAndOpeningsFailExplicitly() {
        listOf(confirmed.copy(grossCents = -1), confirmed.copy(pensionRateBp = -1),
            confirmed.copy(fundRateBp = 10001), confirmed.copy(medicalBaseCents = Long.MAX_VALUE)).forEach {
            rejected { FinanceRules.contributions(it) }
        }
        listOf(row("2025-01", net = -1), row("2025-01", tax = -1),
            row("2025-01").copy(otherDeductionCents = Long.MAX_VALUE),
            row("2025-01").copy(date = "2025-02-01")).forEach {
            rejected { FinanceRules.calculatePayrolls(listOf(it), emptyList()) }
        }
        listOf(TaxOpening(2025, 0), TaxOpening(2025, 13), TaxOpening(2025, taxableCents = 1),
            TaxOpening(2025, 5, Long.MIN_VALUE), TaxOpening(2025, 5, paidTaxCents = -1)).forEach {
            rejected { FinanceRules.calculatePayrolls(listOf(row("2025-01")), listOf(it)) }
        }
        rejected { FinanceRules.monthly("2025-01", listOf(expense("x", null)), emptyList(), emptyList()) }
        rejected { FinanceRules.monthly("2025-01", listOf(expense("x", -1)), emptyList(), emptyList()) }
        rejected { FinanceRules.monthly("2025-01", emptyList(), emptyList(), listOf(MonthlyBudget("2025-01", limitCents = -1))) }
        rejected { FinanceRules.monthly("2025-01", emptyList(), emptyList(), listOf(MonthlyBudget("2025-01", "无效", 1))) }
    }

    @Test fun largeValidPayrollAndExpensesDoNotWrap() {
        val max = FinanceValidation.MAX_CENTS
        val t = confirmed.copy(grossCents = max, pensionBaseCents = max, medicalBaseCents = max,
            unemploymentBaseCents = max, fundBaseCents = max,
            pensionRateBp = 10000, medicalRateBp = 10000, unemploymentRateBp = 10000, fundRateBp = 10000)
        val r = FinanceRules.calculatePayrolls(listOf(row("2025-01", t, max).copy(otherDeductionCents = max)), listOf(opening())).single()
        assertEquals(40_000_000_000L, r.contributions.total)
        assertEquals(-40_000_000_000L, r.estimatedNetCents)
        assertEquals(50_000_000_000L, r.differenceCents)
        try {
            FinanceRules.monthly("2025-01", listOf(expense("a", Long.MAX_VALUE), expense("b", 1)), emptyList(), emptyList())
            fail("Overflow must not wrap")
        } catch (_: ArithmeticException) { }
    }
}
