package com.lianbei.daily

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.time.LocalDate
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun DailyApp(dao: RecordDao) {
    val all by dao.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val categories by dao.categories().collectAsStateWithLifecycle(initialValue = emptyList())
    var dateText by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    val date = LocalDate.parse(dateText)
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<DailyRecord?>(null) }
    var deleting by remember { mutableStateOf<DailyRecord?>(null) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            try {
                val content = JSONObject().put("version", 1).put("records", JSONArray().apply {
                    all.forEach { r -> put(JSONObject().put("id", r.id).put("kind", r.kind).put("date", r.date).put("time", r.time).put("title", r.title).put("detail", r.detail).put("value", r.value ?: JSONObject.NULL).put("extra", r.extra).put("createdAt", r.createdAt)) }
                }).put("categories", JSONArray(categories.map { it.name })).toString(2)
                withContext(Dispatchers.IO) {
                    checkNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(content) }
                }
                snackbar.showSnackbar("记录已导出")
            } catch (_: Exception) { snackbar.showSnackbar("导出失败，请重试") }
        }
    }
    val entries = all.filter { it.date == dateText }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Text("练呗", style = MaterialTheme.typography.headlineLarge)
                Text("把日子记下来，慢慢看见自己。", color = MaterialTheme.colorScheme.secondary)
                TextButton(onClick = { export.launch("练呗记录-${LocalDate.now()}.json") }) { Text("导出记录") }
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { dateText = date.minusDays(1).toString() }) { Text("前一天") }
                    TextButton(onClick = { dateText = LocalDate.now().toString() }) { Text(if (date == LocalDate.now()) "今天 · ${date.monthValue}/${date.dayOfMonth}" else dateText) }
                    TextButton(onClick = { dateText = date.plusDays(1).toString() }, enabled = date < LocalDate.now()) { Text("后一天") }
                }
            }
            items(Kind.entries.chunked(2)) { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { kind ->
                        ElevatedCard(onClick = { editing = null; selected = kind.name }, modifier = Modifier.weight(1f)) {
                            Column(Modifier.padding(18.dp).heightIn(min = 66.dp)) {
                                Text(kind.label, fontSize = 20.sp)
                                Spacer(Modifier.height(6.dp))
                                Text(kind.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                    }
                }
            }
            item {
                val total = all.filter { it.kind == Kind.EXPENSE.name && it.date.startsWith(dateText.take(7)) }.sumOf { it.value ?: 0 }
                Text("${date.monthValue}月消费  ¥${RecordRules.money(total)}", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                Text("这一天 · ${entries.size} 条记录", style = MaterialTheme.typography.titleLarge)
            }
            if (entries.isEmpty()) item {
                Text("还没有记录。\n从一顿饭、一次散步，或此刻的心情开始。", modifier = Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.secondary)
            }
            items(entries, key = { it.id }) { record ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("${record.time}  ·  ${Kind.valueOf(record.kind).label}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(record.title, style = MaterialTheme.typography.titleMedium)
                        if (record.detail.isNotBlank()) Text(record.detail)
                        if (record.kind == Kind.EXPENSE.name) Text("¥${RecordRules.money(record.value ?: 0)}")
                        if (record.kind == Kind.TRAINING.name && record.value != null) Text("${record.value} 分钟")
                        if (record.kind == Kind.NOTE.name && record.extra.isNotBlank()) Text("# ${record.extra}", color = MaterialTheme.colorScheme.primary)
                        if (record.kind == Kind.SLEEP.name) Text(record.extra.replace("|", " → "))
                        Row {
                            TextButton(onClick = { editing = record; selected = record.kind }) { Text("编辑") }
                            TextButton(onClick = { deleting = record }) { Text("删除") }
                        }
                    }
                }
            }
        }
    }
    selected?.let { kind ->
        RecordEditor(Kind.valueOf(kind), dateText, editing, categories.map { it.name },
            onDismiss = { selected = null },
            onSave = { record, newCategory ->
                scope.launch {
                    try {
                        if (newCategory != null) dao.addCategory(CustomCategory(newCategory))
                        dao.save(record)
                        selected = null
                        snackbar.showSnackbar("已记录")
                    } catch (_: Exception) { snackbar.showSnackbar("保存失败，请重试") }
                }
            })
    }
    deleting?.let { record ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除这条记录？") }, text = { Text(record.title) },
            confirmButton = { TextButton(onClick = { scope.launch {
                try { dao.delete(record); deleting = null } catch (_: Exception) { snackbar.showSnackbar("删除失败，请重试") }
            } }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("保留") } })
    }
}
