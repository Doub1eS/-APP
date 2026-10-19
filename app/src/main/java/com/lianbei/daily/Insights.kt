package com.lianbei.daily

import java.time.LocalDate
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters

fun periodStart(date: LocalDate, month: Boolean): LocalDate = if (month) date.withDayOfMonth(1) else date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
fun periodEnd(date: LocalDate, month: Boolean): LocalDate = if (month) date.withDayOfMonth(date.lengthOfMonth()) else periodStart(date, false).plusDays(6)
data class DayValue(val date: LocalDate, val value: Long?, val count: Int)
data class GroupValue(val label: String, val value: Long)
data class InsightData(val records: List<DailyRecord>, val days: List<DayValue>, val groups: List<GroupValue>) {
    val total get() = days.sumOf { it.value ?: 0L }
    val recordedDays get() = days.count { it.count > 0 }
    val missingValues get() = records.count { it.value == null }
    val averageRecordedDay: Long? get() = days.mapNotNull { it.value }.takeIf { it.isNotEmpty() }?.let { it.sum() / it.size }
}
fun recordGroup(r: DailyRecord, kind: Kind): String = when (kind) {
    Kind.EXPENSE -> ExpenseCategories.main(r.title)
    Kind.NOTE -> r.extra.ifBlank { "未分类" }
    Kind.STATE -> r.title.takeIf { it in listOf("开心", "平静", "低落", "烦躁") } ?: "其他"
    Kind.FOOD -> r.title.takeIf { it in listOf("早餐", "午餐", "晚餐", "加餐") } ?: "其他"
    else -> r.title
}
fun insightData(all: List<DailyRecord>, kind: Kind, start: LocalDate, end: LocalDate): InsightData {
    val records = all.filter { it.kind == kind.name && it.date >= start.toString() && it.date <= end.toString() }
    val grouped = records.groupBy { it.date }
    val numeric = kind in listOf(Kind.EXPENSE, Kind.SLEEP, Kind.TRAINING)
    val days = generateSequence(start) { it.plusDays(1) }.takeWhile { it <= end }.map { date ->
        val rows = grouped[date.toString()].orEmpty()
        val values = rows.mapNotNull { it.value }
        DayValue(date, if (rows.isEmpty() || (numeric && values.isEmpty())) null else if (numeric) values.sum() else rows.size.toLong(), rows.size)
    }.toList()
    val groups = records.groupBy { recordGroup(it, kind) }.map { (label, rows) ->
        GroupValue(label, if (kind == Kind.EXPENSE) rows.sumOf { it.value ?: 0 } else rows.size.toLong())
    }.sortedByDescending { it.value }
    return InsightData(records, days, groups)
}
