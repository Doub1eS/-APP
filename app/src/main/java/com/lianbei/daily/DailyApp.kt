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
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import android.app.DatePickerDialog
import androidx.compose.ui.platform.testTag
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
fun DailyApp(dao: RecordDao, openPayments: Boolean = false) {
    var page by rememberSaveable { mutableStateOf("今天") }
    var paymentInbox by rememberSaveable { mutableStateOf(openPayments) }
    val pendingPayments by dao.pending().collectAsStateWithLifecycle(emptyList())
    val foodRows by dao.foodEntries().collectAsStateWithLifecycle(initialValue = null)
    val foodEntries = foodRows.orEmpty()
    val all by dao.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val subscriptions by dao.subscriptions().collectAsStateWithLifecycle(emptyList())
    val subscriptionPayments by dao.subscriptionPayments().collectAsStateWithLifecycle(emptyList())
    val categories by dao.categories().collectAsStateWithLifecycle(initialValue = emptyList())
    var dateText by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    val date = LocalDate.parse(dateText)
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf("") }
    LaunchedEffect(selected) {saveError=""}
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val editing=all.find {it.id==editingId}
    var demoBusy by remember {mutableStateOf(false)}
    var clearDemo by remember {mutableStateOf(false)}
    var deleting by remember { mutableStateOf<DailyRecord?>(null) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    suspend fun cleanPhotos() {
        val paths=dao.allFoodEntries().map {it.photoPath}.toSet()
        withContext(Dispatchers.IO){FoodPhotos.clean(context,paths)}
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            try {
                val content = withContext(Dispatchers.IO) { JSONObject().put("version", 3).put("foodEntries", JSONArray().apply {
                    foodEntries.forEach { f -> put(JSONObject().put("foodId",f.foodId).put("expenseId",f.expenseId ?: JSONObject.NULL).put("ownsExpense",f.ownsExpense).put("photoBase64",FoodPhotos.export(context,f.photoPath))) }
                }).put("subscriptions", JSONArray().apply {
                    subscriptions.forEach { s -> put(JSONObject().put("id",s.id).put("name",s.name).put("cents",s.cents).put("cycle",s.cycle).put("startDate",s.startDate).put("category",s.category).put("note",s.note).put("paymentUrl",s.paymentUrl).put("color",s.color).put("active",s.active)) }
                }).put("subscriptionPayments", JSONArray().apply {
                    subscriptionPayments.forEach { p -> put(JSONObject().put("subscriptionId",p.subscriptionId).put("dueDate",p.dueDate).put("recordId",p.recordId)) }
                }).put("records", JSONArray().apply {
                    all.forEach { r -> put(JSONObject().put("id", r.id).put("kind", r.kind).put("date", r.date).put("time", r.time).put("title", r.title).put("detail", r.detail).put("value", r.value ?: JSONObject.NULL).put("extra", r.extra).put("createdAt", r.createdAt)) }
                }).put("categories", JSONArray(categories.map { it.name })).toString(2) }
                withContext(Dispatchers.IO) {
                    checkNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(content) }
                }
                snackbar.showSnackbar("记录已导出")
            } catch (_: Exception) { snackbar.showSnackbar("导出失败，请重试") }
        }
    }
    val entries = all.filter { it.date == dateText }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
        NavigationBar(containerColor=MaterialTheme.colorScheme.surface) {
            listOf("今天","订阅","回看","我的").forEachIndexed { index,label ->
                NavigationBarItem(selected=page==label,onClick={page=label},icon={
                    NavMark(index)
                },label={Text(label)})
            }
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if(page=="订阅") SubscriptionScreen(dao,all)
            else if(page=="回看") InsightsScreen(all,{editingId=it.id;selected=it.kind},{deleting=it},foodEntries)
            else LazyColumn(Modifier.fillMaxSize().testTag("daily-list"), contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                if(page=="我的") {
                    item { PageTitle("我的", "留住每一天的小小积累。") }
                    item { Panel {
                        Text("${all.size} 条记录",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
                        Text("${all.map {it.date}.distinct().size} 天留下足迹 · ${categories.size} 个自定义类别")
                    } }
                    item { Panel {
                        Text("数据与备份",style=MaterialTheme.typography.titleLarge)
                        Text("记录保存在这台设备，无需登录。导出的 JSON 可保存和查看，目前不支持导入。")
                        Button(onClick={export.launch("练呗记录-${LocalDate.now()}.json")}) {Text("导出记录")}
                    } }
                    item { Panel {
                        Text("示例数据",style=MaterialTheme.typography.titleLarge)
                        Text("添加最近7天的日常和3条订阅，方便查看页面。示例会计入统计，并标注为示例；可随时清除。")
                        Button(enabled=!demoBusy,onClick={demoBusy=true;scope.launch {try {DemoData.add(dao);snackbar.showSnackbar("示例数据已添加")}catch(_:Exception){snackbar.showSnackbar("添加失败，请重试")}finally {demoBusy=false}}}){Text("添加示例数据")}
                        TextButton(enabled=!demoBusy,onClick={clearDemo=true}){Text("清除示例数据")}
                    } }
                    item { Panel {
                        Text("自定义类别",style=MaterialTheme.typography.titleLarge)
                        Text(if(categories.isEmpty()) "阅读、旅行、灵感……给自己的记录起个名字。" else categories.joinToString(" · "){it.name})
                        TextButton(onClick={editingId=null;selected=Kind.NOTE.name}){Text("创建类别 / 写随手记")}
                    } }
                    item { Panel {
                        Text("低成本记账",style=MaterialTheme.typography.titleLarge)
                        Text("付款通知先核对，确认后计入消费。")
                        TextButton(onClick={paymentInbox=true}){Text("支付待确认 · ${pendingPayments.size}")}
                    } }
                } else {
                    item {
                        PageTitle("练呗","把日子记下来，慢慢看见自己。")
                    }
                    item {
                        TextButton(onClick={paymentInbox=true}){Text("支付待确认 · ${pendingPayments.size}")}
                    }
                    item {
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                            TextButton(modifier=Modifier.weight(1f),onClick={dateText=date.minusDays(1).toString()}) {Text("前一天",maxLines=1)}
                            TextButton(modifier=Modifier.weight(1f),onClick={DatePickerDialog(context,{_,y,m,d->dateText=LocalDate.of(y,m+1,d).toString()},date.year,date.monthValue-1,date.dayOfMonth).apply {datePicker.maxDate=System.currentTimeMillis()}.show()}) {Text("${date.monthValue}/${date.dayOfMonth} ▾",maxLines=1)}
                            TextButton(modifier=Modifier.weight(1f),enabled=date<LocalDate.now(),onClick={dateText=date.plusDays(1).toString()}){Text("后一天",maxLines=1)}
                        }
                        if(date!=LocalDate.now()) TextButton(onClick={dateText=LocalDate.now().toString()}){Text("回到今天")}
                    }
                    item {
                        Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primary),modifier=Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                Text("这一天的足迹",color=Color.White.copy(alpha=.85f))
                                Text("${entries.size} 条日常",fontSize=32.sp,fontWeight=FontWeight.Bold,color=Color.White)
                                val total=all.filter {it.kind==Kind.EXPENSE.name && it.date.startsWith(dateText.take(7))}.sumOf {it.value ?: 0}
                                Text("${date.monthValue}月消费  ¥${RecordRules.money(total)}",color=Color.White)
                                Text("每一条记录，都在让生活更清晰。",style=MaterialTheme.typography.bodySmall,color=Color.White.copy(alpha=.85f))
                            }
                        }
                    }
                    item { Text("记下此刻",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold) }
                    items(Kind.entries.chunked(2)) { pair ->
                        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) { pair.forEach {kind ->
                            Card(onClick={editingId=null;selected=kind.name},modifier=Modifier.weight(1f).testTag("entry-${kind.name}"),colors=CardDefaults.cardColors(containerColor=Color.White)) {
                                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {KindMark(kind);Text("＋",color=kind.tint(),fontSize=22.sp)}
                                    Text(kind.label,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                                    Text(kind.hint,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        } }
                    }
                    item { Text("这一天 · ${entries.size} 条记录",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold) }
                    if(entries.isEmpty()) item {Panel {Text("故事，从一条记录开始",style=MaterialTheme.typography.titleMedium);Text("一顿饭、一次散步，或此刻的心情。",color=MaterialTheme.colorScheme.onSurfaceVariant)}}
                    items(entries,key={it.id}) {record->RecordCard(record,{editingId=record.id;selected=record.kind},{deleting=record},foodEntries.find {it.foodId==record.id},all)}
                }
            }
        }
    }
    if(clearDemo) AlertDialog(onDismissRequest={clearDemo=false},title={Text("清除示例数据？")},text={Text("仅清除标记为示例的记录及其自动生成的消费；你自行添加的记录会保留。")},confirmButton={TextButton(enabled=!demoBusy,onClick={demoBusy=true;scope.launch {try {DemoData.clear(dao);cleanPhotos();clearDemo=false;snackbar.showSnackbar("示例数据已清除")}catch(_:Exception){snackbar.showSnackbar("清除失败，请重试")}finally {demoBusy=false}}}){Text("确认清除")}},dismissButton={TextButton(onClick={clearDemo=false}){Text("取消")}})
    if (paymentInbox) PaymentInbox(dao) { paymentInbox = false }
    selected?.let { kind ->
        if(kind==Kind.FOOD.name) {
            if(foodRows!=null && (editingId==null || editing!=null)) FoodEditor(dateText,editing,foodEntries.find {it.foodId==editingId},all,foodEntries,subscriptionPayments,saving,saveError,{selected=null}) {record,photo,cents,existing->
                saving=true
                scope.launch {
                    try {
                        val savedPhoto=withContext(Dispatchers.IO){FoodPhotos.commit(context,photo)}
                        dao.saveFood(record,savedPhoto,cents,existing)
                        withContext(Dispatchers.IO){FoodPhotos.discardDraft(context,photo)}
                        cleanPhotos();saving=false;selected=null;snackbar.showSnackbar("已记录")
                    }
                    catch(e:Exception){saveError=e.message ?: "保存失败，请重试";runCatching {cleanPhotos()}}
                    finally {saving=false}
                }
            }
        } else RecordEditor(Kind.valueOf(kind), dateText, editing, categories.map { it.name },
            onDismiss = { selected = null },
            isSaving = saving, saveError = saveError,
            onSave = { record, newCategory ->
                saving=true
                scope.launch {
                    try {
                        if (newCategory != null) dao.addCategory(CustomCategory(newCategory))
                        dao.save(record)
                        saving=false
                        selected = null
                        snackbar.showSnackbar("已记录")
                    } catch (_: Exception) { saveError="保存失败，请重试" }
                    finally {saving=false}
                }
            })
    }
    deleting?.let { record ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除这条记录？") }, text = { Text(record.title + if(record.kind=="FOOD") "\n删除饮食会保留已关联的消费记录。" else if(record.kind=="EXPENSE"&&foodEntries.any {it.expenseId==record.id}) "\n饮食记录会保留，价格关联会解除。" else "") },
            confirmButton = { TextButton(onClick = { scope.launch {
                try { dao.delete(record); cleanPhotos(); deleting = null } catch (_: Exception) { snackbar.showSnackbar("删除失败，请重试") }
            } }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("保留") } })
    }
}
