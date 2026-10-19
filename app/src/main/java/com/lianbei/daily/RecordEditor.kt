package com.lianbei.daily

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun RecordEditor(kind: Kind, day: String, record: DailyRecord?, categories: List<String>, onDismiss: () -> Unit, isSaving: Boolean = false, saveError: String = "", onSave: (DailyRecord, String?) -> Unit) {
    val context = LocalContext.current
    val recordId=rememberSaveable {record?.id ?: UUID.randomUUID().toString()}
    var title by rememberSaveable { mutableStateOf(if(kind==Kind.EXPENSE) ExpenseCategories.main(record?.title.orEmpty()) else record?.title ?: "") }
    var expenseChild by rememberSaveable {mutableStateOf(if(kind==Kind.EXPENSE) record?.extra.orEmpty() else "")}
    var detail by rememberSaveable { mutableStateOf(record?.detail ?: "") }
    var date by rememberSaveable { mutableStateOf(record?.date ?: day) }
    var time by rememberSaveable { mutableStateOf(record?.time ?: LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))) }
    var amount by rememberSaveable { mutableStateOf(record?.value?.let { if (kind == Kind.EXPENSE) RecordRules.money(it) else it.toString() } ?: "") }
    var start by rememberSaveable { mutableStateOf(record?.extra?.substringBefore("|")?.takeIf { it.isNotEmpty() } ?: "23:00") }
    var end by rememberSaveable { mutableStateOf(record?.extra?.substringAfter("|", "07:00") ?: "07:00") }
    var category by rememberSaveable { mutableStateOf(if (kind == Kind.NOTE) record?.extra ?: "" else "") }
    var error by remember { mutableStateOf("") }
    var errorField by remember { mutableStateOf("") }
    val fields=listOf("date","time","title","amount","start","end")
    val focus=remember {fields.associateWith {FocusRequester()}}
    val reveal=remember {fields.associateWith {BringIntoViewRequester()}}
    val editorScope=rememberCoroutineScope()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(modifier=Modifier.safeDrawingPadding().imePadding(), bottomBar={
                Surface(shadowElevation=4.dp) { Column(Modifier.padding(horizontal=20.dp,vertical=8.dp)) {
                    if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
                    if(saveError.isNotBlank()) Text(saveError,color=MaterialTheme.colorScheme.error)
                Button(modifier = Modifier.fillMaxWidth(), enabled=!isSaving, onClick = {
                    val validDate = runCatching { LocalDate.parse(date) }.getOrNull()
                    val validTime = runCatching { LocalTime.parse(time) }.getOrNull()
                    val value = when(kind) { Kind.EXPENSE -> RecordRules.cents(amount); Kind.SLEEP -> RecordRules.sleepMinutes(start, end); Kind.TRAINING -> amount.toLongOrNull(); else -> null }
                    error = when {
                        validDate == null || validDate > LocalDate.now() -> "请填写有效且不晚于今天的日期"
                        validTime == null -> "请填写有效时间，例如 09:30"
                        kind != Kind.SLEEP && title.isBlank() -> "写一点内容再保存吧"
                        kind == Kind.EXPENSE && value == null -> "金额需大于零，最多两位小数"
                        kind == Kind.SLEEP && value == null -> "请检查睡眠时间，入睡与起床不能相同"
                        kind == Kind.TRAINING && amount.isNotBlank() && (value == null || value <= 0) -> "训练时长请输入正整数"
                        else -> ""
                    }
                    errorField=when {
                        validDate==null || validDate>LocalDate.now()->"date"
                        validTime==null->"time"
                        kind!=Kind.SLEEP && title.isBlank()->"title"
                        kind==Kind.SLEEP && value==null->if(runCatching {LocalTime.parse(start)}.isFailure) "start" else "end"
                        error.isNotEmpty()->"amount"
                        else->""
                    }
                    if(errorField.isNotEmpty()) editorScope.launch {
                        focus.getValue(errorField).requestFocus()
                        delay(200)
                        reveal.getValue(errorField).bringIntoView()
                    }
                    if (error.isEmpty()) onSave(DailyRecord(recordId, kind.name, validDate.toString(), validTime!!.format(DateTimeFormatter.ofPattern("HH:mm")), if (kind == Kind.SLEEP) "睡了 ${value!! / 60} 小时 ${value % 60} 分钟" else title.trim(), detail.trim(), value, when(kind) { Kind.SLEEP -> "$start|$end"; Kind.NOTE -> category.trim(); Kind.EXPENSE -> expenseChild; else -> "" }, record?.createdAt ?: System.currentTimeMillis()), category.trim().takeIf { kind == Kind.NOTE && it.isNotEmpty() })
                }) { Text(if(isSaving) "正在保存…" else "保存记录") }
                } }
            }) { editorPadding ->
            Column(Modifier.padding(editorPadding).padding(horizontal=20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick=onDismiss) { Text("取消") }
                    Spacer(Modifier.weight(1f))
                    KindMark(kind)
                }
                Text(if (record == null) "记一笔 · ${kind.label}" else "编辑 · ${kind.label}", style = MaterialTheme.typography.headlineSmall, fontWeight=FontWeight.Bold)
                Text("记下最重要的就好，其余可以以后补。", style = MaterialTheme.typography.bodySmall)
                if (kind == Kind.FOOD) Choices(listOf("早餐", "午餐", "晚餐", "加餐"),title) { title = it }
                if (kind == Kind.EXPENSE) OutlinedTextField(amount, {amount=it}, label={Text("金额（元）")}, prefix={Text("¥")},
                    textStyle=MaterialTheme.typography.headlineMedium.copy(fontWeight=FontWeight.Bold),
                    singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),
                    modifier=Modifier.fillMaxWidth().focusRequester(focus.getValue("amount")).bringIntoViewRequester(reveal.getValue("amount")),
                    isError=errorField=="amount",supportingText=if(errorField=="amount") ({Text(error)}) else null)
                if (kind == Kind.EXPENSE) ExpenseCategoryPicker(title,expenseChild) {main,child->title=main;expenseChild=child}
                if (kind == Kind.TRAINING) Choices(listOf("力量训练", "步行", "跑步", "拉伸"),title) { title = it }
                if (kind == Kind.STATE) {
                    Choices(listOf("开心", "平静", "低落", "烦躁"),title) { title = it }
                    Choices(listOf("精力充沛", "精力一般", "有些疲惫"),detail) { detail = it }
                }
                if (kind == Kind.SLEEP) {
                    OutlinedTextField(start, { start = it }, label = { Text("入睡时间，例如 23:30") }, modifier = Modifier.fillMaxWidth().focusRequester(focus.getValue("start")).bringIntoViewRequester(reveal.getValue("start")), singleLine = true, isError=errorField=="start", supportingText=if(errorField=="start") ({Text(error)}) else null)
                    OutlinedTextField(end, { end = it }, label = { Text("起床时间，例如 07:00") }, modifier = Modifier.fillMaxWidth().focusRequester(focus.getValue("end")).bringIntoViewRequester(reveal.getValue("end")), singleLine = true, isError=errorField=="end", supportingText=if(errorField=="end") ({Text(error)}) else null)
                    Row {
                        TextButton(onClick={ showTimePicker(context,start) {start=it} }) {Text("选择入睡时间")}
                        TextButton(onClick={ showTimePicker(context,end) {end=it} }) {Text("选择起床时间")}
                    }
                    RecordRules.sleepMinutes(start,end)?.let { minutes -> Panel {Text("${minutes/60} 小时 ${minutes%60} 分钟",style=MaterialTheme.typography.headlineSmall,color=kind.tint())} }
                    Text("日期按起床当天记录；起床早于入睡时按跨午夜计算。", style = MaterialTheme.typography.bodySmall)
                } else if(kind!=Kind.EXPENSE) {
                    OutlinedTextField(title, { title = it }, label = { Text(when(kind) { Kind.FOOD -> "餐次或吃了什么"; Kind.EXPENSE -> "消费用途"; Kind.NOTE -> "想记下什么"; else -> "${kind.label}内容" }) }, modifier = Modifier.fillMaxWidth().focusRequester(focus.getValue("title")).bringIntoViewRequester(reveal.getValue("title")), isError=errorField=="title", supportingText=if(errorField=="title") ({Text(error)}) else null)
                }
                if (kind == Kind.TRAINING) OutlinedTextField(amount, { amount = it }, label = { Text(if (kind == Kind.EXPENSE) "金额（元）" else "时长（分钟，可选）") }, singleLine = true, keyboardOptions=KeyboardOptions(keyboardType=if(kind==Kind.EXPENSE) KeyboardType.Decimal else KeyboardType.Number), modifier = Modifier.fillMaxWidth().focusRequester(focus.getValue("amount")).bringIntoViewRequester(reveal.getValue("amount")), isError=errorField=="amount", supportingText=if(errorField=="amount") ({Text(error)}) else null)
                if (kind == Kind.NOTE) {
                    if (categories.isNotEmpty()) Choices(categories,category) { category = it }
                    OutlinedTextField(category, { category = it }, label = { Text("自定义类别（可选，例如读书）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Text("填写新类别后会保存为下次的快捷选择。", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(detail, { detail = it }, label = { Text(if (kind == Kind.TRAINING) "动作、组数、次数、重量或备注（可选）" else "补充一句（可选）") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                OutlinedTextField(date, { date = it }, label = { Text("日期 YYYY-MM-DD") }, singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(focus.getValue("date")).bringIntoViewRequester(reveal.getValue("date")), isError=errorField=="date", supportingText=if(errorField=="date") ({Text(error)}) else null)
                OutlinedTextField(time, { time = it }, label = { Text("记录时间 HH:mm") }, singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(focus.getValue("time")).bringIntoViewRequester(reveal.getValue("time")), isError=errorField=="time", supportingText=if(errorField=="time") ({Text(error)}) else null)
                Row {
                    TextButton(onClick={val d=runCatching {LocalDate.parse(date)}.getOrDefault(LocalDate.now());DatePickerDialog(context,{_,y,m,dd->date=LocalDate.of(y,m+1,dd).toString()},d.year,d.monthValue-1,d.dayOfMonth).apply { datePicker.maxDate=System.currentTimeMillis() }.show()}) {Text("选择日期")}
                    TextButton(onClick={showTimePicker(context,time){time=it}}){Text("选择记录时间")}
                }
                Spacer(Modifier.height(16.dp))

            }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Choices(options: List<String>, selected: String, onClick: (String) -> Unit) {
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        options.forEach { text -> FilterChip(selected=selected==text,onClick={onClick(text)},label={Text(text)}) }
    }
}
private fun showTimePicker(context: android.content.Context, text:String,onSelect:(String)->Unit) {
    val time=runCatching {LocalTime.parse(text)}.getOrDefault(LocalTime.now())
    TimePickerDialog(context,{_,hour,minute->onSelect(LocalTime.of(hour,minute).format(DateTimeFormatter.ofPattern("HH:mm")))},time.hour,time.minute,true).show()
}
