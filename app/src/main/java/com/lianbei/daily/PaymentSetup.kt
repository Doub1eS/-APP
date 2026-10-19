package com.lianbei.daily

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun openPaymentSetting(context:Context,intent:Intent):Boolean = runCatching { context.startActivity(intent);true }.getOrDefault(false)

@Composable fun PaymentSetup(enabled:Boolean,granted:Boolean,expanded:Boolean,onError:(String)->Unit) {
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val connected by PaymentDiagnostics.connected.collectAsState()
    var status by remember {mutableStateOf(PaymentDiagnostics.snapshot(context))}
    var reminders by remember {mutableStateOf(false)}
    fun refresh() {
        status=PaymentDiagnostics.snapshot(context)
        val manager=context.getSystemService(NotificationManager::class.java)
        reminders=manager.areNotificationsEnabled() && manager.getNotificationChannel(PaymentNotifications.CHANNEL)?.importance!=NotificationManager.IMPORTANCE_NONE
    }
    DisposableEffect(lifecycle) {
        refresh()
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_RESUME) refresh()}
        val listener=SharedPreferences.OnSharedPreferenceChangeListener {_,_->refresh()}
        lifecycle.addObserver(observer)
        PaymentDiagnostics.prefs(context).registerOnSharedPreferenceChangeListener(listener)
        onDispose {lifecycle.removeObserver(observer);PaymentDiagnostics.prefs(context).unregisterOnSharedPreferenceChangeListener(listener)}
    }
    fun stamp(time:Long)=if(time==0L) "暂无" else Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))
    Panel {
        Text(when { !enabled->"自动识别未开启"; !granted->"需要通知读取授权"; !connected->"已授权，监听尚未连接";else->"正在监听支付通知"},style=MaterialTheme.typography.titleMedium)
        Text("通知读取：${if(granted) "已授权" else "未授权"} · 确认提醒：${if(reminders) "可用" else "未开启"}",style=MaterialTheme.typography.bodySmall)
        if(expanded) {
            Text("最近收到微信／支付宝通知：${stamp(status.receivedAt)}",style=MaterialTheme.typography.bodySmall)
            if(status.reason.isNotEmpty()) Text("${status.source} · ${status.reason}",style=MaterialTheme.typography.bodySmall)
            Text("最近成功识别：${stamp(status.capturedAt)}",style=MaterialTheme.typography.bodySmall)
            Text("Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}",style=MaterialTheme.typography.bodySmall)
            Text("未收到系统通知、内容被隐藏、仅在支付App内显示时，无法通过通知自动识别。不会读取历史账单。",style=MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled=enabled&&granted,onClick={
                val ok=runCatching {NotificationListenerService.requestRebind(ComponentName(context,PaymentListener::class.java))}.isSuccess
                if(!ok) onError("暂时无法重连，请在系统通知使用权中关闭后重新开启练呗。")
            }) {Text("尝试重新连接监听")}
            OutlinedButton(enabled=enabled&&granted,onClick={
                if(!PaymentNotifications.selfTest(context)) onError("无法发送检查通知，请在练呗通知设置中允许通知及通知监听检查分类。")
            }) {Text("检查通知监听")}
            if(status.selfRequestedAt>0) Text(if(status.selfReceivedAt>=status.selfRequestedAt) "上次检查通过：${stamp(status.selfReceivedAt)}，监听服务已收到测试通知" else "等待测试通知送达；若一直未通过，请检查通知权限并尝试重连。",style=MaterialTheme.typography.bodySmall)
            Text("此检查验证系统通知链路，不代表微信、支付宝一定发送付款通知。",style=MaterialTheme.typography.bodySmall)
            Text("小米后台设置",style=MaterialTheme.typography.titleSmall)
            Text("在手机设置搜索“自启动”，允许练呗；在练呗应用信息中检查省电策略，必要时设为无限制。HyperOS不同版本入口可能不同。",style=MaterialTheme.typography.bodySmall)
            Text("如果系统提示“受限制的设置”，请在确认安装包来源后，按系统提示在应用信息中允许相关设置，再授权通知使用权。",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={if(!openPaymentSetting(context,Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}")))) onError("无法打开应用信息，请从手机设置进入练呗应用信息。")}) {Text("打开练呗应用信息")}
            TextButton(onClick={PaymentDiagnostics.clear(context)}) {Text("清除识别诊断记录")}
            Text("诊断只保存来源、时间和处理结果，不保存通知原文。",style=MaterialTheme.typography.bodySmall)
        }
    }
}
