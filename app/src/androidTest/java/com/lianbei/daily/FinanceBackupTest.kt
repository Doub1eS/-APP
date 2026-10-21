package com.lianbei.daily

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

class FinanceBackupTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var source: DailyDatabase
    private lateinit var target: DailyDatabase
    private val arrays = listOf("salaryProfiles", "payrolls", "taxOpenings", "budgets")
    // Deliberately synthetic boundary values, not anyone's salary history.
    private val terms = SalaryTerms(9_999_999_999L, 101, 202, 303, 404, 801, 51, 21, 501, 505, true)
    private val profile = SalaryProfile(terms = terms)
    private val payroll = PayrollRecord("2020-02", "2020-02-29", 987_654_321L,
        terms.copy(grossCents = 606, deductionsConfirmed = false), 0, 707, "synthetic fixture")
    private val opening = TaxOpening(2020, 2, -123, 456, true)
    private val budgets = listOf(MonthlyBudget("2020-02", "", 1001), MonthlyBudget("2020-02", "食品酒水", 0),
        MonthlyBudget("2020-03", "", 2002))

    @Before fun setup() {
        source = Room.inMemoryDatabaseBuilder(context, DailyDatabase::class.java).build()
        target = Room.inMemoryDatabaseBuilder(context, DailyDatabase::class.java).build()
    }
    @After fun close() { source.close(); target.close() }

    private suspend fun seed() {
        source.records().saveSalaryProfile(profile)
        source.records().savePayroll(payroll)
        source.records().saveTaxOpening(opening)
        budgets.forEach { source.records().saveBudget(it) }
    }
    private suspend fun fixture(): String {
        seed()
        return Backup.export(context, source.records())
    }
    private fun rejected(text: String) {
        assertTrue("Malformed finance backup must be rejected", runCatching { Backup.parse(text) }.isFailure)
    }

    @Test fun allTermsActualTaxNegativeOpeningAndZeroBudgetRoundTripExactly() = runBlocking {
        val text = fixture()
        val data = Backup.parse(text)
        assertEquals(5, data.version)
        assertEquals(listOf(profile), data.salaryProfiles)
        assertEquals(listOf(payroll), data.payrolls)
        assertEquals(listOf(opening), data.taxOpenings)
        assertEquals(budgets.toSet(), data.budgets.toSet())
        assertEquals(9_999_999_999L, JSONObject(text).getJSONArray("salaryProfiles").getJSONObject(0)
            .getJSONObject("terms").getLong("grossCents"))
        val result = Backup.restore(context, target.records(), data)
        assertEquals(6, result.added)
        assertEquals(1, result.salaryProfilesAdded)
        assertEquals(1, result.payrollsAdded)
        assertEquals(1, result.taxOpeningsAdded)
        assertEquals(3, result.budgetsAdded)
        assertEquals(0, result.conflicts)
        assertEquals(data, Backup.parse(Backup.export(context, target.records())))
        assertTrue(target.records().allRecords().isEmpty())
    }

    @Test fun nullAndPositiveActualTaxRemainDistinctFromZero() = runBlocking {
        seed()
        source.records().savePayroll(payroll.copy(month = "2020-03", date = "2020-03-01", actualTaxCents = null))
        source.records().savePayroll(payroll.copy(month = "2020-04", date = "2020-04-01", actualTaxCents = 123))
        Backup.restore(context, target.records(), Backup.parse(Backup.export(context, source.records())))
        assertEquals(mapOf("2020-02" to 0L, "2020-03" to null, "2020-04" to 123L),
            target.records().allPayrolls().associate { it.month to it.actualTaxCents })
    }

    @Test fun versionsOneThroughFourWithoutFinanceStillRestoreRecords() = runBlocking {
        for (version in 1..4) {
            val data = Backup.parse("""{"version":$version,"records":[{"id":"old-$version","kind":"NOTE","date":"2020-01-01","time":"12:00","title":"legacy"}]}""")
            assertTrue(data.salaryProfiles.isEmpty() && data.payrolls.isEmpty() && data.taxOpenings.isEmpty() && data.budgets.isEmpty())
            Backup.restore(context, target.records(), data)
        }
        assertEquals(4, target.records().allRecords().size)
        assertTrue(target.records().allPayrolls().isEmpty())
    }

    @Test fun v5RequiresEveryFinanceArrayAndRejectsWrongArrayTypes() = runBlocking {
        val text = fixture()
        for (key in arrays) {
            rejected(JSONObject(text).apply { remove(key) }.toString())
            for (bad in listOf(JSONObject.NULL, JSONObject(), "[]", 0, JSONArray().put(1))) {
                rejected(JSONObject(text).put(key, bad).toString())
            }
        }
        val empty = JSONObject("""{"version":5,"records":[],"salaryProfiles":[],"payrolls":[],"taxOpenings":[],"budgets":[]}""")
        assertTrue(Backup.parse(empty.toString()).payrolls.isEmpty())
    }

    @Test fun everyFinanceFieldIsRequiredAndTypesAreNotCoerced() = runBlocking {
        val text = fixture()
        for (array in arrays) {
            val row = JSONObject(text).getJSONArray(array).getJSONObject(0)
            val fields = row.keys().asSequence().toList()
            for (key in fields) {
                val missing = JSONObject(text)
                missing.getJSONArray(array).getJSONObject(0).remove(key)
                rejected(missing.toString())
                val bad = JSONObject(text)
                bad.getJSONArray(array).getJSONObject(0).put(key, JSONObject())
                rejected(bad.toString())
                if (key != "actualTaxCents") {
                    val nulled = JSONObject(text)
                    nulled.getJSONArray(array).getJSONObject(0).put(key, JSONObject.NULL)
                    rejected(nulled.toString())
                }
            }
        }
        val termFields = JSONObject(text).getJSONArray("salaryProfiles").getJSONObject(0)
            .getJSONObject("terms").keys().asSequence().toList()
        for (array in listOf("salaryProfiles", "payrolls")) for (key in termFields) {
            val missing = JSONObject(text)
            missing.getJSONArray(array).getJSONObject(0).getJSONObject("terms").remove(key)
            rejected(missing.toString())
            for (badValue in listOf(JSONObject.NULL, "1", 1.5, JSONObject())) {
                val bad = JSONObject(text)
                bad.getJSONArray(array).getJSONObject(0).getJSONObject("terms").put(key, badValue)
                rejected(bad.toString())
            }
        }
    }

    @Test fun strictIntegerBooleanUnknownFieldsAndDuplicateJsonKeysAreRejected() = runBlocking {
        val text = fixture()
        for (badValue in listOf("1", true, 1.5, 1e30, 4_294_967_297L)) {
            val bad = JSONObject(text)
            bad.getJSONArray("salaryProfiles").getJSONObject(0).put("id", badValue)
            rejected(bad.toString())
        }
        for (badValue in listOf("true", 1, JSONObject.NULL)) {
            val bad = JSONObject(text)
            bad.getJSONArray("taxOpenings").getJSONObject(0).put("confirmed", badValue)
            rejected(bad.toString())
        }
        // Keep the numeric spelling: JSONObject.toString normalizes 1.0 to 1.
        for (number in listOf("1.0", "1e0", "9223372036854775808")) {
            rejected(text.replaceFirst("\"actualNetCents\":987654321", "\"actualNetCents\":$number"))
        }
        for (array in arrays) {
            val bad = JSONObject(text)
            bad.getJSONArray(array).getJSONObject(0).put("unknownField", 1)
            rejected(bad.toString())
        }
        rejected(JSONObject(text).put("unknownCollection", JSONArray()).toString())
        val unknown = JSONObject(text)
        unknown.getJSONArray("payrolls").getJSONObject(0).getJSONObject("terms").put("unknownRate", 1)
        rejected(unknown.toString())
        rejected(text.replaceFirst("\"grossCents\":", "\"grossCents\":1,\"grossCents\":"))
        rejected(text.replaceFirst("\"version\":5", "\"version\":5,\"version\":5"))
        rejected(text.replaceFirst("\"version\":5", "\"version\":5,\"version\":4"))
        rejected(text.replaceFirst("\"budgets\":", "\"budgets\":[],\"budgets\":"))
    }

    @Test fun invalidIdsAmountsMonthsCategoriesDatesAndFuturePayrollAreRejected() = runBlocking {
        val text = fixture()
        val cases = listOf(
            Triple("salaryProfiles", "id", 2),
            Triple("payrolls", "actualNetCents", -1L),
            Triple("payrolls", "actualNetCents", FinanceValidation.MAX_CENTS + 1),
            Triple("payrolls", "actualTaxCents", -1L),
            Triple("payrolls", "otherDeductionCents", FinanceValidation.MAX_CENTS + 1),
            Triple("payrolls", "month", "2020-2"),
            Triple("payrolls", "date", "2020-02-30"),
            Triple("payrolls", "date", "2020-03-01"),
            Triple("payrolls", "date", "2020-2-29"),
            Triple("payrolls", "note", "x".repeat(501)),
            Triple("taxOpenings", "year", 1999),
            Triple("taxOpenings", "startMonth", 13),
            Triple("taxOpenings", "startMonth", 1),
            Triple("taxOpenings", "taxableCents", -FinanceValidation.MAX_CENTS - 1),
            Triple("taxOpenings", "paidTaxCents", -1),
            Triple("budgets", "category", "unknown"),
            Triple("budgets", "month", "2020-2"),
            Triple("budgets", "limitCents", -1),
            Triple("budgets", "limitCents", FinanceValidation.MAX_CENTS + 1)
        )
        for ((array, key, value) in cases) {
            val bad = JSONObject(text)
            bad.getJSONArray(array).getJSONObject(0).put(key, value)
            rejected(bad.toString())
        }
        for ((key, value) in listOf("grossCents" to 0L, "pensionRateBp" to 10001L,
            "fundRateBp" to 4_294_967_797L, "medicalBaseCents" to -1L)) {
            val bad = JSONObject(text)
            bad.getJSONArray("payrolls").getJSONObject(0).getJSONObject("terms").put(key, value)
            rejected(bad.toString())
        }
        val future = LocalDate.now().plusDays(1)
        val bad = JSONObject(text)
        bad.getJSONArray("payrolls").getJSONObject(0).put("month", YearMonth.from(future).toString()).put("date", future.toString())
        rejected(bad.toString())
    }

    @Test fun duplicateFinanceBusinessKeysAreRejected() = runBlocking {
        val text = fixture()
        for (key in arrays) {
            val bad = JSONObject(text)
            val rows = bad.getJSONArray(key)
            rows.put(JSONObject(rows.getJSONObject(0).toString()))
            rejected(bad.toString())
        }
    }

    @Test fun repeatedRestoreDoesNotDuplicateIncomeOrCountFinanceAsExpenses() = runBlocking {
        val data = Backup.parse(fixture())
        Backup.restore(context, target.records(), data)
        val repeated = Backup.restore(context, target.records(), data)
        assertEquals(RestoreResult(0, 6, 0), repeated)
        assertEquals(listOf(payroll), target.records().allPayrolls())
        assertEquals(budgets.toSet(), target.records().allBudgets().toSet())
        assertTrue(target.records().allRecords().isEmpty())
    }

    @Test fun conflictsPreserveAllLocalFinanceAndExistingExpense() = runBlocking {
        seed()
        val local = target.records()
        val localProfile = profile.copy(terms = SalaryTerms())
        val localPayroll = payroll.copy(actualNetCents = 42, actualTaxCents = 12)
        val localOpening = opening.copy(taxableCents = 1, confirmed = false)
        val localBudget = budgets[0].copy(limitCents = 0)
        local.saveSalaryProfile(localProfile)
        local.savePayroll(localPayroll)
        local.saveTaxOpening(localOpening)
        local.saveBudget(localBudget)
        val expense = DailyRecord("existing-expense", "EXPENSE", "2020-02-01", "12:00", "食品酒水", value = 100, createdAt = 1)
        local.save(expense)
        source.records().save(expense.copy(value = 999))
        val result = Backup.restore(context, local, Backup.parse(Backup.export(context, source.records())))
        assertEquals(2, result.added)
        assertEquals(5, result.skipped)
        assertEquals(5, result.conflicts)
        assertEquals(2, result.budgetsAdded)
        assertEquals(0, result.salaryProfilesAdded + result.payrollsAdded + result.taxOpeningsAdded)
        assertEquals(listOf(localProfile), local.allSalaryProfiles())
        assertEquals(listOf(localPayroll), local.allPayrolls())
        assertEquals(listOf(localOpening), local.allTaxOpenings())
        assertEquals(setOf(localBudget, budgets[1], budgets[2]), local.allBudgets().toSet())
        assertEquals(listOf(expense), local.allRecords())
    }

    @Test fun financeFailureRollsBackDailyRecordsAndEarlierFinanceWrites() = runBlocking {
        val valid = Backup.parse(fixture())
        val data = valid.copy(records = listOf(DailyRecord("rollback", "NOTE", "2020-01-01", "12:00", "synthetic")),
            budgets = valid.budgets + MonthlyBudget("2020-04", "", -1))
        assertTrue(runCatching { Backup.restore(context, target.records(), data) }.isFailure)
        assertTrue(target.records().allRecords().isEmpty())
        assertTrue(target.records().allSalaryProfiles().isEmpty())
        assertTrue(target.records().allPayrolls().isEmpty())
        assertTrue(target.records().allTaxOpenings().isEmpty())
        assertTrue(target.records().allBudgets().isEmpty())
    }
}
