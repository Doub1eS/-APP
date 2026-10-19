package com.lianbei.daily

import java.time.LocalDate

object DemoData {
 const val PREFIX="lianbei-demo-"
 suspend fun add(dao:RecordDao,today:LocalDate=LocalDate.now())=dao.addDemo(today.toString())
 suspend fun clear(dao:RecordDao)=dao.clearDemo()
 internal suspend fun populate(dao:RecordDao,today:LocalDate) {
  for(i in 0..6) {
   val date=today.minusDays(i.toLong()).toString()
   val foodId="${PREFIX}food-$i"
   if(dao.record(foodId)==null) dao.saveFood(DailyRecord(foodId,"FOOD",date,"12:15","午餐",listOf("牛肉面","番茄炒蛋配米饭","鸡肉沙拉")[i%3]),"",if(i==2)null else 1800L+i*200,null)
   val rows=listOf(
    DailyRecord("${PREFIX}sleep-$i","SLEEP",date,"07:00","睡了7小时30分钟",value=450,extra="23:30|07:00"),
    DailyRecord("${PREFIX}training-$i","TRAINING",date,"18:00",if(i%2==0)"步行" else "力量训练","示例：记录今天的活动",30L+i*5),
    DailyRecord("${PREFIX}expense-$i","EXPENSE",date,"08:30","交通","示例：地铁出行",600),
    DailyRecord("${PREFIX}state-$i","STATE",date,"20:00",if(i%2==0)"平静" else "开心","精力充沛"),
    DailyRecord("${PREFIX}note-$i","NOTE",date,"21:00","示例：留一点时间给自己","读完一章书，记录今天的小收获。",extra="读书")
   )
   rows.forEach {if(dao.record(it.id)==null)dao.save(it)}
  }
  listOf(
   Subscription("${PREFIX}music","示例 · 音乐会员",1500,"MONTH",today.plusDays(2).toString(),"娱乐",note="示例数据，可编辑为自己的会员",color=0),
   Subscription("${PREFIX}cloud","示例 · 云盘",9800,"YEAR",today.plusDays(5).toString(),"工具",color=1),
   Subscription("${PREFIX}fitness","示例 · 健身会员",9900,"MONTH",today.toString(),"健康",color=2)
  ).forEach {if(dao.subscription(it.id)==null)dao.saveSubscription(it)}
 }
 internal suspend fun remove(dao:RecordDao) {
  val rows=dao.allRecords()
  val ownedExpenses=dao.allFoodEntries().filter {it.foodId.startsWith(PREFIX)&&it.ownsExpense}.mapNotNull {it.expenseId}.toSet()
  rows.filter {it.id.startsWith(PREFIX)||it.id.startsWith("food-expense-$PREFIX")||it.id in ownedExpenses}.forEach {dao.delete(it)}
  dao.allSubscriptions().filter {it.id.startsWith(PREFIX)}.forEach {s->
   dao.allSubscriptionPayments().filter {it.subscriptionId==s.id}.forEach {p->
    // Only remove expenses created for this demo subscription, not user-linked expenses.
    if(p.recordId=="subscription-${s.id}-${p.dueDate}")dao.record(p.recordId)?.let {dao.delete(it)}
    dao.removeSubscriptionPayment(s.id,p.dueDate)
   }
   dao.deleteSubscription(s.id)
  }
 }
}
