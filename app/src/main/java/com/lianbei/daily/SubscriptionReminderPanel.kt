package com.lianbei.daily

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun SubscriptionReminderPanel() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var enabled by remember { mutableStateOf(SubscriptionReminders.isEnabled(context)) }
    var allowed by remember { mutableStateOf(SubscriptionReminders.notificationsAllowed(context)) }
    var settingsError by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = SubscriptionReminders.notificationsAllowed(context)
        SubscriptionReminders.refresh(context)
    }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                enabled = SubscriptionReminders.isEnabled(context)
                allowed = SubscriptionReminders.notificationsAllowed(context)
                SubscriptionReminders.refresh(context)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("订阅到期提醒", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(checked = enabled, onCheckedChange = {
                    enabled = it
                    SubscriptionReminders.enable(context, it)
                    allowed = SubscriptionReminders.notificationsAllowed(context)
                    if (it && Build.VERSION.SDK_INT >= 33 &&
                        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }, modifier = Modifier.testTag("subscription-reminder-switch"))
            }
            Text("启用后，在到期当天和前一天提醒尚未确认付款的启用订阅。每天约9点检查，系统可能延后。")
            if (enabled && !allowed) {
                Text("通知权限或提醒通知类别已关闭，允许通知后才能收到提醒。",
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("subscription-reminder-permission"))
            }
            if (enabled) {
                TextButton(onClick = {
                    settingsError = runCatching {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    }.isFailure
                }, modifier = Modifier.testTag("subscription-notification-settings")) { Text("打开通知设置") }
            }
            if (settingsError) Text("无法打开设置，请在系统设置中允许练呗通知。", color = MaterialTheme.colorScheme.error)
        }
    }
}
