package com.lianbei.daily

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.YearMonth

/** Rates use basis points (100 = 1%); all money is in cents. User-supplied Shenzhen template. */
data class SalaryTerms(
    val grossCents: Long = 1_700_000,
    val pensionBaseCents: Long = 1_330_000,
    val medicalBaseCents: Long = 1_330_000,
    val unemploymentBaseCents: Long = 1_330_000,
    val fundBaseCents: Long = 1_330_000,
    val pensionRateBp: Int = 800,
    val medicalRateBp: Int = 50,
    val unemploymentRateBp: Int = 20,
    val fundRateBp: Int = 500,
    val specialDeductionCents: Long = 0,
    val deductionsConfirmed: Boolean = false
)

@Entity(tableName = "salary_profile")
data class SalaryProfile(@PrimaryKey val id: Int = 1, @Embedded val terms: SalaryTerms = SalaryTerms())

@Entity(tableName = "payrolls")
data class PayrollRecord(
    @PrimaryKey val month: String,
    val date: String,
    val actualNetCents: Long,
    @Embedded val terms: SalaryTerms = SalaryTerms(),
    val actualTaxCents: Long? = null,
    val otherDeductionCents: Long = 0,
    val note: String = ""
)

@Entity(tableName = "tax_openings")
data class TaxOpening(
    @PrimaryKey val year: Int,
    val startMonth: Int = 1,
    val taxableCents: Long = 0,
    val paidTaxCents: Long = 0,
    val confirmed: Boolean = false
)

/** Empty category means the overall monthly budget. Zero is a valid explicit limit. */
@Entity(tableName = "monthly_budgets", primaryKeys = ["month", "category"])
data class MonthlyBudget(val month: String, val category: String = "", val limitCents: Long)

object FinanceValidation {
    const val MAX_CENTS = 10_000_000_000L
    fun money(value: Long) { require(value in 0..MAX_CENTS) { "金额需在0至1亿元之间" } }
    fun month(value: String): YearMonth {
        val result = YearMonth.parse(value)
        require(result.toString() == value && result.year in 2000..2100) { "月份格式应为YYYY-MM" }
        return result
    }
    fun terms(t: SalaryTerms) {
        listOf(t.grossCents,t.pensionBaseCents,t.medicalBaseCents,t.unemploymentBaseCents,t.fundBaseCents,t.specialDeductionCents).forEach(::money)
        require(t.grossCents > 0) { "税前工资需大于零" }
        require(listOf(t.pensionRateBp,t.medicalRateBp,t.unemploymentRateBp,t.fundRateBp).all { it in 0..10000 }) { "比例需在0%至100%之间" }
    }
    fun profile(p: SalaryProfile) { require(p.id == 1) { "工资模板标识无效" }; terms(p.terms) }
    fun payroll(p: PayrollRecord) {
        val month = month(p.month)
        val date = LocalDate.parse(p.date)
        require(date.toString() == p.date && YearMonth.from(date) == month) { "到账日期需在发薪月份内" }
        require(date <= LocalDate.now()) { "到账日期不能晚于今天" }
        terms(p.terms); money(p.actualNetCents); money(p.otherDeductionCents)
        p.actualTaxCents?.let(::money)
        require(p.note.length <= 500) { "备注最多500字" }
    }
    fun opening(o: TaxOpening) {
        require(o.year in 2000..2100 && o.startMonth in 1..12) { "计税年度或起算月份无效" }
        require(o.taxableCents in -MAX_CENTS..MAX_CENTS) { "累计税基超出范围" }
        money(o.paidTaxCents)
        require(o.startMonth != 1 || (o.taxableCents == 0L && o.paidTaxCents == 0L)) { "1月起算时此前累计金额应为0" }
    }
    fun budget(b: MonthlyBudget) {
        month(b.month); money(b.limitCents)
        require(b.category.isEmpty() || b.category in ExpenseCategories.groups) { "预算分类无效" }
    }
}

/** Inherited by RecordDao so existing backup transactions cover finance and daily records together. */
interface FinanceQueries {
    @Query("SELECT * FROM salary_profile WHERE id=1") fun salaryProfile(): Flow<SalaryProfile?>
    @Query("SELECT * FROM payrolls ORDER BY month DESC") fun payrolls(): Flow<List<PayrollRecord>>
    @Query("SELECT * FROM tax_openings ORDER BY year") fun taxOpenings(): Flow<List<TaxOpening>>
    @Query("SELECT * FROM monthly_budgets ORDER BY month, category") fun budgets(): Flow<List<MonthlyBudget>>
    @Query("SELECT * FROM salary_profile") suspend fun allSalaryProfiles(): List<SalaryProfile>
    @Query("SELECT * FROM payrolls") suspend fun allPayrolls(): List<PayrollRecord>
    @Query("SELECT * FROM tax_openings") suspend fun allTaxOpenings(): List<TaxOpening>
    @Query("SELECT * FROM monthly_budgets") suspend fun allBudgets(): List<MonthlyBudget>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putSalaryProfileRow(p: SalaryProfile)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putPayrollRow(p: PayrollRecord)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putTaxOpeningRow(o: TaxOpening)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putBudgetRow(b: MonthlyBudget)
    @Transaction suspend fun saveSalaryProfile(p: SalaryProfile) { FinanceValidation.profile(p); putSalaryProfileRow(p) }
    @Transaction suspend fun savePayroll(p: PayrollRecord) { FinanceValidation.payroll(p); putPayrollRow(p) }
    @Transaction suspend fun saveTaxOpening(o: TaxOpening) { FinanceValidation.opening(o); putTaxOpeningRow(o) }
    @Transaction suspend fun saveBudget(b: MonthlyBudget) { FinanceValidation.budget(b); putBudgetRow(b) }
    @Query("DELETE FROM payrolls WHERE month=:month") suspend fun deletePayroll(month: String)
    @Query("DELETE FROM monthly_budgets WHERE month=:month AND category=:category") suspend fun deleteBudget(month: String, category: String)
}
