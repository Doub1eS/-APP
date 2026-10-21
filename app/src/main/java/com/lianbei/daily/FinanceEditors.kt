package com.lianbei.daily

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Strict decimal parsing happens only when saving, never while a user is typing. */
private fun financeInput(text: String, label: String, signed: Boolean = false): Long {
    val cleaned = text.trim()
    val pattern = if (signed) "[+-]?[0-9]+(?:\\.[0-9]{1,2})?" else "\\+?[0-9]+(?:\\.[0-9]{1,2})?"
    require(cleaned.length <= 20 && cleaned.matches(Regex(pattern))) { "$label：请输入金额，最多两位小数。" }
    val cents = try { BigDecimal(cleaned).movePointRight(2).longValueExact() }
    catch (_: ArithmeticException) { throw IllegalArgumentException("${label}超出支持范围。") }
    require(cents in (if (signed) -FinanceValidation.MAX_CENTS else 0L)..FinanceValidation.MAX_CENTS) {
        "${label}超出支持范围。"
    }
    return cents
}

@Composable
private fun FinanceEditorShell(
    title: String, tag: String, busy: Boolean, error: String, onDismiss: () -> Unit,
    onSave: () -> Unit, saveLabel: String = "保存", content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("$tag-editor"), color = MaterialTheme.colorScheme.background) {
            Scaffold(modifier = Modifier.safeDrawingPadding().imePadding(), topBar = {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.testTag("$tag-cancel")) { Text("取消") }
                    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
            }, bottomBar = {
                Surface(shadowElevation = 4.dp) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("$tag-error"))
                        Button(onClick = onSave, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("$tag-save")) {
                            Text(if (busy) "正在保存…" else saveLabel)
                        }
                    }
                }
            }) { padding ->
                Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(20.dp).testTag("$tag-form"), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
            }
        }
    }
}

@Composable
private fun FinanceTextField(
    value: String, onChange: (String) -> Unit, label: String, tag: String, enabled: Boolean,
    decimal: Boolean = true, supporting: String? = null
) {
    OutlinedTextField(value, onChange, enabled = enabled, label = { Text(label) }, singleLine = true,
        supportingText = supporting?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Text),
        modifier = Modifier.fillMaxWidth().testTag(tag))
}

private fun SalaryTerms.inputs() = arrayListOf(
    financeAmount(grossCents), financeAmount(pensionBaseCents), financeAmount(medicalBaseCents),
    financeAmount(unemploymentBaseCents), financeAmount(fundBaseCents),
    financeAmount(pensionRateBp.toLong()), financeAmount(medicalRateBp.toLong()),
    financeAmount(unemploymentRateBp.toLong()), financeAmount(fundRateBp.toLong()), financeAmount(specialDeductionCents)
)

private fun parsedTerms(inputs: List<String>, confirmed: Boolean): SalaryTerms {
    fun money(index: Int, name: String) = financeInput(inputs[index], name)
    fun rate(index: Int, name: String): Int {
        val basisPoints = financeInput(inputs[index], name)
        require(basisPoints in 0..10_000L) { "${name}需在0%至100%之间。" }
        return basisPoints.toInt()
    }
    return SalaryTerms(
        grossCents = money(0, "税前工资"), pensionBaseCents = money(1, "养老基数"),
        medicalBaseCents = money(2, "医疗基数"), unemploymentBaseCents = money(3, "失业基数"),
        fundBaseCents = money(4, "公积金基数"), pensionRateBp = rate(5, "养老比例"),
        medicalRateBp = rate(6, "医疗比例"), unemploymentRateBp = rate(7, "失业比例"),
        fundRateBp = rate(8, "公积金比例"), specialDeductionCents = money(9, "专项附加扣除"),
        deductionsConfirmed = confirmed
    ).also(FinanceValidation::terms)
}

@Composable
private fun TermsFields(
    values: List<String>, confirmed: Boolean, enabled: Boolean,
    onChange: (Int, String) -> Unit, onConfirmed: (Boolean) -> Unit
) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    FinanceTextField(values[0], { onChange(0, it) }, "税前工资（元）", "terms-gross", enabled)
    TextButton(onClick = { advanced = !advanced }, modifier = Modifier.testTag("terms-advanced")) {
        Text(if (advanced) "收起缴费与专项扣除设置" else "高级设置 · 缴费与专项扣除")
    }
    Text(if (confirmed) "专项附加扣除已确认" else "专项附加扣除未确认，个税将标为估算。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (advanced) {
        Text("个人缴费参数", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("仅填写个人承担部分；比例单位为 %，例如 8 表示 8%。", style = MaterialTheme.typography.bodySmall)
        listOf("养老", "医疗", "失业", "公积金").forEachIndexed { index, name ->
            FinanceTextField(values[index + 1], { onChange(index + 1, it) }, "${name}基数（元）", "terms-base-$index", enabled)
            FinanceTextField(values[index + 5], { onChange(index + 5, it) }, "${name}个人比例（%）", "terms-rate-$index", enabled)
        }
        FinanceTextField(values[9], { onChange(9, it) }, "每月专项附加扣除（元）", "terms-special-deduction", enabled)
        FinanceConfirmation(confirmed, onConfirmed, "我已核实本月专项附加扣除（包括确认为 0）", "terms-confirmed", enabled)
    }
}

@Composable
private fun FinanceConfirmation(value: Boolean, onChange: (Boolean) -> Unit, label: String, tag: String, enabled: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(value, onChange, enabled = enabled, modifier = Modifier.testTag(tag))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun PayrollEditor(
    month: String, original: PayrollRecord?, template: SalaryTerms, busy: Boolean, saveError: String,
    onDismiss: () -> Unit, onSave: (PayrollRecord) -> Unit
) {
    val selected = YearMonth.parse(month)
    val initialTerms = original?.terms ?: template
    val context = LocalContext.current
    var net by rememberSaveable(month) { mutableStateOf(original?.actualNetCents?.let(::financeAmount) ?: "") }
    var date by rememberSaveable(month) { mutableStateOf(original?.date ?: minOf(selected.atEndOfMonth(), LocalDate.now()).toString()) }
    var actualTax by rememberSaveable(month) { mutableStateOf(original?.actualTaxCents?.let(::financeAmount) ?: "") }
    var other by rememberSaveable(month) { mutableStateOf(financeAmount(original?.otherDeductionCents ?: 0)) }
    var note by rememberSaveable(month) { mutableStateOf(original?.note ?: "") }
    var terms by rememberSaveable(month) { mutableStateOf(initialTerms.inputs()) }
    var confirmed by rememberSaveable(month) { mutableStateOf(initialTerms.deductionsConfirmed) }
    var error by rememberSaveable(month) { mutableStateOf("") }
    FinanceEditorShell("${selected.year}年${selected.monthValue}月工资", "payroll", busy, error.ifBlank { saveError }, onDismiss, onSave = {
        if (!busy) {
            try {
                val received = financeInput(net, "实际到账")
                val parsedDate = runCatching { LocalDate.parse(date.trim()) }.getOrNull()
                require(parsedDate != null && parsedDate.toString() == date.trim()) { "请填写有效日期，格式为 YYYY-MM-DD。" }
                require(YearMonth.from(parsedDate) == selected) { "到账日期需在发薪月份内。" }
                require(parsedDate <= LocalDate.now()) { "到账日期不能晚于今天。" }
                val record = PayrollRecord(month, parsedDate.toString(), received, parsedTerms(terms, confirmed),
                    actualTaxCents = if (actualTax.isBlank()) null else financeInput(actualTax, "实际已扣个税"),
                    otherDeductionCents = financeInput(other, "其他税后扣款"), note = note.trim())
                FinanceValidation.payroll(record)
                error = ""
                onSave(record)
            } catch (invalid: IllegalArgumentException) { error = invalid.message ?: "请检查输入。" }
        }
    }, saveLabel = if (original == null) "保存工资" else "保存修改") {
        Text("实际到账", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        FinanceTextField(net, { net = it; error = "" }, "＋ 实际到账金额（元）", "payroll-actual-net", !busy,
            supporting = "输入 +123.45 或 123.45；收入只按这笔实际到账统计。")
        Text("每个到账月只有一张工资单，再次保存会更新本月记录。", style = MaterialTheme.typography.bodySmall)
        FinanceTextField(date, { date = it; error = "" }, "到账日期 YYYY-MM-DD", "payroll-date", !busy, decimal = false)
        TextButton(enabled = !busy, modifier = Modifier.testTag("payroll-date-picker"), onClick = {
            val fallback = minOf(selected.atEndOfMonth(), LocalDate.now())
            val current = runCatching { LocalDate.parse(date) }.getOrDefault(fallback)
                .coerceIn(selected.atDay(1), fallback)
            DatePickerDialog(context, { _, year, zeroMonth, day ->
                date = LocalDate.of(year, zeroMonth + 1, day).toString(); error = ""
            }, current.year, current.monthValue - 1, current.dayOfMonth).apply {
                val zone = ZoneId.systemDefault()
                datePicker.minDate = selected.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
                datePicker.maxDate = fallback.atStartOfDay(zone).toInstant().toEpochMilli()
            }.show()
        }) { Text("选择到账日期") }
        HorizontalDivider()
        TermsFields(terms, confirmed, !busy, onChange = { index, value ->
            terms = ArrayList(terms).apply { this[index] = value }; error = ""
        }, onConfirmed = { confirmed = it; error = "" })
        FinanceTextField(actualTax, { actualTax = it; error = "" }, "实际已扣个税（元，可留空）", "payroll-actual-tax", !busy,
            supporting = "以工资条或个税 App 为准；留空时估算，不从到账反推。")
        FinanceTextField(other, { other = it; error = "" }, "其他税后扣款（元）", "payroll-other-deduction", !busy)
        OutlinedTextField(note, { note = it; error = "" }, enabled = !busy,
            label = { Text("备注 / 工资归属（最多500字）") }, minLines = 2,
            modifier = Modifier.fillMaxWidth().testTag("payroll-note"))
        Text("这里的参数只作用于本条工资。默认模板更改不会改写已保存的工资单。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun SalaryProfileEditor(
    original: SalaryProfile, busy: Boolean, saveError: String, onDismiss: () -> Unit, onSave: (SalaryProfile) -> Unit
) {
    var terms by rememberSaveable { mutableStateOf(original.terms.inputs()) }
    var confirmed by rememberSaveable { mutableStateOf(original.terms.deductionsConfirmed) }
    var error by rememberSaveable { mutableStateOf("") }
    FinanceEditorShell("默认工资模板", "profile", busy, error.ifBlank { saveError }, onDismiss, onSave = {
        if (!busy) try {
            val profile = original.copy(terms = parsedTerms(terms, confirmed))
            FinanceValidation.profile(profile); error = ""; onSave(profile)
        } catch (invalid: IllegalArgumentException) { error = invalid.message ?: "请检查输入。" }
    }) {
        Text("用于之后新建的工资单，历史工资保留各自快照。默认税前 17,000 元、缴费基数 13,300 元，来自你的个人模板。",
            style = MaterialTheme.typography.bodyMedium)
        TermsFields(terms, confirmed, !busy, onChange = { index, value ->
            terms = ArrayList(terms).apply { this[index] = value }; error = ""
        }, onConfirmed = { confirmed = it; error = "" })
        // Show the stored breakdown without attempting to parse an incomplete draft.
        HorizontalDivider()
        Text("当前已保存的个人缴费", style = MaterialTheme.typography.titleMedium)
        FinanceContributionLines(original.terms)
        Text("保存后按新参数计算；不估填单位缴费比例。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun TaxOpeningEditor(
    original: TaxOpening, busy: Boolean, saveError: String, onDismiss: () -> Unit, onSave: (TaxOpening) -> Unit
) {
    var start by rememberSaveable(original.year) { mutableStateOf(original.startMonth.toString()) }
    var taxable by rememberSaveable(original.year) { mutableStateOf(financeAmount(original.taxableCents)) }
    var paid by rememberSaveable(original.year) { mutableStateOf(financeAmount(original.paidTaxCents)) }
    var confirmed by rememberSaveable(original.year) { mutableStateOf(original.confirmed) }
    var error by rememberSaveable(original.year) { mutableStateOf("") }
    FinanceEditorShell("${original.year}年计税期初", "opening", busy, error.ifBlank { saveError }, onDismiss, onSave = {
        if (!busy) try {
            val startMonth = start.trim().toIntOrNull()
            require(startMonth != null && startMonth in 1..12) { "起算月份请填写1至12。" }
            val opening = TaxOpening(original.year, startMonth, financeInput(taxable, "此前累计税基", signed = true),
                financeInput(paid, "此前累计已扣个税"), confirmed)
            FinanceValidation.opening(opening); error = ""; onSave(opening)
        } catch (invalid: IllegalArgumentException) { error = invalid.message ?: "请检查输入。" }
    }) {
        Text("从工资条或个税 App 核对起算月之前的累计应纳税所得额和已扣税额。累计税基不是累计税前工资，也不是实际到账之和。")
        Text("年中开始记录或历史有缺月时可设置期初。没有核实资料时请保留未确认，税额继续显示估算。", style = MaterialTheme.typography.bodySmall)
        FinanceTextField(start, { start = it; error = "" }, "开始连续记录的月份（1–12）", "opening-start-month", !busy, decimal = false)
        FinanceTextField(taxable, { taxable = it; error = "" }, "此前累计应纳税所得额（元，可为负）", "opening-taxable", !busy, decimal = false,
            supporting = "支持 -123.45；从1月起算时，两项累计金额均为0。")
        FinanceTextField(paid, { paid = it; error = "" }, "此前累计已扣个税（元）", "opening-paid-tax", !busy)
        FinanceConfirmation(confirmed, { confirmed = it; error = "" }, "我已按工资条或个税 App 核实以上期初资料", "opening-confirmed", !busy)
        Text("期初只影响本年度。起算月之后的缺月仍会提示估算，保存期初不会补造工资记录。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun BudgetEditor(
    month: String, category: String, original: MonthlyBudget?, busy: Boolean, saveError: String,
    onDismiss: () -> Unit, onSave: (MonthlyBudget?) -> Unit
) {
    var amount by rememberSaveable(month, category) { mutableStateOf(original?.limitCents?.let(::financeAmount) ?: "") }
    var error by rememberSaveable(month, category) { mutableStateOf("") }
    FinanceEditorShell("${category.ifEmpty { "总预算" }} · $month", "budget", busy, error.ifBlank { saveError }, onDismiss, onSave = {
        if (!busy) try {
            val budget = if (amount.isBlank()) null else MonthlyBudget(month, category, financeInput(amount, "预算上限"))
            budget?.let(FinanceValidation::budget); error = ""; onSave(budget)
        } catch (invalid: IllegalArgumentException) { error = invalid.message ?: "请检查输入。" }
    }) {
        Text("总预算与各分类预算分别保存，不自动相加或互相覆盖。")
        FinanceTextField(amount, { amount = it; error = "" }, "预算上限（元）", "budget-limit", !busy,
            supporting = "留空并保存＝取消设置；输入0＝明确设置0元预算。")
        Text("只比较这个月份的已入账消费，待确认账目不占用预算。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun FinanceMonthEditor(original: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var month by rememberSaveable { mutableStateOf(original) }
    var error by rememberSaveable { mutableStateOf("") }
    FinanceEditorShell("选择年月", "month", false, error, onDismiss, onSave = {
        val parsed = runCatching { FinanceValidation.month(month.trim()) }.getOrNull()
        if (parsed == null) error = "请输入有效年月 YYYY-MM，年份为2000至2100。" else onSave(parsed.toString())
    }, saveLabel = "查看此月") {
        FinanceTextField(month, { month = it; error = "" }, "年月 YYYY-MM", "month-input", true, decimal = false)
        Text("可直接跳至往年月份；未来月份可设置预算。", style = MaterialTheme.typography.bodySmall)
    }
}
