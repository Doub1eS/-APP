package com.lianbei.daily

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable fun BackupPanel(dao:RecordDao) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<BackupData?>(null) }
    var message by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    fun error(e:Exception) {
        failed=true
        message=if(e is IllegalArgumentException || e is IllegalStateException) e.message?.take(150) ?: "备份操作失败，请重试"
            else "无法读取或写入完整备份，请检查文件及存储空间后重试"
    }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if(uri!=null)scope.launch {
            busy=true;message="";failed=false
            try {
                CaptureDrain.drain(context,dao)
                val content=Backup.export(context,dao)
                withContext(Dispatchers.IO) {
                    checkNotNull(context.contentResolver.openOutputStream(uri,"wt")).bufferedWriter().use{it.write(content)}
                }
                message="备份已导出，包含照片、订阅和支付处理记录"
            } catch(e:CancellationException){throw e}
            catch(e:Exception){error(e)}
            finally {busy=false}
        }
    }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null)scope.launch {
            busy=true;message="";failed=false
            try {
                preview=withContext(Dispatchers.IO) {
                    checkNotNull(context.contentResolver.openInputStream(uri)).use { Backup.parse(Backup.read(it)) }
                }
            } catch(e:CancellationException){throw e}
            catch(e:Exception){error(e)}
            finally{busy=false}
        }
    }
    Panel {
        Text("数据与备份",style=MaterialTheme.typography.titleLarge)
        Text("记录保存在这台设备，无需登录。JSON备份包含照片、订阅、消费关联和支付处理记录，可在练呗中恢复。")
        Text("恢复会合并新增数据；同ID保留本机内容，不改变识别开关和系统授权。单个备份最多64MB。",style=MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(enabled=!busy,onClick={export.launch("练呗备份-${LocalDate.now()}.json")}){Text("导出记录")}
            OutlinedButton(enabled=!busy,onClick={picker.launch(arrayOf("application/json","text/plain","application/octet-stream"))}){Text("导入备份")}
        }
        if(busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("正在处理备份，请保持应用打开…",style=MaterialTheme.typography.bodySmall)
        }
        if(message.isNotBlank())Text(message,color=if(failed)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    }
    preview?.let { data ->
        BackupPreviewDialog(data,busy,{if(!busy)preview=null}) {
            scope.launch {
                busy=true;failed=false;message=""
                try {
                    val result=Backup.restore(context,dao,data)
                    SubscriptionReminders.refresh(context)
                    preview=null
                    message="恢复完成：新增 ${result.added} 项，跳过 ${result.skipped} 项，${result.conflicts} 项冲突已保留本机数据。"
                } catch(e:CancellationException){throw e}
                catch(e:Exception){preview=null;error(e)}
                finally{busy=false}
            }
        }
    }
}

@Composable internal fun BackupPreviewDialog(data:BackupData,busy:Boolean,onDismiss:()->Unit,onRestore:()->Unit) {
    AlertDialog(onDismissRequest={if(!busy)onDismiss()},title={Text("恢复这份备份？")},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("备份版本 ${data.version}")
            Text("${data.records.size} 条日常记录 · ${data.foodEntries.count{it.photoBase64.isNotBlank()}} 张照片")
            Text("${data.subscriptions.size} 条订阅 · ${data.payments.size} 条支付处理记录")
            Text("只合并新增数据。同ID内容、已占用的消费关联均保留本机版本；重复导入不会重复添加。")
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    },confirmButton={TextButton(enabled=!busy,onClick=onRestore){Text("确认合并恢复")}},
        dismissButton={TextButton(enabled=!busy,onClick=onDismiss){Text("取消")}})
}
