package com.lianbei.daily

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.net.URI

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
