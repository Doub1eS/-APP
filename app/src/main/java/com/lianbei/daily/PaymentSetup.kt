package com.lianbei.daily

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun openPaymentSetting(context: Context, intent: Intent): Boolean = runCatching { context.startActivity(intent); true }.getOrDefault(false)

/** Reads the shared state written by the `:capture` process. Cheap enough to poll while visible. */
@Composable fun rememberCaptureStatus(intervalMs: Long = 1_000L): PaymentCaptureStatus {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var status by remember(context, lifecycle) { mutableStateOf(PaymentCaptureStatus()) }
    LaunchedEffect(context, lifecycle, intervalMs) {
        observeCaptureStatus(lifecycle, intervalMs,
            readStatus = {
                withContext(Dispatchers.IO) {
                    settledStatus(context, CaptureStore.snapshot(context))
                }
            },
            onStatus = { status = it })
    }
    return status
}

internal suspend fun observeCaptureStatus(
    lifecycle: Lifecycle,
    intervalMs: Long,
    readStatus: suspend () -> PaymentCaptureStatus,
    onStatus: (PaymentCaptureStatus) -> Unit
) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        while (true) {
            onStatus(readStatus())
            delay(intervalMs.coerceAtLeast(1L))
        }
    }
}

/**
 * Notification visibility is independent of service liveness. Match each persisted owner against
 * the OS process list without starting or binding a service. An unavailable list means unknown
 * and is conservatively represented by false in the existing status fields.
 */
internal fun settledStatus(context: Context, status: PaymentCaptureStatus): PaymentCaptureStatus {
    val raw = CaptureStore.state(context)
    val processes = runCatching {
        context.getSystemService(ActivityManager::class.java)?.runningAppProcesses?.map {
            CaptureProcess(it.pid, it.uid, it.processName)
        }
    }.getOrNull()
    fun active(key: String) = CaptureLiveness.isActive(
        raw, key, processes, context.applicationInfo.uid, "${context.packageName}:capture"
    )
    return status.copy(
        watchRunning = status.watchRunning && active("watch"),
        connected = status.connected && active("connected")
    )
}

@Composable fun PaymentSetup(enabled: Boolean, granted: Boolean, expanded: Boolean, onError: (String) -> Unit) {
    val context = LocalContext.current
    val status = rememberCaptureStatus()
    val remindersNow = context.getSystemService(NotificationManager::class.java)
        .let { it.areNotificationsEnabled() && it.getNotificationChannel(PaymentNotifications.CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE }
    fun stamp(time: Long) = if (time == 0L) "暂无" else Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))

    Panel {
        Text(
            when {
                !enabled -> "自动识别未开启"
                !granted -> "已开启，但系统还没有授权读取通知"
                !status.connected -> "已授权，尚未确认监听连接"
                !status.watchRunning -> "监听中 · 后台常驻未确认运行"
                else -> "后台监听中"
            },
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            "通知读取：${if (granted) "已授权" else "未授权"} · 确认提醒：${if (remindersNow) "可用" else "未开启"} · 后台常驻：${if (status.watchRunning) "运行中" else "未确认运行"}",
            style = MaterialTheme.typography.bodySmall
        )
        if (enabled && granted && !status.connected) {
            Text(
                "系统已授权，但目前无法确认监听连接，付款通知可能无法记录。可以点下方“尝试重新连接监听”，或到系统「通知使用权」中检查授权。若曾强行停止练呗，需要先手动打开应用，再检查连接状态。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            TextButton(onClick = {
                if (!openPaymentSetting(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)))
                    onError("无法打开通知使用权，请在手机设置搜索“通知使用权”并允许练呗。")
            }) { Text("去系统设置检查通知使用权") }
        }
        if (enabled && granted && status.connected && !status.watchRunning) {
            Text(
                "监听已连接，但尚未确认后台常驻运行。开启后台常驻有助于维持监听，并会显示常驻通知；划走应用、系统清理或后台限制仍可能中断监听。强行停止后需要手动打开应用。",
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (status.refused.isNotBlank()) Text(status.refused, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        if (expanded) {
            Text("最近收到微信／支付宝通知：${stamp(status.receivedAt)}", style = MaterialTheme.typography.bodySmall)
            if (status.reason.isNotEmpty()) Text("${status.source} · ${status.reason}", style = MaterialTheme.typography.bodySmall)
            Text("最近成功识别：${stamp(status.capturedAt)}", style = MaterialTheme.typography.bodySmall)
            Text("最近连接建立：${stamp(status.connectedAt)}", style = MaterialTheme.typography.bodySmall)
            if (status.queued > 0) Text("还有 ${status.queued} 条待写入本地账本，打开页面后会自动写入。", style = MaterialTheme.typography.bodySmall)
            Text("Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}", style = MaterialTheme.typography.bodySmall)
            Text("未收到系统通知、内容被隐藏、仅在支付App内显示时，无法通过通知自动识别。不会读取历史账单。", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = enabled && granted, onClick = {
                val ok = PaymentListener.requestRebindNow(context)
                if (!ok) onError("暂时无法重连，请在系统通知使用权中关闭后重新开启练呗。")
            }) { Text("尝试重新连接监听") }
            OutlinedButton(enabled = enabled && granted, onClick = {
                if (!PaymentNotifications.selfTest(context)) onError("无法发送检查通知，请在练呗通知设置中允许通知及通知监听检查分类。")
            }) { Text("检查通知监听") }
            TextButton(enabled = enabled, onClick = {
                if (status.watchRunning) PaymentWatchService.stop(context) else PaymentWatchService.start(context)
            }) { Text(if (status.watchRunning) "关闭后台常驻" else "开启后台常驻") }
            if (status.selfRequestedAt > 0) Text(
                if (status.selfReceivedAt >= status.selfRequestedAt) "上次检查通过：${stamp(status.selfReceivedAt)}，监听服务已收到测试通知"
                else "等待测试通知送达；若一直未通过，请检查通知权限并尝试重连。",
                style = MaterialTheme.typography.bodySmall
            )
            Text("此检查验证系统通知链路，不代表微信、支付宝一定发送付款通知。", style = MaterialTheme.typography.bodySmall)
            Text("小米后台设置", style = MaterialTheme.typography.titleSmall)
            Text("在手机设置搜索“自启动”，允许练呗；在练呗应用信息中检查省电策略，必要时设为无限制。HyperOS不同版本入口可能不同。", style = MaterialTheme.typography.bodySmall)
            Text("如果系统提示“受限制的设置”，请在确认安装包来源后，按系统提示在应用信息中允许相关设置，再授权通知使用权。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                if (!openPaymentSetting(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))))
                    onError("无法打开应用信息，请从手机设置进入练呗应用信息。")
            }) { Text("打开练呗应用信息") }
            TextButton(onClick = { PaymentDiagnostics.clear(context) }) { Text("清除识别诊断记录") }
            Text("诊断只保存来源、时间和处理结果，不保存通知原文。", style = MaterialTheme.typography.bodySmall)
        }
    }
}
