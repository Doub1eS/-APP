package com.lianbei.daily

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** An edit is saved independently of confirmation; the DAO rechecks pending status atomically. */
@Composable
fun PaymentEdit(
    original: PendingPayment,
    available: Boolean,
    busy: Boolean,
    saveError: String,
    onDismiss: () -> Unit,
    onSave: (PendingPayment) -> Unit
) {
    val context = LocalContext.current
    var amount by rememberSaveable(original.id) { mutableStateOf(RecordRules.money(original.cents)) }
    var merchant by rememberSaveable(original.id) { mutableStateOf(original.merchant) }
    var date by rememberSaveable(original.id) { mutableStateOf(original.date) }
    var time by rememberSaveable(original.id) { mutableStateOf(original.time) }
    var category by rememberSaveable(original.id) { mutableStateOf(original.category) }
    var validationError by remember { mutableStateOf("") }
    val editable = available && !busy
    val error = if (!available) "这笔账目已处理或已不存在，无法再修改。"
        else validationError.ifBlank { saveError }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize().testTag("payment-editor")) {
            Scaffold(
                modifier = Modifier.safeDrawingPadding().imePadding(),
                topBar = {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                        TextButton(
                            onClick = onDismiss, enabled = !busy,
                            modifier = Modifier.testTag("payment-edit-cancel")
                        ) { Text("取消") }
                        Text("编辑待确认账目", style = MaterialTheme.typography.headlineSmall)
                    }
                },
                bottomBar = {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (error.isNotBlank()) Text(
                            error, color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("payment-edit-error")
                        )
                        Button(
                            enabled = editable,
                            modifier = Modifier.fillMaxWidth().testTag("payment-edit-save"),
                            onClick = {
                                if (editable) {
                                    val cents = RecordRules.cents(amount)
                                    val parsedDate = runCatching { LocalDate.parse(date.trim()) }.getOrNull()
                                    val validTime = time.trim().matches(Regex("\\d{2}:\\d{2}")) &&
                                        runCatching { LocalTime.parse(time.trim()) }.isSuccess
                                    val parent = category.substringBefore(" / ")
                                    val child = category.substringAfter(" / ", "")
                                    validationError = when {
                                        cents == null -> "金额需大于 0，最多两位小数，且不能超出支持范围。"
                                        merchant.isBlank() -> "请填写商户名称。"
                                        merchant.trim().length > 200 -> "商户名称最多填写 200 字。"
                                        parsedDate == null -> "请填写有效日期，格式为 YYYY-MM-DD。"
                                        parsedDate > LocalDate.now() -> "付款日期不能晚于今天。"
                                        !validTime -> "请填写有效时间，格式为 HH:mm。"
                                        parent !in ExpenseCategories.groups ||
                                            ExpenseCategories.selection(parent, child) != category -> "请选择有效分类。"
                                        else -> ""
                                    }
                                    if (validationError.isEmpty()) onSave(original.copy(
                                        cents = cents!!, merchant = merchant.trim(), date = parsedDate.toString(),
                                        time = time.trim(), category = category
                                    ))
                                }
                            }
                        ) { Text(if (busy) "正在保存…" else "保存修改") }
                    }
                }
            ) { padding ->
                Column(
                    Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("保存后仍在待确认列表，确认入账后才计入消费。", style = MaterialTheme.typography.bodyMedium)
                    Text("来源：${original.source}", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        amount, { amount = it; validationError = "" }, enabled = editable,
                        label = { Text("金额（元）") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth().testTag("payment-edit-amount")
                    )
                    OutlinedTextField(
                        merchant, { merchant = it; validationError = "" }, enabled = editable,
                        label = { Text("商户名称") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("payment-edit-merchant")
                    )
                    OutlinedTextField(
                        date, { date = it; validationError = "" }, enabled = editable,
                        label = { Text("日期 YYYY-MM-DD") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("payment-edit-date")
                    )
                    OutlinedTextField(
                        time, { time = it; validationError = "" }, enabled = editable,
                        label = { Text("付款时间 HH:mm") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("payment-edit-time")
                    )
                    Row {
                        TextButton(enabled = editable, onClick = {
                            val d = runCatching { LocalDate.parse(date) }.getOrDefault(LocalDate.now())
                            DatePickerDialog(context, { _, year, month, day ->
                                date = LocalDate.of(year, month + 1, day).toString(); validationError = ""
                            }, d.year, d.monthValue - 1, d.dayOfMonth).apply {
                                datePicker.maxDate = System.currentTimeMillis()
                            }.show()
                        }) { Text("选择日期") }
                        TextButton(enabled = editable, onClick = {
                            val t = runCatching { LocalTime.parse(time) }.getOrDefault(LocalTime.now())
                            TimePickerDialog(context, { _, hour, minute ->
                                time = LocalTime.of(hour, minute).format(DateTimeFormatter.ofPattern("HH:mm"))
                                validationError = ""
                            }, t.hour, t.minute, true).show()
                        }) { Text("选择时间") }
                    }
                    if (editable) ExpenseCategoryPicker(
                        category.substringBefore(" / "), category.substringAfter(" / ", "")
                    ) { parent, child ->
                        category = ExpenseCategories.selection(parent, child)
                        validationError = ""
                    } else Text("分类 · $category")
                }
            }
        }
    }
}
