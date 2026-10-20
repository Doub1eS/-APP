package com.lianbei.daily

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.net.URI
import java.time.ZonedDateTime

@Entity(tableName="subscriptions")
data class Subscription(@PrimaryKey val id:String, val name:String, val cents:Long,
 val cycle:String, val startDate:String, val category:String="其他", val note:String="",
 val paymentUrl:String="", val color:Int=0, val active:Boolean=true)

@Entity(tableName="subscription_payments", primaryKeys=["subscriptionId","dueDate"], indices=[Index(value=["recordId"],unique=true)])
data class SubscriptionPayment(val subscriptionId:String,val dueDate:String,val recordId:String)

object SubscriptionRules {
 val cycles=linkedMapOf("WEEK" to "每周","MONTH" to "每月","QUARTER" to "每季度","YEAR" to "每年")
 fun validLink(text:String):Boolean = text.isBlank() || runCatching {
  val u=URI(text); u.scheme?.lowercase() in listOf("http","https") && !u.host.isNullOrBlank() && u.rawUserInfo==null
 }.getOrDefault(false)
 fun dates(s:Subscription,from:LocalDate,to:LocalDate):List<LocalDate> {
  if(!s.active || to<from) return emptyList()
  val start=LocalDate.parse(s.startDate)
  val months=when(s.cycle){"MONTH"->1L;"QUARTER"->3L;"YEAR"->12L;"WEEK"->0L;else->error("未知周期")}
  var n=if(months==0L) (ChronoUnit.DAYS.between(start,from)/7).coerceAtLeast(0) else (ChronoUnit.MONTHS.between(start.withDayOfMonth(1),from.withDayOfMonth(1))/months).coerceAtLeast(0)
  val result=mutableListOf<LocalDate>()
  while(true) {
   val date=if(months==0L) start.plusWeeks(n) else start.plusMonths(n*months)
   if(date>to) break
   if(date>=from) result.add(date)
   n++
  }
  return result
 }
 fun next(s:Subscription,today:LocalDate):LocalDate? {
  if(!s.active) return null
  val from=maxOf(today,LocalDate.parse(s.startDate))
  return dates(s,from,from.plusYears(1)).firstOrNull()
 }
}

enum class SubscriptionFilter(val label: String, val emptyText: String) {
 ALL("全部", "还没有订阅记录"),
 ACTIVE("启用", "暂无启用的订阅"),
 INACTIVE("停用", "暂无停用的订阅"),
 DUE_SOON("7天内到期", "未来7天没有到期订阅");

 fun apply(subscriptions: List<Subscription>, today: LocalDate): List<Subscription> =
  subscriptions.filter { s ->
   when (this) {
    ALL -> true
    ACTIVE -> s.active
    INACTIVE -> !s.active
    DUE_SOON -> SubscriptionRules.dates(s, today, today.plusDays(6)).isNotEmpty()
   }
  }
}

data class SubscriptionReminderDue(val subscription: Subscription, val due: LocalDate, val checkedOn: LocalDate) {
 // A length prefix makes IDs containing separators unambiguous.
 val key: String get() = "$checkedOn|${subscription.id.length}:${subscription.id}|$due"
}

object SubscriptionReminderRules {
 fun due(subscriptions: List<Subscription>, payments: List<SubscriptionPayment>, today: LocalDate): List<SubscriptionReminderDue> {
  val paid = payments.mapTo(HashSet()) { it.subscriptionId to it.dueDate }
  return subscriptions.flatMap { s ->
   // One malformed imported row must not suppress reminders for every other subscription.
   runCatching { SubscriptionRules.dates(s, today, today.plusDays(1)) }.getOrDefault(emptyList())
    .filter { (s.id to it.toString()) !in paid }
    .map { SubscriptionReminderDue(s, it, today) }
  }
 }

 fun nextCheck(now: ZonedDateTime): ZonedDateTime =
  now.toLocalDate().plusDays(1).atTime(9, 0).atZone(now.zone)
}
