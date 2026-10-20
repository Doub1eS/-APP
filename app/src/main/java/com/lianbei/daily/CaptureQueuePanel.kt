package com.lianbei.daily

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shows only fixed failure categories and counts. Never renders a queued payload or exception. */
@Composable fun CaptureQueuePanel(dao: RecordDao) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<CaptureQueueStatus?>(null) }
    var confirmation by remember { mutableStateOf<CaptureQueueStatus?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    suspend fun refresh() {
        status = withContext(Dispatchers.IO) { CaptureStore.queueStatus(context) }
    }

    LaunchedEffect(context, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                refresh()
                delay(1_000)
            }
        }
    }

    fun act(discard: CaptureQueueStatus? = null) {
        if (busy) return
        busy = true
        message = ""
        scope.launch {
            try {
                if (discard == null) {
                    CaptureDrain.retryFailed(context, dao)
                    message = "重试完成，请查看剩余失败项和待确认列表。"
                } else {
                    val count = CaptureDrain.discardFailed(context, discard)
                    message = "已丢弃 $count 条失败项。"
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                message = "操作未完成或队列已变化，请刷新状态后重试；丢弃需要重新确认。"
            } finally {
                busy = false
            }
            refresh()
        }
    }

    val current = status
    Panel {
        Text("采集失败队列", style = MaterialTheme.typography.titleSmall)
        when {
            current == null -> Text("正在读取队列状态…", style = MaterialTheme.typography.bodySmall)
            current.readError.isNotEmpty() -> {
                Text(current.readError, modifier = Modifier.testTag("capture-queue-error"),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = !busy, onClick = { scope.launch { refresh() } }) { Text("刷新队列状态") }
            }
            current.failedCount == 0 -> Text("暂无失败项", style = MaterialTheme.typography.bodySmall)
            else -> {
                Text("有 ${current.failedCount} 条采集失败项", style = MaterialTheme.typography.bodyMedium)
                if (current.malformedCount > 0) Text(
                    "队列内容损坏或格式不支持：${current.malformedCount} 条",
                    style = MaterialTheme.typography.bodySmall
                )
                if (current.storageCount > 0) Text(
                    "写入本地账本失败：${current.storageCount} 条",
                    style = MaterialTheme.typography.bodySmall
                )
                Text("可重试写入；内容损坏的记录可能无法恢复。这里只显示失败原因和数量。",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !busy, modifier = Modifier.testTag("capture-retry"),
                        onClick = { act() }) { Text(if (busy) "处理中…" else "重试失败项") }
                    TextButton(enabled = !busy, modifier = Modifier.testTag("capture-discard"),
                        onClick = { confirmation = current }) { Text("丢弃失败项") }
                }
            }
        }
        if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall)
    }

    confirmation?.let { selected ->
        AlertDialog(
            onDismissRequest = { if (!busy) confirmation = null },
            title = { Text("丢弃 ${selected.failedCount} 条失败项？") },
            text = { Text("丢弃后不可恢复。这些失败项将无法通过队列重试写入账本，请确认已核对付款情况。正常新队列和已有账目会保留。") },
            confirmButton = {
                TextButton(enabled = !busy, modifier = Modifier.testTag("capture-discard-confirm"), onClick = {
                    confirmation = null
                    act(selected)
                }) { Text("确认丢弃，不可恢复") }
            },
            dismissButton = {
                TextButton(enabled = !busy, modifier = Modifier.testTag("capture-discard-cancel"),
                    onClick = { confirmation = null }) { Text("取消") }
            }
        )
    }
}
