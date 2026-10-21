package com.lianbei.daily

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth

data class Contributions(val pension: Long, val medical: Long, val unemployment: Long, val fund: Long) {
    val social: Long get() = Math.addExact(Math.addExact(pension, medical), unemployment)
    val total: Long get() = Math.addExact(social, fund)
}

data class PayrollCalculation(
    val record: PayrollRecord,
    val contributions: Contributions,
    val taxCents: Long,
    val estimatedNetCents: Long,
    val differenceCents: Long,
    val estimated: Boolean,
    val warnings: List<String>,
    val cumulativeTaxableCents: Long,
    val cumulativeTaxCents: Long
)

data class BudgetProgress(val budget: MonthlyBudget, val spentCents: Long) {
    val remainingCents: Long get() = Math.subtractExact(budget.limitCents, spentCents)
}

data class MonthlyFinance(
    val incomeCents: Long,
    val expenseCents: Long,
    val categoryExpenses: Map<String, Long>,
    val budgetProgress: List<BudgetProgress>
) {
    val balanceCents: Long get() = Math.subtractExact(incomeCents, expenseCents)
}

object FinanceRules {
    fun contributions(t: SalaryTerms): Contributions {
        FinanceValidation.terms(t)
        return Contributions(
            roundedRate(t.pensionBaseCents, t.pensionRateBp, 4),
            roundedRate(t.medicalBaseCents, t.medicalRateBp, 4),
            roundedRate(t.unemploymentBaseCents, t.unemploymentRateBp, 4),
            roundedRate(t.fundBaseCents, t.fundRateBp, 4)
        )
    }

    /** Resident cumulative wage tax, rounded HALF_UP to a cent. Negative bases incur no tax. */
    fun cumulativeTax(taxableCents: Long): Long {
        if (taxableCents <= 0) return 0
        val (rate, quickDeduction) = when {
            taxableCents <= 3_600_000 -> 3 to 0L
            taxableCents <= 14_400_000 -> 10 to 252_000L
            taxableCents <= 30_000_000 -> 20 to 1_692_000L
            taxableCents <= 42_000_000 -> 25 to 3_192_000L
            taxableCents <= 66_000_000 -> 30 to 5_292_000L
            taxableCents <= 96_000_000 -> 35 to 8_592_000L
            else -> 45 to 18_192_000L
        }
        return Math.subtractExact(roundedRate(taxableCents, rate, 2), quickDeduction).coerceAtLeast(0)
    }

    /**
     * Returns chronological calculations without mutating the saved actual amounts.
     * Identical primary keys are counted once; conflicting snapshots must be resolved by the caller.
     * An opening replaces all earlier cumulative history, never adds to it. Overlapping earlier
     * records are calculated separately and both segments remain explicitly estimated.
     */
    fun calculatePayrolls(rows: List<PayrollRecord>, openings: List<TaxOpening>): List<PayrollCalculation> {
        val payrolls = unique(rows, { it.month }, "工资月份").onEach(::validatePayroll).sortedBy { it.month }
        val openingByYear = unique(openings, { it.year }, "年度期初")
            .onEach(FinanceValidation::opening).associateBy { it.year }
        return payrolls.groupBy { FinanceValidation.month(it.month).year }.flatMap { (year, yearRows) ->
            val opening = openingByYear[year]
            if (opening == null) {
                calculateSegment(yearRows, 1, 0, 0, listOf("年度期初资料缺失，累计个税为估算"))
            } else {
                val (earlier, applicable) = yearRows.partition {
                    FinanceValidation.month(it.month).monthValue < opening.startMonth
                }
                val overlap = if (earlier.isNotEmpty()) listOf("期初累计数据与较早工资记录重叠，已分开计算，请核实期初") else emptyList()
                calculateSegment(earlier, 1, 0, 0,
                    overlap + "起算月之前的年度期初资料未确认，累计个税为估算") +
                    calculateSegment(applicable, opening.startMonth, opening.taxableCents, opening.paidTaxCents,
                        overlap + if (opening.confirmed) emptyList() else listOf("年度期初资料未确认，累计个税为估算"))
            }
        }
    }

    private fun calculateSegment(
        rows: List<PayrollRecord>, startMonth: Int, openingTaxable: Long, openingPaidTax: Long,
        initialWarnings: List<String>
    ): List<PayrollCalculation> {
        var taxable = openingTaxable
        var paidTax = openingPaidTax
        var expectedMonth = startMonth
        val warnings = initialWarnings.toMutableSet()
        return rows.map { row ->
            val month = FinanceValidation.month(row.month).monthValue
            if (month != expectedMonth) warnings += "${row.month}之前缺少工资月份记录，累计个税为估算"
            if (!row.terms.deductionsConfirmed) warnings += "${row.month}专项附加扣除未确认，后续累计个税为估算"
            expectedMonth = month + 1

            val contributions = contributions(row.terms)
            // Keep negative monthly and cumulative bases; only the resulting tax is floored at zero.
            val monthlyTaxable = Math.subtractExact(
                Math.subtractExact(Math.subtractExact(row.terms.grossCents, contributions.total), 500_000),
                row.terms.specialDeductionCents
            )
            taxable = Math.addExact(taxable, monthlyTaxable)
            val cumulativeTax = cumulativeTax(taxable)
            val tax = Math.subtractExact(cumulativeTax, paidTax).coerceAtLeast(0)
            paidTax = Math.addExact(paidTax, row.actualTaxCents ?: tax)
            val net = Math.subtractExact(
                Math.subtractExact(Math.subtractExact(row.terms.grossCents, contributions.total), tax),
                row.otherDeductionCents
            )
            PayrollCalculation(row, contributions, tax, net, Math.subtractExact(row.actualNetCents, net),
                warnings.isNotEmpty(), warnings.toList(), taxable, cumulativeTax)
        }
    }

    /** Only saved EXPENSE rows and actual payroll receipts enter the cash budget. */
    fun monthly(
        month: String, records: List<DailyRecord>, payrolls: List<PayrollRecord>, budgets: List<MonthlyBudget>
    ): MonthlyFinance {
        val target = FinanceValidation.month(month)
        val income = unique(payrolls, { it.month }, "工资月份").onEach(::validatePayroll)
            .filter { YearMonth.from(LocalDate.parse(it.date)) == target }
            .fold(0L) { sum, row -> Math.addExact(sum, row.actualNetCents) }
        val categories = linkedMapOf<String, Long>()
        var expense = 0L
        unique(records, { it.id }, "日常记录").filter { it.kind == "EXPENSE" }.forEach { row ->
            val date = LocalDate.parse(row.date)
            require(date.toString() == row.date) { "消费日期格式无效" }
            if (YearMonth.from(date) == target) {
                val cents = requireNotNull(row.value) { "消费金额缺失" }
                require(cents >= 0) { "消费金额不能为负数" }
                val category = ExpenseCategories.main(row.title.substringBefore(" / "))
                expense = Math.addExact(expense, cents)
                categories[category] = Math.addExact(categories[category] ?: 0, cents)
            }
        }
        val progress = unique(budgets, { it.month to it.category }, "月度预算")
            .onEach(FinanceValidation::budget).filter { it.month == month }.map { budget ->
                BudgetProgress(budget, if (budget.category.isEmpty()) expense else categories[budget.category] ?: 0)
            }
        return MonthlyFinance(income, expense, categories.toMap(), progress)
    }

    // Decimal arithmetic avoids overflow even for cumulativeTax(Long.MAX_VALUE).
    private fun roundedRate(cents: Long, rate: Int, decimalPlaces: Int): Long =
        BigDecimal.valueOf(cents).multiply(BigDecimal.valueOf(rate.toLong())).movePointLeft(decimalPlaces)
            .setScale(0, RoundingMode.HALF_UP).longValueExact()

    // Deliberately independent of LocalDate.now(): pure calculations remain reproducible.
    // The persistence boundary owns the prohibition on saving future payroll receipts.
    private fun validatePayroll(row: PayrollRecord) {
        val month = FinanceValidation.month(row.month)
        val date = LocalDate.parse(row.date)
        require(date.toString() == row.date && YearMonth.from(date) == month) { "到账日期需在发薪月份内" }
        FinanceValidation.terms(row.terms)
        FinanceValidation.money(row.actualNetCents)
        FinanceValidation.money(row.otherDeductionCents)
        row.actualTaxCents?.let(FinanceValidation::money)
        require(row.note.length <= 500) { "备注最多500字" }
    }

    private fun <T, K> unique(rows: List<T>, key: (T) -> K, label: String): List<T> {
        val result = linkedMapOf<K, T>()
        rows.forEach { row ->
            val id = key(row)
            require(!result.containsKey(id) || result[id] == row) { "$label 存在冲突的重复记录：$id" }
            result[id] = row
        }
        return result.values.toList()
    }
}
