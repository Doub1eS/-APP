package com.lianbei.daily

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

internal fun financeAmount(cents: Long): String = BigDecimal.valueOf(cents, 2).toPlainString()
internal fun financeCash(cents: Long): String = "¥${financeAmount(cents)}"

private data class FinanceSnapshot(
    val profile: SalaryProfile,
    val payrolls: List<PayrollRecord>,
    val openings: List<TaxOpening>,
    val budgets: List<MonthlyBudget>,
    val records: List<DailyRecord>
)
private data class FinanceLoad(val data: FinanceSnapshot? = null, val error: String = "")

/** Finance flows share one loading gate: an empty database is different from an unread database. */
@Composable
fun FinanceScreen(dao: RecordDao, onBack: () -> Unit) {
    var retry by remember { mutableIntStateOf(0) }
    val source = remember(dao, retry) {
        combine(dao.salaryProfile(), dao.payrolls(), dao.taxOpenings(), dao.budgets(), dao.observeAll()) {
                profile, payrolls, openings, budgets, records ->
            FinanceLoad(FinanceSnapshot(profile ?: SalaryProfile(), payrolls, openings, budgets, records))
        }.catch { error ->
            if (error is CancellationException) throw error
            emit(FinanceLoad(error = "财务数据加载失败，请重试。"))
        }
    }
    val loaded by source.collectAsStateWithLifecycle(initialValue = FinanceLoad())
    val data = loaded.data
    val scope = rememberCoroutineScope()
    var monthText by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    val month = YearMonth.parse(monthText)
    var editor by rememberSaveable { mutableStateOf("") }
    var budgetCategory by rememberSaveable { mutableStateOf("") }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf("") }
    val payroll = data?.payrolls?.find { it.month == monthText }

    fun open(kind: String) { if (!busy && data != null) { saveError = ""; editor = kind } }
    fun mutate(action: suspend () -> Unit) {
        if (busy || data == null) return
        busy = true
        saveError = ""
        scope.launch {
            try {
                action()
                editor = ""
                deleting = false
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                saveError = "保存失败，输入已保留，请重试。"
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .testTag("finance-screen")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, enabled = !busy, modifier = Modifier.testTag("finance-back")) { Text("返回") }
            Text("工资与预算", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        if (data == null) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (loaded.error.isEmpty()) {
                    CircularProgressIndicator(Modifier.testTag("finance-loading"))
                    Text("正在加载工资与预算…")
                } else {
                    Text(loaded.error, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { retry++ }) { Text("重新加载") }
                }
            }
        } else {
            val monthly = remember(data, monthText) {
                FinanceRules.monthly(monthText, data.records, data.payrolls, data.budgets)
            }
            val calculations = remember(data.payrolls, data.openings) {
                FinanceRules.calculatePayrolls(data.payrolls, data.openings)
            }
            val calculation = calculations.find { it.record.month == monthText }
            Box(Modifier.weight(1f).testTag("finance-loaded")) {
                LazyColumn(Modifier.fillMaxSize().testTag("finance-list"), contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item {
                        Text("每一份收入，都有清楚的去向。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { monthText = month.minusMonths(1).toString() },
                                enabled = !busy && month > YearMonth.of(2000, 1), modifier = Modifier.testTag("finance-previous-month")) { Text("上月") }
                            TextButton(onClick = { open("month") }, modifier = Modifier.testTag("finance-month")) {
                                Text("${month.year}年${month.monthValue}月", fontWeight = FontWeight.Bold)
                            }
                            TextButton(onClick = { monthText = month.plusMonths(1).toString() },
                                enabled = !busy && month < YearMonth.of(2100, 12), modifier = Modifier.testTag("finance-next-month")) { Text("下月") }
                        }
                    }
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Text("本月收支", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FinanceMetric("实际工资收入", monthly.incomeCents, Modifier.weight(1f), "finance-income")
                                    FinanceMetric("已入账支出", monthly.expenseCents, Modifier.weight(1f), "finance-expense")
                                    FinanceMetric("结余", monthly.balanceCents, Modifier.weight(1f), "finance-balance")
                                }
                                Text("按到账与消费日期统计；待确认消费不计入。", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    item {
                        Button(onClick = { open("payroll") }, enabled = !busy && month <= YearMonth.now(),
                            modifier = Modifier.fillMaxWidth().testTag("payroll-open")) {
                            Text(if (payroll == null) "＋ 记录本月工资" else "编辑本月工资")
                        }
                        if (month > YearMonth.now()) Text("未来月份可先规划预算，到账后再记录工资。", style = MaterialTheme.typography.bodySmall)
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { open("profile") }, enabled = !busy,
                                modifier = Modifier.weight(1f).testTag("profile-open")) { Text("工资模板") }
                            OutlinedButton(onClick = { open("opening") }, enabled = !busy,
                                modifier = Modifier.weight(1f).testTag("opening-open")) { Text("${month.year}年期初") }
                        }
                    }
                    item {
                        if (calculation != null) PayrollDetail(calculation, onEdit = { open("payroll") }, onDelete = {
                            saveError = ""; deleting = true
                        }, enabled = !busy)
                        else Panel {
                            Text("本月还没有工资记录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("填写实际到账金额后计入收入，每个到账月保留一张工资单。", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    item {
                        Text("月度预算", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("总预算与分类预算独立比较；未设置表示不限额，0 元表示本月不计划支出。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(listOf("") + ExpenseCategories.groups.keys, key = { "budget-$it" }) { category ->
                        val budget = data.budgets.find { it.month == monthText && it.category == category }
                        val spent = if (category.isEmpty()) monthly.expenseCents else monthly.categoryExpenses[category] ?: 0L
                        BudgetCard(category, budget, spent, enabled = !busy) {
                            budgetCategory = category; open("budget")
                        }
                    }
                    item {
                        Text("个税为居民个人工资薪金累计预扣估算，不含年终奖单独计税、多雇主汇算等情形。实际到账直接记为收入，社保、公积金与个税不会再次计作消费。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    // Keep drafts saveable inside their editor; emissions and background/foreground transitions never reset inputs.
    if (data != null) {
        when (editor) {
            "payroll" -> PayrollEditor(monthText, payroll, data.profile.terms, busy, saveError,
                onDismiss = { editor = "" }, onSave = { record -> mutate { dao.savePayroll(record) } })
            "profile" -> SalaryProfileEditor(data.profile, busy, saveError,
                onDismiss = { editor = "" }, onSave = { profile -> mutate { dao.saveSalaryProfile(profile) } })
            "opening" -> TaxOpeningEditor(data.openings.find { it.year == month.year } ?: TaxOpening(month.year),
                busy, saveError, onDismiss = { editor = "" }, onSave = { opening -> mutate { dao.saveTaxOpening(opening) } })
            "budget" -> BudgetEditor(monthText, budgetCategory,
                data.budgets.find { it.month == monthText && it.category == budgetCategory }, busy, saveError,
                onDismiss = { editor = "" }, onSave = { budget ->
                    mutate { if (budget == null) dao.deleteBudget(monthText, budgetCategory) else dao.saveBudget(budget) }
                })
            "month" -> FinanceMonthEditor(monthText, onDismiss = { editor = "" }, onSave = { monthText = it; editor = "" })
        }
        if (deleting) AlertDialog(onDismissRequest = { if (!busy) deleting = false },
            title = { Text("删除 ${month.year}年${month.monthValue}月工资？") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("实际工资收入将移除，后续月份个税估算会重新计算。消费和预算仍保留。")
                if (saveError.isNotEmpty()) Text(saveError, color = MaterialTheme.colorScheme.error)
            } }, confirmButton = {
                TextButton(onClick = { mutate { dao.deletePayroll(monthText) } }, enabled = !busy,
                    modifier = Modifier.testTag("payroll-delete-confirm")) { Text(if (busy) "正在删除…" else "确认删除") }
            }, dismissButton = { TextButton(onClick = { deleting = false }, enabled = !busy) { Text("取消") } })
    }
}

@Composable
private fun FinanceMetric(label: String, cents: Long, modifier: Modifier, tag: String) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(financeCash(cents), modifier = Modifier.testTag(tag), style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold, color = if (cents < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun PayrollDetail(value: PayrollCalculation, onEdit: () -> Unit, onDelete: () -> Unit, enabled: Boolean) {
    val row = value.record
    var expanded by rememberSaveable(row.month) { mutableStateOf(false) }
    Panel(Modifier.testTag("payroll-detail")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("本月工资单", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("到账 ${row.date}", style = MaterialTheme.typography.bodySmall)
            }
            Text(if (value.estimated) "待核对 · 估算" else "资料已确认", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
        }
        Text("实际到账  ${financeCash(row.actualNetCents)}", style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        FinanceDetailLine("税前工资", financeCash(row.terms.grossCents))
        FinanceDetailLine("个人社保合计", financeCash(value.contributions.social))
        FinanceDetailLine("个人公积金", financeCash(value.contributions.fund))
        row.actualTaxCents?.let {
            FinanceDetailLine("本月个税（实际已扣）", financeCash(it), Modifier.testTag("payroll-actual-tax-value"))
        }
        FinanceDetailLine("本月个税（估算）", financeCash(value.taxCents), Modifier.testTag("payroll-estimated-tax-value"))
        FinanceDetailLine("其他税后扣款", financeCash(row.otherDeductionCents))
        FinanceDetailLine("预计税后到账", financeCash(value.estimatedNetCents))
        FinanceDetailLine("到账差额（实际 − 预计）", financeCash(value.differenceCents))
        Text("预计到账按估算个税计算；已填的实际扣税用于后续月份累计校准。", style = MaterialTheme.typography.bodySmall)
        if (row.actualTaxCents == null) Text("实际扣税未填写；不会从到账金额倒推个税。", style = MaterialTheme.typography.bodySmall)
        value.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (row.note.isNotBlank()) Text(row.note)
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("payroll-details-toggle")) {
            Text(if (expanded) "收起缴费与计税明细" else "查看缴费与计税明细")
        }
        if (expanded) {
            HorizontalDivider()
            Text("个人缴费 · 本条工资快照", fontWeight = FontWeight.SemiBold)
            FinanceContributionLines(row.terms)
            FinanceDetailLine("每月专项附加扣除", financeCash(row.terms.specialDeductionCents))
            Text(if (row.terms.deductionsConfirmed) "专项附加扣除已确认" else "专项附加扣除未确认", style = MaterialTheme.typography.bodySmall)
            FinanceDetailLine("累计应纳税所得额", financeCash(value.cumulativeTaxableCents))
            FinanceDetailLine("累计应纳税额", financeCash(value.cumulativeTaxCents))
            Text("修改默认模板只用于之后新建的工资单。此处保留记录当时的参数。", style = MaterialTheme.typography.bodySmall)
        }
        Row {
            TextButton(onClick = onEdit, enabled = enabled, modifier = Modifier.testTag("payroll-edit")) { Text("编辑") }
            TextButton(onClick = onDelete, enabled = enabled, modifier = Modifier.testTag("payroll-delete")) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
internal fun FinanceDetailLine(label: String, amount: String, amountModifier: Modifier = Modifier) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(amount, modifier = amountModifier, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun FinanceContributionLines(terms: SalaryTerms) {
    val contributions = FinanceRules.contributions(terms)
    listOf(
        Triple("养老", terms.pensionBaseCents to terms.pensionRateBp, contributions.pension),
        Triple("医疗", terms.medicalBaseCents to terms.medicalRateBp, contributions.medical),
        Triple("失业", terms.unemploymentBaseCents to terms.unemploymentRateBp, contributions.unemployment),
        Triple("公积金", terms.fundBaseCents to terms.fundRateBp, contributions.fund)
    ).forEach { (label, basis, amount) ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FinanceDetailLine(label, financeCash(amount))
            Text("基数 ${financeCash(basis.first)} × ${financeAmount(basis.second.toLong())}%",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    FinanceDetailLine("个人社保合计", financeCash(contributions.social))
    FinanceDetailLine("个人社保＋公积金", financeCash(contributions.total))
}

@Composable
private fun BudgetCard(category: String, budget: MonthlyBudget?, spent: Long, enabled: Boolean, onEdit: () -> Unit) {
    val name = category.ifEmpty { "总预算" }
    val remaining = budget?.let { it.limitCents - spent }
    val exceeded = remaining != null && remaining < 0
    Panel(Modifier.testTag("budget-card-${category.ifEmpty { "total" }}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            TextButton(onClick = onEdit, enabled = enabled,
                modifier = Modifier.testTag("budget-open-${category.ifEmpty { "total" }}")) { Text(if (budget == null) "设置" else "编辑") }
        }
        FinanceDetailLine("已入账支出", financeCash(spent))
        if (budget == null) Text("未设置预算", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else {
            val progress = if (budget.limitCents == 0L) { if (spent > 0) 1f else 0f }
                else (spent.toDouble() / budget.limitCents).coerceIn(0.0, 1.0).toFloat()
            FinanceDetailLine("预算上限", financeCash(budget.limitCents))
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(6.dp),
                color = if (exceeded) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Text(if (exceeded) "已超支 ${financeCash(-remaining!!)}" else "剩余 ${financeCash(remaining!!)}",
                color = if (exceeded) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            if (budget.limitCents == 0L) Text("已设置 0 元预算", style = MaterialTheme.typography.bodySmall)
        }
    }
}
