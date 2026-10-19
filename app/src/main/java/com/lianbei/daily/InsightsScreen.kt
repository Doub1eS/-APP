package com.lianbei.daily

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

@Composable fun InsightsScreen(all: List<DailyRecord>, onEdit: (DailyRecord)->Unit, onDelete: (DailyRecord)->Unit, foodEntries:List<FoodEntry> = emptyList()) {
    var kindName by rememberSaveable { mutableStateOf(Kind.EXPENSE.name) }
    val kind=Kind.valueOf(kindName)
    var month by rememberSaveable { mutableStateOf(false) }
    var anchor by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var selectedDay by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedGroup by rememberSaveable { mutableStateOf<String?>(null) }
    val start=periodStart(LocalDate.parse(anchor),month)
    val end=minOf(periodEnd(LocalDate.parse(anchor),month),LocalDate.now())
    val data=remember(all,kind,start,end) { insightData(all,kind,start,end) }
    val scoped=remember(data,selectedDay,kind) {
        selectedDay?.let { val day=LocalDate.parse(it); insightData(data.records,kind,day,day) } ?: data
    }
    fun selectDay(day:String) { selectedDay=if(selectedDay==day) null else day; selectedGroup=null }
    fun clearFilters() { selectedDay=null; selectedGroup=null }
    LazyColumn(Modifier.fillMaxSize().testTag("insights"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item { PageTitle("回看", "把零散的记录，连成生活的轮廓。") }
        item { Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Kind.entries.forEach { k -> FilterChip(selected=k==kind,onClick={kindName=k.name;clearFilters()},label={Text(k.label)}) }
        } }
        item { Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
            Row { FilterChip(selected=!month,onClick={month=false;clearFilters()},label={Text("周")}); Spacer(Modifier.width(8.dp)); FilterChip(selected=month,onClick={month=true;clearFilters()},label={Text("月")}) }
            TextButton(onClick={anchor=LocalDate.now().toString();clearFilters()}) { Text("回到本期") }
        }
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                TextButton(onClick={anchor=(if(month) start.minusMonths(1) else start.minusWeeks(1)).toString();clearFilters()}){Text("上一期")}
                Text("${start.monthValue}/${start.dayOfMonth} — ${end.monthValue}/${end.dayOfMonth}",style=MaterialTheme.typography.titleSmall)
                TextButton(enabled=periodEnd(LocalDate.parse(anchor),month)<LocalDate.now(),onClick={anchor=(if(month) start.plusMonths(1) else start.plusWeeks(1)).toString();clearFilters()}){Text("下一期")}
            }
            Text("${start.year} 年 · ${if(month) "按自然月" else "周一至周日"}",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { Card(colors=CardDefaults.cardColors(containerColor=kind.tint()),modifier=Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(selectedDay?.let { "$it · 单日" } ?: "本期汇总",color=Color.White.copy(alpha=.85f))
                Text(when(kind){Kind.EXPENSE->"已记录消费";Kind.SLEEP->"有记录日 · 平均睡眠";Kind.TRAINING->"已记录训练时长";else->"${kind.label}记录"},color=Color.White.copy(alpha=.85f))
                Text(when(kind){Kind.EXPENSE->"¥${RecordRules.money(scoped.total)}";Kind.SLEEP->scoped.averageRecordedDay?.let { "${it/60}小时 ${it%60}分" } ?: "暂无记录";Kind.TRAINING->"${scoped.total} 分钟";else->"${scoped.records.size} 条"},fontSize=32.sp,fontWeight=FontWeight.Bold,color=Color.White)
                Text("${scoped.records.size} 条记录 · ${scoped.recordedDays} 天留下足迹",color=Color.White)
                if(kind==Kind.TRAINING && scoped.missingValues>0) Text("其中 ${scoped.missingValues} 次未填写时长",color=Color.White)
            }
        } }
        if(data.records.isEmpty()) item { Panel { Text("这一段日子，还没有${kind.label}记录",style=MaterialTheme.typography.titleMedium);Text("记录之后，这里会出现你的趋势和分布。",color=MaterialTheme.colorScheme.onSurfaceVariant) } }
        else {
            item { Panel {
                Text(if(kind in listOf(Kind.FOOD,Kind.STATE,Kind.NOTE)) "记录日历" else "每日趋势",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                Text(if(kind==Kind.FOOD || kind==Kind.SLEEP) "点击日期联动汇总与明细 · 未记录不等于零" else "点击日期联动汇总、分布与明细 · 未记录不等于零",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(kind in listOf(Kind.FOOD,Kind.STATE,Kind.NOTE)) RecordCalendar(data.days,kind,selectedDay,data.records) { selectDay(it) }
                else DailyBars(data.days,kind,selectedDay) { selectDay(it) }
                if(kind==Kind.STATE) Text("日历显示当天最近的心情，点击可查看当天全部记录。",style=MaterialTheme.typography.bodySmall)
                if(kind==Kind.SLEEP) Text("同日多次睡眠累计；平均值仅计算有时长的日期。",style=MaterialTheme.typography.bodySmall)
            } }
            if(kind!=Kind.SLEEP && kind!=Kind.FOOD) item { Panel {
                Text(when(kind){Kind.EXPENSE->"消费去向";Kind.TRAINING->"训练项目 · 次数";Kind.FOOD->"餐次分布";Kind.STATE->"心情分布";else->"记录类别"},style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                Text(selectedDay?.let { "$it · 单日" } ?: "本期全部日期",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(scoped.records.isEmpty()) Text("当天暂无记录")
                if(kind==Kind.EXPENSE) ExpenseRing(scoped.groups)
                scoped.groups.forEach { g ->
                    val total=scoped.groups.sumOf { it.value }.coerceAtLeast(1)
                    Column(Modifier.fillMaxWidth().testTag("group-${g.label}").clickable { selectedGroup=if(selectedGroup==g.label) null else g.label }.padding(vertical=8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            if(kind==Kind.EXPENSE) Box(Modifier.padding(end=8.dp,top=5.dp).size(10.dp).background(expenseTint(g.label),RoundedCornerShape(3.dp)))
                            Text((if(selectedGroup==g.label) "✓ " else "")+g.label,Modifier.weight(1f))
                            Text(if(kind==Kind.EXPENSE) "¥${RecordRules.money(g.value)} · ${String.format(java.util.Locale.ROOT,"%.1f",g.value.toDouble()*100/total)}%" else "${g.value} 次",color=kind.tint())
                        }
                        LinearProgressIndicator(progress={g.value.toFloat()/total},modifier=Modifier.fillMaxWidth().height(5.dp),color=if(kind==Kind.EXPENSE) expenseTint(g.label) else kind.tint(),trackColor=kind.tint().copy(alpha=.08f))
                    }
                }
            } }
            if(kind==Kind.STATE) item { Panel {
                Text("精力分布",style=MaterialTheme.typography.titleMedium)
                val energy=scoped.records.filter { it.detail in listOf("精力充沛","精力一般","有些疲惫") }.groupingBy { it.detail }.eachCount()
                if(energy.isEmpty()) Text("暂无明确的精力选项记录")
                energy.forEach { (label,count) ->
                    Text("$label  ·  $count 次")
                    LinearProgressIndicator(progress={count.toFloat()/energy.values.sum()},modifier=Modifier.fillMaxWidth(),color=kind.tint(),trackColor=kind.tint().copy(alpha=.10f))
                }
                Text("只统计明确选择的精力文字。",style=MaterialTheme.typography.bodySmall)
            } }
        }
        item { Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
            Text("记录明细",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
            if(selectedDay!=null || selectedGroup!=null) TextButton(onClick={clearFilters()}){Text("清除筛选")}
        }
            if(selectedDay!=null || selectedGroup!=null) Text(listOfNotNull(selectedDay,selectedGroup).joinToString(" · "),color=kind.tint())
        }
        val rows=data.records.filter { (selectedDay==null || it.date==selectedDay) && (selectedGroup==null || recordGroup(it,kind)==selectedGroup) }
        if(rows.isEmpty() && data.records.isNotEmpty()) item { Text("该筛选下没有记录。") }
        items(rows,key={it.id}) { RecordCard(it,{onEdit(it)},{onDelete(it)},foodEntries.find {f->f.foodId==it.id},all) }
    }
}

@Composable private fun DailyBars(days: List<DayValue>,kind: Kind,selected: String?,onSelect:(String)->Unit) {
    val max=days.mapNotNull { it.value }.maxOrNull()?.coerceAtLeast(1) ?: 1
    Text(when(kind) {Kind.EXPENSE->"金额 / 元";Kind.SLEEP->"睡眠 / 小时与分钟";else->"训练 / 分钟"},style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.Bottom) {
        days.forEach { d ->
            val label=d.value?.let { if(kind==Kind.EXPENSE) RecordRules.money(it) else if(kind==Kind.SLEEP) "${it/60}h${it%60}" else "$it" } ?: if(d.count>0) "未填时长" else "—"
            Column(Modifier.width(64.dp).clickable {onSelect(d.date.toString())}.semantics {contentDescription="${d.date}，${d.value?.let { "$label ${if(kind==Kind.EXPENSE) "元" else if(kind==Kind.TRAINING) "分钟" else "小时分钟"}" } ?: if(d.count>0) "已记录${d.count}次，未填写时长" else "未记录"}"},horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(label,fontSize=12.sp,color=kind.tint())
                Box(Modifier.height(110.dp).fillMaxWidth(),contentAlignment=Alignment.BottomCenter) {
                    Box(Modifier.width(22.dp).height(if(d.value==null) 3.dp else (110f*d.value/max).coerceAtLeast(3f).dp).background(kind.tint().copy(alpha=if(selected==null || selected==d.date.toString()) 1f else .25f),RoundedCornerShape(6.dp)))
                }
                Text("${d.date.dayOfMonth}日",fontSize=12.sp)
            }
        }
    }
}
@Composable private fun RecordCalendar(days: List<DayValue>,kind: Kind,selected: String?,records: List<DailyRecord>,onSelect:(String)->Unit) {
    val cells=List<DayValue?>(days.firstOrNull()?.date?.dayOfWeek?.value?.minus(1) ?: 0){null}+days
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val calendarWidth=maxWidth
    Row {
    Column(Modifier.width(calendarWidth),verticalArrangement=Arrangement.spacedBy(8.dp)) {
    Row(Modifier.fillMaxWidth()) { listOf("一","二","三","四","五","六","日").forEach { Text(it,Modifier.weight(1f),color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.labelSmall) } }
    cells.chunked(7).forEach { week -> Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
        (week+List<DayValue?>(7-week.size){null}).forEach { d ->
            if(d==null) Spacer(Modifier.weight(1f)) else Column(Modifier.weight(1f).heightIn(min=52.dp).background(if(d.date.toString()==selected) kind.tint().copy(alpha=.25f) else if(d.count>0) kind.tint().copy(alpha=.10f) else Color.Transparent,RoundedCornerShape(10.dp)).clickable {onSelect(d.date.toString())}.semantics { contentDescription="${d.date}，${d.count}条记录" }.padding(vertical=6.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Text("${d.date.dayOfMonth}",style=MaterialTheme.typography.labelMedium)
                val mood=records.firstOrNull {it.date==d.date.toString()}?.let {recordGroup(it,kind)}
                Text(if(d.count==0) "—" else if(kind==Kind.STATE) mood.orEmpty() else "${d.count}条",fontSize=10.sp,color=kind.tint())
            }
        }
    } }
    }
    }
    }
}
@Composable private fun ExpenseRing(groups: List<GroupValue>) {
    val total=groups.sumOf {it.value}.coerceAtLeast(1)
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(20.dp)) {
        Canvas(Modifier.size(112.dp).padding(10.dp).semantics {contentDescription="消费分类占比，数值见下方列表"}) {
            var start=-90f
            groups.forEach { g -> val sweep=360f*g.value/total
                drawArc(expenseTint(g.label),start,(sweep-.5f).coerceAtLeast(.01f),false,style=Stroke(18.dp.toPx()));start+=sweep
            }
        }
        Column {Text("${groups.size} 个分类",style=MaterialTheme.typography.titleMedium);Text("点击下方分类查看明细",style=MaterialTheme.typography.bodySmall)}
    }
}
