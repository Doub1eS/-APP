package com.lianbei.daily

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class FinanceStorageTest {
    private lateinit var db: DailyDatabase
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, DailyDatabase::class.java).build() }
    @After fun close() { db.close() }

    @Test fun upgradeFromSixPreservesExistingRecordsAndCreatesEmptyFinanceTables() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="finance-migration-${java.util.UUID.randomUUID()}.db"
        val prior=Room.databaseBuilder(context,DailyDatabase::class.java,name).build()
        prior.records().save(DailyRecord("original","EXPENSE","2026-01-02","12:00","食品酒水",value=8900))
        prior.records().addPayment(PendingPayment("pending",1200,"商户","微信","2026-01-03","12:00"))
        prior.close()
        context.openOrCreateDatabase(name,0,null).use { sql ->
            listOf("salary_profile","payrolls","tax_openings","monthly_budgets").forEach { sql.execSQL("DROP TABLE $it") }
            sql.version=6
        }
        val upgraded=Room.databaseBuilder(context,DailyDatabase::class.java,name).addMigrations(DailyDatabase.MIGRATION_6_7).build()
        try {
            assertEquals(8900L,upgraded.records().record("original")?.value)
            assertEquals("pending",upgraded.records().payment("pending")?.status)
            assertTrue(upgraded.records().allPayrolls().isEmpty())
            assertTrue(upgraded.records().allSalaryProfiles().isEmpty())
            upgraded.records().savePayroll(PayrollRecord("2026-01","2026-01-15",1_400_000))
            upgraded.records().saveBudget(MonthlyBudget("2026-01","",100_000))
            upgraded.records().saveTaxOpening(TaxOpening(2026,confirmed=true))
            assertEquals(1,upgraded.records().allPayrolls().size)
            assertEquals(1,upgraded.records().allTaxOpenings().size)
        } finally { upgraded.close();context.deleteDatabase(name) }
    }

    @Test fun payrollUpsertKeepsOneIncomeAndProfileChangesDoNotRewriteHistory() = runBlocking {
        val dao = db.records()
        val old = PayrollRecord("2026-01", "2026-01-15", 1_400_000)
        dao.savePayroll(old)
        dao.savePayroll(old.copy(actualNetCents=1_410_000))
        dao.saveSalaryProfile(SalaryProfile(terms=SalaryTerms(grossCents=2_000_000)))
        assertEquals(1, dao.allPayrolls().size)
        assertEquals(1_410_000L, dao.allPayrolls().single().actualNetCents)
        assertEquals(1_700_000L, dao.allPayrolls().single().terms.grossCents)
        assertTrue(dao.allRecords().isEmpty())
        dao.deletePayroll("2026-01")
        assertTrue(dao.allPayrolls().isEmpty())
    }

    @Test fun budgetsAreIndependentByMonthAndCategoryAndZeroIsPersisted() = runBlocking {
        val dao = db.records()
        dao.saveBudget(MonthlyBudget("2026-01", "", 500_000))
        dao.saveBudget(MonthlyBudget("2026-01", "食品酒水", 0))
        dao.saveBudget(MonthlyBudget("2026-02", "", 600_000))
        assertEquals(3, dao.allBudgets().size)
        dao.deleteBudget("2026-01", "")
        assertEquals(setOf("食品酒水" to 0L, "" to 600_000L), dao.allBudgets().map { it.category to it.limitCents }.toSet())
    }

    @Test fun invalidWritesLeaveExistingFinancialDataIntact() = runBlocking {
        val dao = db.records()
        val row = PayrollRecord("2026-01", "2026-01-15", 1_400_000)
        dao.savePayroll(row)
        assertTrue(runCatching { dao.savePayroll(row.copy(date="2026-02-15")) }.isFailure)
        assertTrue(runCatching { dao.savePayroll(row.copy(actualNetCents=-1)) }.isFailure)
        assertTrue(runCatching { dao.saveSalaryProfile(SalaryProfile(terms=SalaryTerms(fundRateBp=10001))) }.isFailure)
        assertTrue(runCatching { dao.saveTaxOpening(TaxOpening(2026,1,1)) }.isFailure)
        assertTrue(runCatching { dao.saveBudget(MonthlyBudget("2026-01","未知分类",0)) }.isFailure)
        assertEquals(row, dao.allPayrolls().single())
    }
}
