package com.lianbei.daily

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign

@OptIn(ExperimentalLayoutApi::class)
@Composable fun ExpenseCategoryPicker(parent:String,child:String,onSelect:(String,String)->Unit) {
    val main=ExpenseCategories.main(parent)
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
    Text(if(child.isEmpty()) "分类 · $main" else "分类 · $main / $child",style=MaterialTheme.typography.labelLarge,color=expenseTint(main))
    (ExpenseCategories.groups.keys+listOf(main)).distinct().chunked(3).forEach {row ->
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.fillMaxWidth()) {
            row.forEach {name ->
                val selected=main==name
                Surface(modifier=Modifier.weight(1f),shape=RoundedCornerShape(14.dp),
                    color=if(selected) expenseTint(name).copy(alpha=.09f) else MaterialTheme.colorScheme.surface,
                    border=BorderStroke(1.dp,if(selected) expenseTint(name) else MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.selectable(selected=selected,role=Role.RadioButton,onClick={onSelect(name,if(selected) child else "")})
                        .testTag("expense-parent-$name").padding(horizontal=4.dp,vertical=10.dp),
                        horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        ExpenseMark(name)
                        Text(name,style=MaterialTheme.typography.labelMedium,textAlign=TextAlign.Center)
                    }
                }
            }
            repeat(3-row.size) {Spacer(Modifier.weight(1f))}
        }
    }
    val children=ExpenseCategories.groups[main].orEmpty()
    if(children.isNotEmpty() || child.isNotEmpty()) {
        Text("细分类（可选）",style=MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            FilterChip(selected=child.isEmpty(),onClick={onSelect(main,"")},label={Text("未细分")})
            (children+listOf(child)).filter {it.isNotEmpty()}.distinct().forEach {name ->
                FilterChip(selected=child==name,onClick={onSelect(main,name)},label={Text(name)},modifier=Modifier.testTag("expense-child-$name"))
            }
        }
    }
    }
}
