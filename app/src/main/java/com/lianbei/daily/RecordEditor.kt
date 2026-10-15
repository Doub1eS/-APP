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

@Composable
fun RecordEditor(kind: Kind, day: String, record: DailyRecord?, categories: List<String>, onDismiss: () -> Unit, onSave: (DailyRecord, String?) -> Unit) {
    var title by rememberSaveable { mutableStateOf(record?.title ?: "") }
    var detail by rememberSaveable { mutableStateOf(record?.detail ?: "") }
    var date by rememberSaveable { mutableStateOf(record?.date ?: day) }
    var time by rememberSaveable { mutableStateOf(record?.time ?: LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))) }
    var amount by rememberSaveable { mutableStateOf(record?.value?.let { if (kind == Kind.EXPENSE) RecordRules.money(it) else it.toString() } ?: "") }
    var start by rememberSaveable { mutableStateOf(record?.extra?.substringBefore("|")?.takeIf { it.isNotEmpty() } ?: "23:00") }
    var end by rememberSaveable { mutableStateOf(record?.extra?.substringAfter("|", "07:00") ?: "07:00") }
    var category by rememberSaveable { mutableStateOf(if (kind == Kind.NOTE) record?.extra ?: "" else "") }
    var error by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding().imePadding().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (record == null) "记一笔 · ${kind.label}" else "编辑 · ${kind.label}", style = MaterialTheme.typography.headlineSmall)
                Text("记下最重要的就好，其余可以以后补。", style = MaterialTheme.typography.bodySmall)
                if (kind == Kind.FOOD) Choices(listOf("早餐", "午餐", "晚餐", "加餐")) { title = it }
                if (kind == Kind.EXPENSE) Choices(listOf("餐饮", "交通", "购物", "居家", "其他")) { title = it }
                if (kind == Kind.TRAINING) Choices(listOf("力量训练", "步行", "跑步", "拉伸")) { title = it }
                if (kind == Kind.STATE) {
                    Choices(listOf("开心", "平静", "低落", "烦躁")) { title = it }
                    Choices(listOf("精力充沛", "精力一般", "有些疲惫")) { detail = it }
                }
                if (kind == Kind.SLEEP) {
                    OutlinedTextField(start, { start = it }, label = { Text("入睡时间，例如 23:30") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(end, { end = it }, label = { Text("起床时间，例如 07:00") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Text("日期按起床当天记录；起床早于入睡时按跨午夜计算。", style = MaterialTheme.typography.bodySmall)
                } else {
                    OutlinedTextField(title, { title = it }, label = { Text(when(kind) { Kind.FOOD -> "餐次或吃了什么"; Kind.EXPENSE -> "消费用途"; Kind.NOTE -> "想记下什么"; else -> "${kind.label}内容" }) }, modifier = Modifier.fillMaxWidth())
                }
                if (kind == Kind.EXPENSE || kind == Kind.TRAINING) OutlinedTextField(amount, { amount = it }, label = { Text(if (kind == Kind.EXPENSE) "金额（元）" else "时长（分钟，可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (kind == Kind.NOTE) {
                    if (categories.isNotEmpty()) Choices(categories) { category = it }
                    OutlinedTextField(category, { category = it }, label = { Text("自定义类别（可选，例如读书）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Text("填写新类别后会保存为下次的快捷选择。", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(detail, { detail = it }, label = { Text(if (kind == Kind.TRAINING) "动作、组数、次数、重量或备注（可选）" else "补充一句（可选）") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                OutlinedTextField(date, { date = it }, label = { Text("日期 YYYY-MM-DD") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(time, { time = it }, label = { Text("记录时间 HH:mm") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                Button(modifier = Modifier.fillMaxWidth(), onClick = {
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
                    if (error.isEmpty()) onSave(DailyRecord(record?.id ?: UUID.randomUUID().toString(), kind.name, validDate.toString(), validTime!!.format(DateTimeFormatter.ofPattern("HH:mm")), if (kind == Kind.SLEEP) "睡了 ${value!! / 60} 小时 ${value % 60} 分钟" else title.trim(), detail.trim(), value, when(kind) { Kind.SLEEP -> "$start|$end"; Kind.NOTE -> category.trim(); else -> "" }, record?.createdAt ?: System.currentTimeMillis()), category.trim().takeIf { kind == Kind.NOTE && it.isNotEmpty() })
                }) { Text("保存记录") }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("取消") }
            }
        }
    }
}

@Composable
private fun Choices(options: List<String>, onClick: (String) -> Unit) {
    Column { options.chunked(3).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { row.forEach { text -> SuggestionChip(onClick = { onClick(text) }, label = { Text(text) }) } } } }
}
