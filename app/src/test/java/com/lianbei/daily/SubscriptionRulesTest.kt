package com.lianbei.daily
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
class SubscriptionRulesTest {
 private fun d(s:String)=LocalDate.parse(s)
 private fun sub(cycle:String="MONTH",start:String="2024-01-31")=Subscription("s","会员",12000,cycle,start)
 @Test fun monthEndKeepsAnchor() { assertEquals(listOf(d("2024-02-29"),d("2024-03-31")), SubscriptionRules.dates(sub(),d("2024-02-01"),d("2024-03-31"))) }
 @Test fun yearlyLeapAndNoAmortization() { val s=sub("YEAR","2024-02-29"); assertEquals(listOf(d("2025-02-28")),SubscriptionRules.dates(s,d("2025-01-01"),d("2025-12-31"))); assertTrue(SubscriptionRules.dates(s,d("2025-03-01"),d("2025-03-31")).isEmpty()) }
 @Test fun quarterAndWeek() { assertEquals(listOf(d("2024-04-30"),d("2024-07-31")),SubscriptionRules.dates(sub("QUARTER"),d("2024-02-01"),d("2024-08-01"))); assertEquals(d("2024-02-07"),SubscriptionRules.next(sub("WEEK"),d("2024-02-01"))) }
 @Test fun inactiveAndFutureStart() { assertNull(SubscriptionRules.next(sub().copy(active=false),d("2024-02-01"))); assertEquals(d("2024-01-31"),SubscriptionRules.next(sub(),d("2023-01-01"))) }
 @Test fun sevenDayWindowIncludesTodayAndExcludesDaySeven() { val s=sub("WEEK","2026-10-16"); assertEquals(listOf(d("2026-10-16")),SubscriptionRules.dates(s,d("2026-10-16"),d("2026-10-22")));assertTrue(SubscriptionRules.dates(s,d("2026-10-17"),d("2026-10-22")).isEmpty()) }
 @Test fun linksRequireWebAddress() { assertTrue(SubscriptionRules.validLink("https://example.com/pay?a=1")); assertTrue(SubscriptionRules.validLink("")); listOf("javascript:alert(1)","file:///a","https://","https://user:pass@example.com","hello").forEach { assertFalse(it,SubscriptionRules.validLink(it)) } }
}
