package com.lianbei.daily

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.app.NotificationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

private data class PaymentBatch(val ids: List<String>, val confirm: Boolean)

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun PaymentInbox(dao: RecordDao, onClose: () -> Unit) {
    var showSettings by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val pending by dao.pending().collectAsStateWithLifecycle(emptyList())
    val status = rememberCaptureStatus(2_000)
    val enabled = status.enabled
    var consent by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var categoryChanges by remember { mutableStateOf(emptyMap<String, String>()) }
    val working = busy || categoryChanges.isNotEmpty()
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var editing by remember { mutableStateOf<PendingPayment?>(null) }
    var editError by remember { mutableStateOf("") }
    var batch by remember { mutableStateOf<PaymentBatch?>(null) }
    var batchError by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(error) { if (error.isNotEmpty()) { snackbar.showSnackbar(error); error = "" } }
    val pendingIds = pending.map { it.id }.toSet()
    val selectedIds = selected.intersect(pendingIds)
    LaunchedEffect(pendingIds) { selected = selected.intersect(pendingIds) }
    fun cancelNotifications(ids: List<String>): Boolean {
        var succeeded = true
        ids.forEach { id ->
            try { PaymentNotifications.cancel(context, id) }
            catch (_: Exception) { succeeded = false }
        }
        return succeeded
    }
    fun saveCategory(id: String, category: String) {
        if (busy) return
        val startWorker = categoryChanges.isEmpty()
        categoryChanges = categoryChanges + (id to category)
        if (!startWorker) return
        scope.launch {
            try {
                // Serialize writes and retain a subsequent child selection made during a slow save.
                while (categoryChanges.isNotEmpty()) {
                    val (nextId, nextCategory) = categoryChanges.entries.first()
                    try {
                        val latest = dao.payment(nextId)
                        if (latest == null || latest.status != "pending" ||
                            !dao.updatePending(latest.copy(category = nextCategory))) {
                            categoryChanges = categoryChanges - nextId
                            error = "这笔账目已处理或已不存在，无法再修改。"
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) {
                        categoryChanges = categoryChanges - nextId
                        error = "分类保存失败，请重试；本次选择尚未保存。"
                    }
                    if (categoryChanges[nextId] == nextCategory) categoryChanges = categoryChanges - nextId
                }
            } finally { categoryChanges = emptyMap() }
        }
    }
    fun processOne(id: String, confirm: Boolean) {
        if (busy || categoryChanges.isNotEmpty()) return
        busy = true
        scope.launch {
            try {
                // Both single and batch actions use saved categories and pending-only transactions.
                val count = if (confirm) dao.confirmMany(listOf(id)) else dao.ignoreMany(listOf(id))
                selected = selected - id
                if (count == 0) error = "这笔账目已处理或已不存在，请查看最新列表。"
                if (!cancelNotifications(listOf(id))) error = "账目已处理，提醒通知未能清除，请手动移除通知。"
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = if (confirm) "入账失败，请重试" else "忽略失败，请重试。" }
            finally { busy = false }
        }
    }
    fun openEditor(id: String) {
        if (busy || categoryChanges.isNotEmpty()) return
        busy = true
        scope.launch {
            try {
                val latest = dao.payment(id)
                if (latest == null || latest.status != "pending") {
                    error = "这笔账目已处理或已不存在，无法再修改。"
                } else {
                    editing = latest
                    editError = ""
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "账目读取失败，请重试。" }
            finally { busy = false }
        }
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun refresh() {
        val component = ComponentName(context, PaymentListener::class.java)
        granted = if (Build.VERSION.SDK_INT >= 27) context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component)
        else Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?.split(":")?.any { ComponentName.unflattenFromString(it) == component } == true
    }
    DisposableEffect(lifecycle) {
        refresh()
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // A payment captured while the app was closed is still waiting in the shared queue.
    LaunchedEffect(Unit) {
        try { CaptureDrain.drain(context, dao) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { error = "部分待确认记录尚未导入，请稍后重新打开；原记录已保留。" }
    }
    Dialog(onDismissRequest = { if (!working) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.safeDrawingPadding().fillMaxSize()) {
                    // Keep the selection controls reachable even on long cards and large lists.
                    Surface(tonalElevation = 2.dp) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                            if (selecting) {
                                Text("已选择 ${selectedIds.size} 笔", style = MaterialTheme.typography.titleMedium)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(enabled = !working && pending.isNotEmpty(), onClick = { selected = pendingIds }) { Text("全选") }
                                    TextButton(enabled = !working && selectedIds.isNotEmpty(), onClick = { selected = emptySet() }) { Text("取消选择") }
                                    TextButton(enabled = !working, onClick = { selecting = false; selected = emptySet() }) { Text("退出多选") }
                                }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        enabled = !working && selectedIds.isNotEmpty(),
                                        modifier = Modifier.testTag("payment-batch-confirm"),
                                        onClick = {
                                            if (!busy && categoryChanges.isEmpty() && selectedIds.isNotEmpty()) {
                                                batchError = ""
                                                batch = PaymentBatch(selectedIds.toList(), true)
                                            }
                                        }
                                    ) { Text("批量确认") }
                                    OutlinedButton(
                                        enabled = !working && selectedIds.isNotEmpty(),
                                        modifier = Modifier.testTag("payment-batch-ignore"),
                                        onClick = {
                                            if (!busy && categoryChanges.isEmpty() && selectedIds.isNotEmpty()) {
                                                batchError = ""
                                                batch = PaymentBatch(selectedIds.toList(), false)
                                            }
                                        }
                                    ) { Text("批量忽略") }
                                }
                            } else TextButton(enabled = !working && pending.isNotEmpty(), onClick = { selecting = true }) { Text("多选") }
                            if (working) Text(if (busy) "正在处理，请稍候…" else "正在保存分类…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                LazyColumn(Modifier.weight(1f).padding(horizontal = 20.dp).testTag("payment-list"), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                    item {
                        TextButton(onClick = onClose, enabled = !working) { Text("返回日常记录") }
                        PageTitle("支付待确认", "核对一下，让每一笔都有来处。")
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = { showSettings = !showSettings }) { Text(if (showSettings) "收起识别设置" else "识别设置与演示") }
                        if (showSettings) {
                            Text("${if (enabled) "识别已开启" else "识别已关闭"} · ${if (granted) "通知读取已授权" else "尚未授权读取通知"}")
                            TextButton(onClick = {
                                if (enabled) scope.launch { PaymentCapture.setEnabled(context, false) }
                                else consent = true
                            }) { Text(if (enabled) "关闭自动识别" else "开启自动识别") }
                            if (enabled && !granted) TextButton(onClick = { if (!openPaymentSetting(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) error = "无法打开通知使用权，请在手机设置搜索“通知使用权”并允许练呗。" }) { Text("前往授权通知读取") }
                            TextButton(onClick = {
                                if (Build.VERSION.SDK_INT >= 33) request.launch(Manifest.permission.POST_NOTIFICATIONS)
                                else if (!openPaymentSetting(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))) error = "请从手机设置打开练呗的通知权限。"
                            }) { Text("允许确认提醒通知") }
                            TextButton(onClick = { if (!openPaymentSetting(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))) error = "请从手机设置打开练呗的通知权限。" }) { Text("打开系统通知设置") }
                            Text("超级岛取决于小米系统、焦点通知权限和接入资格；不支持时显示普通通知。通知被关闭时仍可在此确认。", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = {
                                scope.launch {
                                    try {
                                        val p = PendingPayment("demo-${UUID.randomUUID()}", 1234, "演示商户（非真实交易）", "演示", LocalDate.now().toString(), LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")))
                                        dao.addPayment(p); PaymentNotifications.show(context, p)
                                    } catch (_: Exception) { error = "演示创建失败，请重试" }
                                }
                            }) { Text("生成演示待确认账目") }
                            Text("演示仅检查待确认和入账界面，不验证微信、支付宝通知接收；确认后会计入消费，可在首页删除。", style = MaterialTheme.typography.bodySmall)
                        }
                        PaymentSetup(enabled, granted, showSettings, dao = dao) { error = it }
                        Spacer(Modifier.height(12.dp))
                        Text("待确认 ${pending.size} 笔", style = MaterialTheme.typography.titleLarge)
                    }
                    if (pending.isEmpty()) item { Panel { Text("账目已整理好", style = MaterialTheme.typography.titleLarge); Text("暂时没有待确认账目。开启识别后，新收到的明确付款通知会出现在这里。") } }
                    items(pending, key = { it.id }) { p ->
                        OutlinedCard(Modifier.fillMaxWidth().testTag("payment-card-${p.id}")) {
                            Column(Modifier.padding(14.dp)) {
                                if (selecting) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = p.id in selectedIds, enabled = !working,
                                        onCheckedChange = { checked ->
                                            if (!working) selected = if (checked) selected + p.id else selected - p.id
                                        },
                                        modifier = Modifier.testTag("payment-select-${p.id}")
                                    )
                                    Text("选择此账目", style = MaterialTheme.typography.labelLarge)
                                }
                                Text("¥${RecordRules.money(p.cents)}", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Text(p.merchant, style = MaterialTheme.typography.titleMedium)
                                Text("${p.source} · ${p.date} ${p.time}")
                                val shownCategory = categoryChanges[p.id] ?: p.category
                                if (!busy) ExpenseCategoryPicker(shownCategory.substringBefore(" / "), shownCategory.substringAfter(" / ", "")) { main, sub ->
                                    val value = ExpenseCategories.selection(main, sub)
                                    if (value != shownCategory) saveCategory(p.id, value)
                                } else Text("分类 · ${p.category}")
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        enabled = !working, onClick = { processOne(p.id, true) },
                                        modifier = Modifier.testTag("payment-confirm-${p.id}")
                                    ) { Text("确认入账") }
                                    TextButton(
                                        enabled = !working, onClick = { openEditor(p.id) },
                                        modifier = Modifier.testTag("payment-edit-${p.id}")
                                    ) { Text("编辑") }
                                    TextButton(
                                        enabled = !working, onClick = { processOne(p.id, false) },
                                        modifier = Modifier.testTag("payment-ignore-${p.id}")
                                    ) { Text("忽略") }
                                }
                            }
                        }
                    }
                }
                    // Reserve layout space for notices so a scrolled card's buttons stay touchable.
                    SnackbarHost(snackbar, Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("payment-notice"))
                }
            }
        }
    }
    editing?.let { original ->
        PaymentEdit(
            original = original,
            available = original.id in pendingIds,
            busy = busy,
            saveError = editError,
            onDismiss = { if (!busy) { editing = null; editError = "" } },
            onSave = { changed ->
                if (!busy) {
                    busy = true
                    editError = ""
                    scope.launch {
                        try {
                            if (dao.updatePending(changed)) {
                                editing = null
                                error = "修改已保存，仍待确认。"
                            } else editError = "这笔账目已处理或已不存在，无法再修改。"
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { editError = "修改保存失败，请检查金额、日期、时间和分类后重试。" }
                        finally { busy = false }
                    }
                }
            }
        )
    }
    batch?.let { action ->
        val count = action.ids.size
        AlertDialog(
            onDismissRequest = { if (!busy) batch = null },
            title = { Text(if (action.confirm) "确认入账 $count 笔？" else "忽略 $count 笔账目？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (action.confirm) "将所选 $count 笔账目按各自保存的金额和分类计入消费。"
                        else "将忽略所选 $count 笔账目，不计入消费，并从待确认列表移除。")
                    Text("已被其他入口处理的账目会跳过；新收到的账目不包含在本次操作中。", style = MaterialTheme.typography.bodySmall)
                    if (batchError.isNotBlank()) Text(batchError, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy, modifier = Modifier.testTag("payment-batch-accept"),
                    onClick = {
                        if (!busy) {
                            busy = true
                            batchError = ""
                            scope.launch {
                                try {
                                    val processed = if (action.confirm) dao.confirmMany(action.ids) else dao.ignoreMany(action.ids)
                                    selected = selected - action.ids.toSet()
                                    batch = null
                                    val label = if (action.confirm) "确认" else "忽略"
                                    error = if (processed == count) "已$label $processed 笔。"
                                        else "已$label $processed 笔，另有 ${count - processed} 笔已处理或已不存在。"
                                    if (!cancelNotifications(action.ids)) error += " 提醒通知未能清除，请手动移除通知。"
                                } catch (e: CancellationException) { throw e }
                                catch (_: Exception) { batchError = "批量处理失败，请重试；所选账目已保留。" }
                                finally { busy = false }
                            }
                        }
                    }
                ) { Text(if (busy) "正在处理…" else if (action.confirm) "确认入账 $count 笔" else "确认忽略 $count 笔") }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { batch = null }, modifier = Modifier.testTag("payment-batch-cancel")) { Text("取消") }
            }
        )
    }
    if (consent) AlertDialog(onDismissRequest = { consent = false }, title = { Text("开启支付通知识别") }, text = {
        Text(
            "系统授权允许练呗读取通知。本功能只处理微信、支付宝的付款成功通知，提取金额、商户和时间，在本机生成待确认账目；不保存通知原文、不上传数据。聊天、退款、收款和不明确的内容会跳过。" +
                "开启后会启动独立的后台采集进程，并显示低优先级常驻通知，帮助页面关闭后继续采集；系统清理或后台限制仍可能中断监听。后台进程会占用内存，可以随时关闭识别、关闭后台常驻，或在系统设置撤销授权。"
        )
    }, confirmButton = {
        TextButton(onClick = {
            scope.launch {
                PaymentCapture.setEnabled(context, true); consent = false
                // A newly enabled listener has to be granted by the user first.
                if (!granted && !openPaymentSetting(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)))
                    error = "无法打开通知使用权，请在手机设置搜索“通知使用权”并允许练呗。"
                else PaymentListener.requestRebindNow(context)
            }
        }) { Text("同意并前往授权") }
    }, dismissButton = { TextButton(onClick = { consent = false }) { Text("暂不开启") } })
}
