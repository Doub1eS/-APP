package com.lianbei.daily

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class SubscriptionReminderRulesTest {
    private val today = LocalDate.of(2026, 10, 20)
    private fun sub(id: String, days: Long, active: Boolean = true) =
        Subscription(id, id, 100, "MONTH", today.plusDays(days).toString(), active = active)

    @Test fun remindersExcludePaidInactivePastAndDayTwo() {
        val subscriptions = listOf(sub("today", 0), sub("tomorrow", 1), sub("later", 2),
            sub("past", -1), sub("paused", 0, false), sub("paid", 0))
        val payments = listOf(SubscriptionPayment("paid", "2026-10-20", "record"))
        assertEquals(listOf("today", "tomorrow"),
            SubscriptionReminderRules.due(subscriptions, payments, today).map { it.subscription.id })
    }

    @Test fun dedupIncludesSubscriptionDueAndCheckDay() {
        val s = sub("monthly", 1)
        val advance = SubscriptionReminderRules.due(listOf(s), emptyList(), today).single()
        val same = SubscriptionReminderRules.due(listOf(s), emptyList(), today).single()
        val dueDay = SubscriptionReminderRules.due(listOf(s), emptyList(), today.plusDays(1)).single()
        assertEquals(advance.key, same.key)
        assertNotEquals(advance.key, dueDay.key)
        assertNotEquals(advance.key, advance.copy(subscription = s.copy(id = "other")).key)
    }

    @Test fun sevenDayFilterIncludesTodayThroughDaySixAndKeepsPaidPlans() {
        val subscriptions = listOf(sub("today", 0), sub("six", 6), sub("seven", 7), sub("paused", 0, false))
        assertEquals(listOf("today", "six"),
            SubscriptionFilter.DUE_SOON.apply(subscriptions, today).map { it.id })
        assertEquals(listOf("paused"), SubscriptionFilter.INACTIVE.apply(subscriptions, today).map { it.id })
        assertEquals(3, SubscriptionFilter.ACTIVE.apply(subscriptions, today).size)
        assertEquals(4, SubscriptionFilter.ALL.apply(subscriptions, today).size)
    }

    @Test fun nextCheckIsTomorrowNineInLocalZoneEvenAcrossDst() {
        val now = ZonedDateTime.of(2026, 10, 31, 22, 0, 0, 0, ZoneId.of("America/New_York"))
        assertEquals("2026-11-01T09:00-05:00[America/New_York]",
            SubscriptionReminderRules.nextCheck(now).toString())
        assertEquals("2026-10-21T09:00+08:00[Asia/Shanghai]",
            SubscriptionReminderRules.nextCheck(ZonedDateTime.of(2026, 10, 20, 8, 0, 0, 0,
                ZoneId.of("Asia/Shanghai"))).toString())
    }
}
