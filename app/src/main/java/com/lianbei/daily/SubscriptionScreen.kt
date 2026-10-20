package com.lianbei.daily

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

private val subscriptionColors=listOf(Color(0xFF24634C),Color(0xFF315E9D),Color(0xFF6153A3),Color(0xFF995020))
private fun cash(c:Long)="¥${RecordRules.money(c)}"

@OptIn(ExperimentalLayoutApi::class)
@Composable fun SubscriptionScreen(dao:RecordDao,records:List<DailyRecord>) {
 val subscriptions by dao.subscriptions().collectAsStateWithLifecycle(emptyList())
 val links by dao.subscriptionPayments().collectAsStateWithLifecycle(emptyList())
 val scope=rememberCoroutineScope();val context=LocalContext.current
 val snackbar=remember {SnackbarHostState()}
 var mode by rememberSaveable {mutableStateOf("列表")}
 var filter by rememberSaveable {mutableStateOf(SubscriptionFilter.ACTIVE.name)}
 var monthText by rememberSaveable {mutableStateOf(YearMonth.now().toString())}
 val month=YearMonth.parse(monthText);val today=LocalDate.now()
 var editingId by rememberSaveable {mutableStateOf<String?>(null)}
 val editing=subscriptions.find {it.id==editingId}
 var editor by rememberSaveable {mutableStateOf(false)}
 var deleting by remember {mutableStateOf<Subscription?>(null)}
 var confirming by remember {mutableStateOf<Pair<Subscription,LocalDate>?>(null)}
 var busy by remember {mutableStateOf(false)}
 var error by remember {mutableStateOf("")}
 var message by remember {mutableStateOf("")}
 var selectedDate by rememberSaveable {mutableStateOf<String?>(null)}
 val active=subscriptions.filter {it.active}
 fun total(from:LocalDate,to:LocalDate)=active.sumOf {s->SubscriptionRules.dates(s,from,to).size*s.cents}
 val current=YearMonth.from(today)
 fun mutate(action:suspend ()->Unit) {if(busy)return;busy=true;error="";scope.launch {try {action();SubscriptionReminders.refresh(context)}catch(_:Exception){error="操作失败，请检查记录后重试。"}finally {busy=false}}}
 fun openLink(s:Subscription) {
  val notice=try {check(SubscriptionRules.validLink(s.paymentUrl)&&s.paymentUrl.isNotBlank());context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(s.paymentUrl)).addCategory(Intent.CATEGORY_BROWSABLE));"完成缴费后，请返回确认付款。"}
  catch(_:Exception){"无法打开缴费链接，请检查网址或安装浏览器。"}
  scope.launch {snackbar.currentSnackbarData?.dismiss();snackbar.showSnackbar(notice,actionLabel="知道了",duration=SnackbarDuration.Long)}
 }
 Box(Modifier.fillMaxSize()) {
 LazyColumn(Modifier.fillMaxSize().testTag("subscriptions-list"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
  item {
   PageTitle("订阅管理","记住每一笔续费，让支出心中有数。")
  }
  item {
   Button(onClick={editingId=null;error="";editor=true},modifier=Modifier.fillMaxWidth()){Text("添加订阅")}
  }
  item {
   Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)) {
    Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
     Text("本月预计扣费",style=MaterialTheme.typography.labelLarge)
     Text(cash(total(current.atDay(1),current.atEndOfMonth())),fontSize=32.sp,fontWeight=FontWeight.Bold)
     Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
      Column(Modifier.weight(1f)){Text("${active.size}",fontSize=24.sp,fontWeight=FontWeight.Bold);Text("启用订阅")}
      Column(Modifier.weight(1f)){Text("${active.count {SubscriptionRules.dates(it,today,today.plusDays(6)).isNotEmpty()}}",fontSize=24.sp,fontWeight=FontWeight.Bold);Text("7天内扣费")}
     }
     HorizontalDivider()
     Text("本年预计扣费  ${cash(total(today.withDayOfYear(1),today.withMonth(12).withDayOfMonth(31)))}")
     Text("按当前启用计划预测，实际支出请查看「回看」。",style=MaterialTheme.typography.bodySmall)
    }
   }
  }
  item {SubscriptionReminderPanel()}
  if(message.isNotBlank()) item {Text(message,color=MaterialTheme.colorScheme.primary)}
  if(error.isNotBlank()&&!editor&&confirming==null) item {Text(error,color=MaterialTheme.colorScheme.error)}
  item {FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("列表","日历","统计").forEach {tab->FilterChip(mode==tab,{mode=tab;selectedDate=null},label={Text(tab)})}}}
  if(mode=="列表") {
   val selectedFilter=SubscriptionFilter.entries.find {it.name==filter} ?: SubscriptionFilter.ACTIVE
   item {FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){SubscriptionFilter.entries.forEach {f->FilterChip(selectedFilter==f,{filter=f.name},label={Text(f.label)},modifier=Modifier.testTag("subscription-filter-${f.name}"))}}}
   val shown=selectedFilter.apply(subscriptions,today).sortedBy {SubscriptionRules.next(it,today) ?: LocalDate.MAX}
   if(shown.isEmpty()) item {Panel {
    Text(if(subscriptions.isEmpty())"还没有订阅记录" else selectedFilter.emptyText)
    Text(if(subscriptions.isEmpty())"点击上方「添加订阅」，记录金额、周期和缴费链接。" else "可切换筛选查看其他订阅。")
   }}
   items(shown,key={it.id}) {s->
    val next=SubscriptionRules.next(s,today)
    val paid=next?.let {d->links.any {it.subscriptionId==s.id&&it.dueDate==d.toString()}} ?: false
    Card(modifier=Modifier.testTag("subscription-card-${s.id}"),colors=CardDefaults.cardColors(containerColor=subscriptionColors[s.color.coerceIn(0,3)].copy(alpha=.12f))) {
     Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
      Text(s.name,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
      Text("${SubscriptionRules.cycles[s.cycle]} · ${s.category} · ${if(s.active)"启用中" else "已停用"}")
      Text(cash(s.cents),fontSize=30.sp,fontWeight=FontWeight.Bold,color=subscriptionColors[s.color.coerceIn(0,3)])
      Text(next?.let {"下一计划扣费  $it${if(paid)" · 已确认" else ""}"} ?: "已暂停扣费预测")
      if(s.note.isNotBlank())Text(s.note)
      if(s.paymentUrl.isNotBlank()) {
       Text(Uri.parse(s.paymentUrl).host ?: s.paymentUrl,style=MaterialTheme.typography.bodySmall)
       Button(onClick={openLink(s)}){Text("去缴费")}
      } else Text("尚未设置缴费链接，可在编辑中添加。",style=MaterialTheme.typography.bodySmall)
      FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
       TextButton(onClick={error="";confirming=s to (next ?: today)}){Text("确认付款")}
       TextButton(onClick={editingId=s.id;error="";editor=true}){Text("编辑")}
       TextButton(onClick={mutate {dao.saveSubscription(s.copy(active=!s.active))}},enabled=!busy,modifier=Modifier.testTag("subscription-toggle-${s.id}")){Text(if(s.active)"停用" else "恢复")}
       TextButton(onClick={deleting=s},enabled=!busy){Text("删除")}
      }
      val history=links.filter {it.subscriptionId==s.id}.sortedByDescending {it.dueDate}
      if(history.isNotEmpty()) {
       Text("已确认 ${history.size} 期",fontWeight=FontWeight.SemiBold)
       history.take(3).forEach {p->val r=records.find {it.id==p.recordId};Text("${p.dueDate} · ${r?.let {cash(it.value ?: 0)} ?: "对应消费已删除"}",style=MaterialTheme.typography.bodySmall)}
      }
     }
    }
   }
  } else {
   item {Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){
    TextButton(onClick={monthText=month.minusMonths(1).toString();selectedDate=null}){Text("上月")}
    Text("${month.year}年${month.monthValue}月",fontWeight=FontWeight.Bold)
    TextButton(onClick={monthText=month.plusMonths(1).toString();selectedDate=null}){Text("下月")}
   }}
   val events=active.flatMap {s->SubscriptionRules.dates(s,month.atDay(1),month.atEndOfMonth()).map {it to s}}.sortedBy {it.first}
   if(mode=="日历") {
    item {Panel {
     Text("扣费日历",style=MaterialTheme.typography.titleLarge)
     Text("左右滑动查看完整一周",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
     Column(Modifier.horizontalScroll(rememberScrollState())) {
      Row(Modifier.width(364.dp)){listOf("一","二","三","四","五","六","日").forEach {Text(it,Modifier.width(52.dp),textAlign=androidx.compose.ui.text.style.TextAlign.Center)}}
      val cells=List(month.atDay(1).dayOfWeek.value-1){0}+(1..month.lengthOfMonth()).toList()
      cells.chunked(7).forEach {week->Row {week.forEach {day->
       if(day==0)Spacer(Modifier.size(52.dp)) else {
        val d=month.atDay(day);val count=events.count {it.first==d}
        TextButton(onClick={selectedDate=if(selectedDate==d.toString())null else d.toString()},modifier=Modifier.size(52.dp),contentPadding=PaddingValues(0.dp)) {
         Column(horizontalAlignment=Alignment.CenterHorizontally){Text("$day",fontWeight=if(count>0)FontWeight.Bold else FontWeight.Normal);if(count>0)Text("●",fontSize=9.sp)}
        }
       }
      }}}
     }
     Text(selectedDate?.let {"已选 $it · 再点日期显示全月"} ?: "点击日期查看扣费，圆点表示有计划扣费。",style=MaterialTheme.typography.bodySmall)
    }}
    val filtered=events.filter {selectedDate==null||it.first.toString()==selectedDate}
    if(filtered.isEmpty())item {Text("所选时间没有预计扣费。")}
    items(filtered,key={"${it.second.id}-${it.first}"}) {(date,s)->Panel {
     Text("$date · ${s.name}",fontWeight=FontWeight.Bold);Text(cash(s.cents))
     FlowRow {if(s.paymentUrl.isNotBlank())TextButton(onClick={openLink(s)}){Text("去缴费")};TextButton(onClick={error="";confirming=s to date}){Text(if(links.any {it.subscriptionId==s.id&&it.dueDate==date.toString()})"已确认 · 查看" else "确认付款")}}
    }}
   } else {
    item {Panel {
     Text("${month.year}年月度预计扣费",style=MaterialTheme.typography.titleLarge)
     val values=(1..12).map {m->val ym=YearMonth.of(month.year,m);total(ym.atDay(1),ym.atEndOfMonth())}
     val max=values.maxOrNull()?.coerceAtLeast(1) ?: 1
     Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.Bottom) {
      values.forEachIndexed {i,v->Column(Modifier.width(80.dp).clickable {monthText=YearMonth.of(month.year,i+1).toString()},horizontalAlignment=Alignment.CenterHorizontally){
       Text(cash(v),fontSize=11.sp)
       Box(Modifier.height(128.dp).fillMaxWidth(),contentAlignment=Alignment.BottomCenter){Box(Modifier.width(30.dp).height((128f*v/max).coerceAtLeast(2f).dp).background(if(i+1==month.monthValue)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,RoundedCornerShape(12.dp)))}
       Text("${i+1}月")
      }}
     }
    }}
    item {Panel {
     Text("${month.monthValue}月分类占比",style=MaterialTheme.typography.titleLarge)
     val sum=events.sumOf {it.second.cents}
     Text("预计合计 ${cash(sum)}")
     if(sum==0L)Text("本月暂无预计扣费")
     events.groupBy {it.second.category}.mapValues {it.value.sumOf {p->p.second.cents}}.toList().sortedByDescending {it.second}.forEach {(category,value)->
      Text("$category  ${cash(value)} · ${"%.1f".format(value*100.0/sum)}%")
      LinearProgressIndicator(progress={value.toFloat()/sum},modifier=Modifier.fillMaxWidth())
     }
    }}
   }
  }
 }
 SnackbarHost(snackbar,Modifier.align(Alignment.BottomCenter).padding(12.dp))
 }
 if(editor && (editingId==null || editing!=null)) SubscriptionEditor(editing,busy,error,{editor=false},{s->mutate {dao.saveSubscription(s);editor=false;message="订阅已保存"}})
 confirming?.let {(s,due)->SubscriptionConfirm(s,due,records,links,busy,error,{confirming=null},{d,c,date,existing->mutate {dao.confirmSubscription(s.id,d,c,date,existing);confirming=null;message="本期付款已确认，可在消费记录中查看。"}})}
 deleting?.let {s->AlertDialog(onDismissRequest={if(!busy)deleting=null},title={Text("删除 ${s.name}？")},text={Text("删除订阅计划，已确认的消费记录仍保留。")},confirmButton={TextButton(enabled=!busy,onClick={mutate {dao.deleteSubscription(s.id);deleting=null}}){Text("删除订阅")}},dismissButton={TextButton(enabled=!busy,onClick={deleting=null}){Text("取消")}})}
}
