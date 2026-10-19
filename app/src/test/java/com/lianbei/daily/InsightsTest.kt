package com.lianbei.daily

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class InsightsTest {
    private val day = LocalDate.of(2026, 10, 15)
    private fun row(kind: Kind, value: Long? = null, date: String = day.toString(), title: String = "其他", extra: String = "") =
        DailyRecord(java.util.UUID.randomUUID().toString(), kind.name, date, "12:00", title, value = value, extra = extra)

    @Test fun periodsHaveCalendarBoundaries() {
        assertEquals(LocalDate.of(2026,10,12), periodStart(day, false))
        assertEquals(LocalDate.of(2026,10,1), periodStart(day, true))
        assertEquals(LocalDate.of(2026,10,31), periodEnd(day,true))
    }
    @Test fun sleepUsesRecordedDayTotalsAndLeavesMissingDaysNull() {
        val data = insightData(listOf(row(Kind.SLEEP,420), row(Kind.SLEEP,60)), Kind.SLEEP, day.minusDays(1),day)
        assertNull(data.days.first().value)
        assertEquals(480L, data.days.last().value)
        assertEquals(480L, data.averageRecordedDay)
    }
    @Test fun untimedTrainingCountsWithoutInventingDuration() {
        val data = insightData(listOf(row(Kind.TRAINING),row(Kind.TRAINING,30)),Kind.TRAINING,day,day)
        assertEquals(2,data.records.size)
        assertEquals(1,data.missingValues)
        assertEquals(30L,data.total)
    }
    @Test fun expenseFiltersRangeAndKeepsExactCents() {
        val data = insightData(listOf(row(Kind.EXPENSE,1234,title="交通"),row(Kind.EXPENSE,1,title="交通"),row(Kind.EXPENSE,999,date="2026-09-30"),row(Kind.FOOD)),Kind.EXPENSE,periodStart(day,true),day)
        assertEquals(1235L,data.total)
        assertEquals(1235L,data.groups.single().value)
    }
    @Test fun customNotesAndUnknownMoodAreGroupedHonestly() {
        assertEquals("读书",insightData(listOf(row(Kind.NOTE,extra="读书")),Kind.NOTE,day,day).groups.single().label)
        assertEquals("其他",insightData(listOf(row(Kind.STATE,title="自由描述")),Kind.STATE,day,day).groups.single().label)
        assertNull(insightData(emptyList(),Kind.SLEEP,day,day).averageRecordedDay)
    }
}
