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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun PaymentInbox(dao: RecordDao, onClose: () -> Unit) {
    var showSettings by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val pending by dao.pending().collectAsStateWithLifecycle(emptyList())
    var enabled by remember { mutableStateOf(PaymentSettings.enabled(context)) }
    var consent by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val snackbar=remember {SnackbarHostState()}
    LaunchedEffect(error) { if(error.isNotEmpty()) {snackbar.showSnackbar(error);error=""} }
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
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.safeDrawingPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    TextButton(onClick=onClose) {Text("返回日常记录")}
                    PageTitle("支付待确认", "核对一下，让每一笔都有来处。")
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick={showSettings=!showSettings}) {Text(if(showSettings) "收起识别设置" else "识别设置与演示")}
                    if(showSettings) {
                    Text("${if (enabled) "识别已开启" else "识别已关闭"} · ${if (granted) "通知读取已授权" else "尚未授权读取通知"}")
                    TextButton(onClick = {
                        if (enabled) { scope.launch {PaymentCapture.setEnabled(context, false); enabled = false} }
                        else consent = true
                    }) { Text(if (enabled) "关闭自动识别" else "开启自动识别") }
                    if (enabled && !granted) TextButton(onClick = { if(!openPaymentSetting(context,Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) error="无法打开通知使用权，请在手机设置搜索“通知使用权”并允许练呗。" }) { Text("前往授权通知读取") }
                    TextButton(onClick = {
                        if (Build.VERSION.SDK_INT >= 33) request.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else if(!openPaymentSetting(context,Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))) error="请从手机设置打开练呗的通知权限。"
                    }) { Text("允许确认提醒通知") }
                    TextButton(onClick={if(!openPaymentSetting(context,Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName))) error="请从手机设置打开练呗的通知权限。"}) {Text("打开系统通知设置")}
                    Text("超级岛取决于小米系统、焦点通知权限和接入资格；不支持时显示普通通知。通知被关闭时仍可在此确认。", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { scope.launch {
                        try {
                            val p = PendingPayment("demo-${UUID.randomUUID()}", 1234, "演示商户（非真实交易）", "演示", LocalDate.now().toString(), LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")))
                            dao.addPayment(p); PaymentNotifications.show(context, p)
                        } catch (_: Exception) { error = "演示创建失败，请重试" }
                    } }) { Text("生成演示待确认账目") }
                    Text("演示仅检查待确认和入账界面，不验证微信、支付宝通知接收；确认后会计入消费，可在首页删除。", style = MaterialTheme.typography.bodySmall)

                    }
                    PaymentSetup(enabled,granted,showSettings) {error=it}
                    Spacer(Modifier.height(12.dp))
                    Text("待确认 ${pending.size} 笔", style = MaterialTheme.typography.titleLarge)
                }
                if (pending.isEmpty()) item { Panel { Text("账目已整理好",style=MaterialTheme.typography.titleLarge); Text("暂时没有待确认账目。开启识别后，新收到的明确付款通知会出现在这里。") } }
                items(pending, key = { it.id }) { p ->
                    var category by androidx.compose.runtime.saveable.rememberSaveable(p.id) { mutableStateOf("其他") }
                    var child by androidx.compose.runtime.saveable.rememberSaveable(p.id) { mutableStateOf("") }
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text("¥${RecordRules.money(p.cents)}", fontSize=32.sp,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)
                            Text(p.merchant,style=MaterialTheme.typography.titleMedium)
                            Text("${p.source} · ${p.date} ${p.time}")
                            ExpenseCategoryPicker(category,child) {main,sub->category=main;child=sub}
                            Row {
                                Button(onClick = { scope.launch {
                                    try { dao.confirm(p.id, ExpenseCategories.selection(category,child)); PaymentNotifications.cancel(context, p.id) }
                                    catch (_: Exception) { error = "入账失败，请重试" }
                                } }) { Text("确认入账") }
                                TextButton(onClick = { scope.launch {
                                    try { dao.resolve(p.id, "ignored"); PaymentNotifications.cancel(context, p.id) }
                                    catch (_: Exception) { error = "操作失败，请重试" }
                                } }) { Text("忽略") }
                            }
                        }
                    }
                }
            }
            SnackbarHost(snackbar,Modifier.align(androidx.compose.ui.Alignment.BottomCenter).safeDrawingPadding().padding(16.dp))
            }
        }
    }
    if (consent) AlertDialog(onDismissRequest = { consent = false }, title = { Text("开启支付通知识别") }, text = {
        Text("系统授权允许练呗读取通知。本功能只处理微信、支付宝的付款成功通知，提取金额、商户和时间，在本机生成待确认账目；不保存通知原文、不上传数据。聊天、退款、收款和不明确的内容会跳过。可以随时关闭，或在系统设置撤销授权。")
    }, confirmButton = { TextButton(onClick = {
        scope.launch {
        PaymentCapture.setEnabled(context, true); enabled = true; consent = false
        if(!openPaymentSetting(context,Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) error="无法打开通知使用权，请在手机设置搜索“通知使用权”并允许练呗。"
        }
    }) { Text("同意并前往授权") } }, dismissButton = { TextButton(onClick = { consent = false }) { Text("暂不开启") } })
}
