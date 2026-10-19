package com.lianbei.daily

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

fun Kind.tint(): Color = when(this) {
    Kind.FOOD -> Color(0xFF9B531E); Kind.SLEEP -> Color(0xFF6153A3)
    Kind.TRAINING -> Color(0xFF24634C); Kind.EXPENSE -> Color(0xFF315E9D)
    Kind.STATE -> Color(0xFFAA4766); Kind.NOTE -> Color(0xFF796046)
}
@Composable fun LianbeiTheme(content: @Composable () -> Unit) {
    val context=LocalContext.current
    SideEffect {
        val activity=generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }.filterIsInstance<android.app.Activity>().firstOrNull()
        activity?.let { WindowCompat.getInsetsController(it.window,it.window.decorView).isAppearanceLightStatusBars=true }
    }
    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF24634C), onPrimary = Color.White,
        primaryContainer=Color(0xFFDDECDD),onPrimaryContainer=Color(0xFF153D2C),
        secondaryContainer=Color(0xFFDDECDD),onSecondaryContainer=Color(0xFF153D2C),
        secondary = Color(0xFF5C6D62), background = Color(0xFFF7F8F2), surface = Color.White,
        surfaceVariant = Color(0xFFEAF0E7), onSurface = Color(0xFF202B25), onBackground = Color(0xFF202B25),
        onSurfaceVariant = Color(0xFF536257), outline = Color(0xFF78867B)),
        shapes = Shapes(extraSmall=RoundedCornerShape(10.dp),small=RoundedCornerShape(12.dp),medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(24.dp)), content = content)
}
@Composable fun NavMark(index:Int) {
    val color=MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(24.dp)) {
        val w=size.width; val h=size.height; val stroke=2.dp.toPx()
        when(index) {
            0 -> { drawCircle(color,w*.37f,Offset(w*.5f,h*.5f),style=Stroke(stroke));drawLine(color,Offset(w*.5f,h*.28f),Offset(w*.5f,h*.52f),stroke);drawLine(color,Offset(w*.5f,h*.52f),Offset(w*.68f,h*.6f),stroke) }
            1 -> {
                drawRoundRect(color,Offset(w*.15f,h*.2f),Size(w*.7f,h*.65f),androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),style=Stroke(stroke))
                drawLine(color,Offset(w*.15f,h*.4f),Offset(w*.85f,h*.4f),stroke)
                listOf(.33f,.67f).forEach { drawLine(color,Offset(w*it,h*.1f),Offset(w*it,h*.3f),stroke) }
            }
            2 -> {listOf(.3f,.65f,.45f).forEachIndexed { i,v -> drawLine(color,Offset(w*(.22f+i*.28f),h*.85f),Offset(w*(.22f+i*.28f),h*(.85f-v)),stroke*2) } }
            else -> {drawCircle(color,w*.18f,Offset(w*.5f,h*.3f),style=Stroke(stroke));drawArc(color,180f,180f,false,Offset(w*.15f,h*.55f),Size(w*.7f,h*.6f),style=Stroke(stroke))}
        }
    }
}
@Composable fun KindMark(kind: Kind, modifier: Modifier = Modifier) {
    val color = kind.tint()
    Box(modifier.size(38.dp).background(color.copy(alpha=.10f),RoundedCornerShape(12.dp)), contentAlignment=Alignment.Center) {
        Canvas(Modifier.size(22.dp)) {
            val w=size.width; val h=size.height; val s=Stroke(2.dp.toPx())
            when(kind) {
                Kind.FOOD -> { drawCircle(color,w*.30f,Offset(w*.6f,h*.5f),style=s); drawLine(color,Offset(w*.08f,h*.1f),Offset(w*.08f,h*.9f),s.width); drawLine(color,Offset(w*.18f,h*.1f),Offset(w*.18f,h*.4f),s.width) }
                Kind.SLEEP -> { drawArc(color,45f,270f,false,Offset(w*.1f,h*.1f),Size(w*.8f,h*.8f),style=s); drawLine(color,Offset(w*.78f,h*.2f),Offset(w*.9f,h*.2f),s.width) }
                Kind.TRAINING -> { drawLine(color,Offset(w*.15f,h*.5f),Offset(w*.85f,h*.5f),s.width); listOf(.15f,.3f,.7f,.85f).forEach { drawLine(color,Offset(w*it,h*.25f),Offset(w*it,h*.75f),s.width) } }
                Kind.EXPENSE -> { drawRoundRect(color,Offset(w*.08f,h*.22f),Size(w*.84f,h*.6f),androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),style=s); drawLine(color,Offset(w*.1f,h*.4f),Offset(w*.9f,h*.4f),s.width) }
                Kind.STATE -> { drawCircle(color,w*.4f,Offset(w*.5f,h*.5f),style=s); drawCircle(color,w*.035f,Offset(w*.35f,h*.4f)); drawCircle(color,w*.035f,Offset(w*.65f,h*.4f)); drawArc(color,0f,180f,false,Offset(w*.3f,h*.45f),Size(w*.4f,h*.25f),style=s) }
                Kind.NOTE -> { drawRoundRect(color,Offset(w*.2f,h*.08f),Size(w*.6f,h*.84f),androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),style=s); listOf(.35f,.55f,.75f).forEach { drawLine(color,Offset(w*.35f,h*it),Offset(w*.65f,h*it),s.width) } }
            }
        }
    }
}
@Composable fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth(), colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(20.dp), verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
    }
}
@Composable fun PageTitle(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
    Text(title,style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold)
    Text(subtitle,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable fun RecordCard(record: DailyRecord, onEdit: () -> Unit, onDelete: () -> Unit, food:FoodEntry?=null, all:List<DailyRecord> = emptyList()) {
    val kind=Kind.valueOf(record.kind)
    Panel {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            if(kind==Kind.EXPENSE) ExpenseMark(record.title) else KindMark(kind)
            Column(Modifier.weight(1f)) {
                Text("${kind.label} · ${record.date} · ${record.time}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if(kind==Kind.SLEEP && record.title.startsWith("睡了")) "睡眠记录" else if(kind==Kind.EXPENSE) ExpenseCategories.main(record.title) else record.title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
            }
        }
        if(kind==Kind.EXPENSE && record.extra.isNotBlank()) Text(record.extra,style=MaterialTheme.typography.titleSmall,color=expenseTint(record.title))
        if(record.kind==Kind.EXPENSE.name) Text("¥${RecordRules.money(record.value ?: 0)}",fontSize=28.sp,fontWeight=FontWeight.Bold,color=expenseTint(record.title))
        if(kind==Kind.TRAINING) {
            Surface(color=kind.tint().copy(alpha=.08f),shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text("训练时长",style=MaterialTheme.typography.labelMedium,color=kind.tint())
                    Text(record.value?.let { "$it 分钟" } ?: "未填写时长",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold,color=kind.tint())
                }
            }
        }
        if(kind==Kind.SLEEP) {
            Text(record.value?.let { "${it/60} 小时 ${it%60} 分钟" } ?: "未填写时长",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold,color=kind.tint())
            val times=record.extra.split("|")
            if(times.size==2) Surface(color=kind.tint().copy(alpha=.08f),shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f)) { Text(if(runCatching { java.time.LocalTime.parse(times[0]) > java.time.LocalTime.parse(times[1]) }.getOrDefault(false)) "前一天入睡" else "当天入睡",style=MaterialTheme.typography.labelMedium); Text(times[0],style=MaterialTheme.typography.titleLarge,color=kind.tint()) }
                    Column(Modifier.weight(1f)) { Text("起床",style=MaterialTheme.typography.labelMedium); Text(times[1],style=MaterialTheme.typography.titleLarge,color=kind.tint()) }
                }
            }
        }
        if(record.id.startsWith(DemoData.PREFIX)||record.id.startsWith("food-expense-${DemoData.PREFIX}")) Text("示例数据",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
        if(record.detail.isNotBlank()) {
            if(kind==Kind.TRAINING || kind==Kind.SLEEP) Text("记录感受",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(record.detail,style=if(kind==Kind.FOOD) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyMedium)
        }
        if(kind==Kind.FOOD) {
            val expense=all.find {it.id==food?.expenseId}
            Text(expense?.value?.let {"餐费 ¥${RecordRules.money(it)} · 已计入消费"} ?: "未记录价格",color=kind.tint())
            food?.photoPath?.let {FoodPhoto(it)}
        }
        if(kind==Kind.NOTE && record.extra.isNotBlank()) Text("# ${record.extra}",color=kind.tint())
        Row { TextButton(onClick=onEdit) { Text("编辑") }; TextButton(onClick=onDelete) { Text("删除",color=MaterialTheme.colorScheme.error) } }
    }
}

fun expenseTint(category:String):Color = when(ExpenseCategories.main(category)) {
    "食品酒水" -> Color(0xFF9B531E)
    "交通" -> Color(0xFF315E9D)
    "衣服","购物" -> Color(0xFF8C4E83)
    "休闲娱乐" -> Color(0xFF6556A3)
    "居家物业" -> Color(0xFF36796C)
    "医疗保健" -> Color(0xFFAC485B)
    "学习进修" -> Color(0xFF4D6593)
    "人情往来" -> Color(0xFF93673D)
    else -> Color(0xFF637260)
}
@Composable fun ExpenseMark(category:String) {
    val color=expenseTint(category)
    if(ExpenseCategories.main(category)=="食品酒水") { KindMark(Kind.FOOD); return }
    Box(Modifier.size(38.dp).background(color.copy(alpha=.10f),RoundedCornerShape(12.dp)),contentAlignment=Alignment.Center) {
        if(category=="交通") Canvas(Modifier.size(22.dp)) {
            val w=size.width; val h=size.height; val s=Stroke(2.dp.toPx())
            drawRoundRect(color,Offset(w*.18f,h*.1f),Size(w*.64f,h*.65f),androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),style=s)
            drawLine(color,Offset(w*.2f,h*.4f),Offset(w*.8f,h*.4f),s.width)
            listOf(.3f,.7f).forEach { drawCircle(color,w*.06f,Offset(w*it,h*.6f)); drawLine(color,Offset(w*it,h*.76f),Offset(w*it,h*.92f),s.width) }
        } else Text(category.take(1),color=color,fontWeight=FontWeight.Bold)
    }
}
