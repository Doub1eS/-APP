package com.lianbei.daily

import android.app.DatePickerDialog
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.LocalDate
import java.util.UUID

@Composable fun SubscriptionDialog(title:String,busy:Boolean,error:String,onDismiss:()->Unit,onSave:()->Unit,saveLabel:String,content:@Composable ColumnScope.()->Unit) {
 Dialog(onDismissRequest={if(!busy)onDismiss()},properties=DialogProperties(usePlatformDefaultWidth=false)) {
  Surface(Modifier.fillMaxSize()) {
   Scaffold(modifier=Modifier.safeDrawingPadding().imePadding(),bottomBar={
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
     if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
     Button(onClick=onSave,enabled=!busy,modifier=Modifier.fillMaxWidth()){Text(if(busy)"正在保存…" else saveLabel)}
    }
   }) {padding-> Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
    TextButton(onClick=onDismiss,enabled=!busy){Text("取消")}
    Text(title,style=MaterialTheme.typography.headlineMedium)
    content()
   } }
  }
 }
}

@Composable fun SubscriptionDate(label:String,value:String,onChange:(String)->Unit) {
 val context=LocalContext.current
 OutlinedButton(onClick={val d=LocalDate.parse(value);DatePickerDialog(context,{_,y,m,day->onChange(LocalDate.of(y,m+1,day).toString())},d.year,d.monthValue-1,d.dayOfMonth).show()},modifier=Modifier.fillMaxWidth()){Text("$label：$value")}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun SubscriptionEditor(original:Subscription?,busy:Boolean,saveError:String,onDismiss:()->Unit,onSave:(Subscription)->Unit) {
 val id=rememberSaveable {original?.id ?: UUID.randomUUID().toString()}
 var name by rememberSaveable {mutableStateOf(original?.name ?: "")}
 var amount by rememberSaveable {mutableStateOf(original?.let {RecordRules.money(it.cents)} ?: "")}
 var cycle by rememberSaveable {mutableStateOf(original?.cycle ?: "MONTH")}
 var start by rememberSaveable {mutableStateOf(original?.startDate ?: LocalDate.now().toString())}
 var category by rememberSaveable {mutableStateOf(original?.category ?: "其他")}
 var note by rememberSaveable {mutableStateOf(original?.note ?: "")}
 var link by rememberSaveable {mutableStateOf(original?.paymentUrl ?: "")}
 var color by rememberSaveable {mutableIntStateOf(original?.color ?: 0)}
 var error by remember {mutableStateOf("")}
 SubscriptionDialog(if(original==null)"添加订阅" else "编辑订阅",busy,error.ifBlank {saveError},onDismiss,{
  val cents=RecordRules.cents(amount)
  error=when {name.isBlank()->"请填写订阅名称";cents==null || cents>10000000000L->"金额需大于0且不超过1亿元，最多两位小数";!SubscriptionRules.validLink(link.trim())->"缴费链接需为完整的 http:// 或 https:// 地址";else->""}
  if(error.isEmpty()) onSave(Subscription(id,name.trim(),cents!!,cycle,start,category.trim().ifBlank {"其他"},note.trim(),link.trim(),color,original?.active ?: true))
 },"保存订阅") {
  OutlinedTextField(name,{name=it},label={Text("订阅名称")},modifier=Modifier.fillMaxWidth(),singleLine=true)
  OutlinedTextField(amount,{amount=it},label={Text("金额（元）")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth(),singleLine=true)
  FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){SubscriptionRules.cycles.forEach {(key,label)->FilterChip(cycle==key,{cycle=key},label={Text(label)})}}
  SubscriptionDate("首次扣费日期",start){start=it}
  OutlinedTextField(category,{category=it},label={Text("分类")},modifier=Modifier.fillMaxWidth(),singleLine=true)
  OutlinedTextField(link,{link=it},label={Text("缴费链接（可选）")},supportingText={Text("粘贴官方续费网页地址，保存后可一键打开。")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri),modifier=Modifier.fillMaxWidth(),singleLine=true)
  OutlinedTextField(note,{note=it},label={Text("备注")},modifier=Modifier.fillMaxWidth())
  Text("卡片颜色")
  FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("森林","海蓝","葡萄","暖橙").forEachIndexed {i,label->FilterChip(color==i,{color=i},label={Text(label)})}}
  Text("金额按计划扣费日计入预计支出；实际付款需单独确认。",style=MaterialTheme.typography.bodySmall)
 }
}

@Composable fun SubscriptionConfirm(s:Subscription,due:LocalDate,records:List<DailyRecord>,links:List<SubscriptionPayment>,busy:Boolean,error:String,onDismiss:()->Unit,onSave:(String,Long,String,String?)->Unit) {
 var dueText by rememberSaveable {mutableStateOf(due.toString())}
 var paidDate by rememberSaveable {mutableStateOf(LocalDate.now().toString())}
 var amount by rememberSaveable {mutableStateOf(RecordRules.money(s.cents))}
 var existing by rememberSaveable {mutableStateOf<String?>(null)}
 var showExisting by rememberSaveable {mutableStateOf(false)}
 var localError by remember {mutableStateOf("")}
 val used=links.map {it.recordId}.toSet()
 val choices=records.filter {it.kind=="EXPENSE" && it.id !in used}
 SubscriptionDialog("确认付款 · ${s.name}",busy,localError.ifBlank {error},onDismiss,{
  val cents=RecordRules.cents(amount)
  localError=when {cents==null->"请填写正确金额";LocalDate.parse(paidDate)>LocalDate.now()->"付款日期不能晚于今天";SubscriptionRules.dates(s.copy(active=true),LocalDate.parse(dueText),LocalDate.parse(dueText)).isEmpty()->"所选计划日期不属于此订阅周期";else->""}
  if(localError.isEmpty()) onSave(dueText,cents!!,paidDate,existing)
 },"确认并记账") {
  Text("打开缴费网页不会自动记账。完成付款后，再确认本次消费。")
  SubscriptionDate("计划扣费日期",dueText){dueText=it}
  SubscriptionDate("实际付款日期",paidDate){paidDate=it}
  OutlinedTextField(amount,{amount=it},enabled=existing==null,label={Text("实付金额（元）")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth())
  TextButton(onClick={showExisting=!showExisting}){Text("关联已有消费记录")}
  if(existing!=null){Text("将关联已有消费，保留该笔消费原有金额和日期。");TextButton(onClick={existing=null}){Text("改为新建消费")}}
  if(showExisting) {
   if(choices.isEmpty()) Text("暂无可关联的消费，请先在支付待确认中确认或手动记账。")
   choices.forEach {r->OutlinedButton(onClick={existing=r.id;amount=RecordRules.money(r.value ?: 0);paidDate=r.date;showExisting=false},modifier=Modifier.fillMaxWidth()){Text("${r.date} · ${r.title} · ¥${RecordRules.money(r.value ?: 0)}")}}
  }
 }
}
