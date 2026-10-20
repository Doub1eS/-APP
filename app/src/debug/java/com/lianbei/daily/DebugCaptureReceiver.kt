package com.lianbei.daily

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Debug-build only. Lets local verification scripts drive the real capture pipeline of the
 * `:capture` process without a real WeChat/Alipay payment:
 *
 *   adb shell am broadcast -a com.lianbei.daily.DEBUG_SIMULATE \
 *     -e pkg com.tencent.mm -e title 微信支付 -e body "已支付¥12.34"
 *   adb shell am broadcast -a com.lianbei.daily.DEBUG_DUMP   (writes files/debug-dump.txt)
 *
 * It is not compiled into a release APK.
 */
class DebugCaptureReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "com.lianbei.daily.DEBUG_SIMULATE" -> {
                val result = goAsync()
                val pkg = intent.getStringExtra("pkg") ?: "com.tencent.mm"
                val title = intent.getStringExtra("title").orEmpty()
                val body = intent.getStringExtra("body").orEmpty()
                val key = intent.getStringExtra("key") ?: "debug-${System.currentTimeMillis()}"
                val postedAt = System.currentTimeMillis()
                val extras = Bundle().apply {
                    putCharSequence(Notification.EXTRA_TITLE, title)
                    putCharSequence(Notification.EXTRA_TEXT, body)
                    intent.getStringExtra("big")?.let { putCharSequence(Notification.EXTRA_BIG_TEXT, it) }
                }
                val notification = Notification.Builder(context, "debug_probe")
                    .setSmallIcon(android.R.drawable.ic_menu_info_details)
                    .addExtras(extras).build()
                scope.launch {
                    try {
                        val outcome = PaymentCapture.accept(context, pkg, key, postedAt, notification, PaymentSettings.session(context))
                        Log.i(TAG, "simulate outcome=$outcome pkg=$pkg")
                        append(context, "simulate outcome=$outcome pkg=$pkg key=$key")
                    } catch (e: Exception) {
                        Log.e(TAG, "simulate failed", e)
                    } finally {
                        result.finish()
                    }
                }
            }
            "com.lianbei.daily.DEBUG_DUMP" -> dump(context)
            "com.lianbei.daily.DEBUG_SELFTEST" -> Log.i(TAG, "selftest posted=${PaymentNotifications.selfTest(context)}")
            "com.lianbei.daily.DEBUG_TOGGLE" -> {
                val value = intent.getBooleanExtra("enabled", true)
                val result = goAsync()
                scope.launch {
                    try {
                        PaymentCapture.setEnabled(context, value)
                        Log.i(TAG, "toggle enabled=$value")
                        append(context, "toggle enabled=$value")
                    } finally {
                        result.finish()
                    }
                }
            }
            "com.lianbei.daily.DEBUG_REBIND" -> {
                val ok = PaymentListener.requestRebindNow(context)
                Log.i(TAG, "rebind requested=$ok")
                append(context, "rebind plain=$ok")
            }
            "com.lianbei.daily.DEBUG_REBIND_HARD" -> {
                val result = goAsync()
                scope.launch {
                    try {
                        val component = android.content.ComponentName(context, PaymentListener::class.java)
                        val pm = context.packageManager
                        pm.setComponentEnabledSetting(component, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, android.content.pm.PackageManager.DONT_KILL_APP)
                        kotlinx.coroutines.delay(2_000)
                        pm.setComponentEnabledSetting(component, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED, android.content.pm.PackageManager.DONT_KILL_APP)
                        Log.i(TAG, "component cycled enabled=${pm.getComponentEnabledSetting(component)}")
                        append(context, "component cycled")
                    } catch (e: Exception) {
                        Log.e(TAG, "component cycle failed", e)
                        append(context, "component cycle failed ${e.message}")
                    } finally {
                        result.finish()
                    }
                }
            }

        }
    }

    private fun dump(context: Context) {
        val state = CaptureStore.state(context)
        val queue = File(context.filesDir, "capture-queue.jsonl")
        val text = buildString {
            appendLine("listenerConnectedHere=${PaymentListener.connectedHere}")
            appendLine("state=" + state.toSortedMap())
            appendLine("queueLines=" + (queue.takeIf { it.exists() }?.readLines()?.size ?: 0))
        }
        runCatching { File(context.filesDir, "debug-dump.txt").writeText(text) }
        Log.i(TAG, "dump written")
    }

    private fun append(context: Context, line: String) {
        runCatching {
            File(context.filesDir, "debug-events.txt").appendText("${System.currentTimeMillis()} $line\n")
        }
    }

    companion object {
        private const val TAG = "LianbeiDebug"
    }
}
