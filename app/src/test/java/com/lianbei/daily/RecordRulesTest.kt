package com.lianbei.daily

import org.junit.Assert.*
import org.junit.Test

class RecordRulesTest {
    @Test fun moneyUsesExactCents() { assertEquals(1234L, RecordRules.cents("12.34")); assertEquals("12.34", RecordRules.money(1234)) }
    @Test fun rejectsInvalidAmounts() { listOf("", "-1", "0", "1.234", "abc", "999999999999999999999999").forEach { assertNull(RecordRules.cents(it)) } }
    @Test fun overnightSleep() { assertEquals(450L, RecordRules.sleepMinutes("23:30", "07:00")) }
    @Test fun daytimeSleep() { assertEquals(45L, RecordRules.sleepMinutes("13:00", "13:45")) }
    @Test fun rejectsInvalidSleep() { assertNull(RecordRules.sleepMinutes("25:00", "07:00")); assertNull(RecordRules.sleepMinutes("07:00", "07:00")) }
}
