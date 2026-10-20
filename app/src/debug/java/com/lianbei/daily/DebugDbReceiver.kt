package com.lianbei.daily

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/**
 * Debug-build only. Dumps the database this process owns so verification scripts can read it:
 *
 *   adb shell am broadcast -n com.lianbei.daily/.DebugDbReceiver -a com.lianbei.daily.DEBUG_DB
 *   adb shell run-as com.lianbei.daily cat files/debug-db.txt
 */
class DebugDbReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == "com.lianbei.daily.DEBUG_DELETE_PENDING") {
            val ids = intent.getStringArrayExtra("ids").orEmpty().toList()
            if (ids.isEmpty()) return
            val result = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    val dao = DailyDatabase.get(context).records()
                    ids.forEach { dao.deletePending(it) }
                    Log.i("LianbeiDebug", "deleted pending=${ids.size}")
                } finally {
                    result.finish()
                }
            }
            return
        }
        if (action !in setOf("com.lianbei.daily.DEBUG_DB", "com.lianbei.daily.DEBUG_DRAIN")) return
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val dao = DailyDatabase.get(context).records()
                if (action == "com.lianbei.daily.DEBUG_DRAIN") {
                    CaptureDrain.drain(context, dao)
                    return@launch
                }
                val pending = dao.pending().first()
                val all = dao.observeAll().first()
                val text = buildString {
                    appendLine("PendingCount=${pending.size} RecordCount=${all.size}")
                    pending.forEach {
                        appendLine("PENDING\t${it.id}\t${it.cents}\t${it.merchant}\t${it.source}\t${it.date} ${it.time}\t${it.status}")
                    }
                    all.forEach {
                        appendLine("RECORD\t${it.id}\t${it.kind}\t${it.date}\t${it.value}\t${it.title}\t${it.detail}")
                    }
                }
                File(context.filesDir, "debug-db.txt").writeText(text)
                Log.i("LianbeiDebug", "db dumped pending=${pending.size} records=${all.size}")
            } catch (e: Exception) {
                Log.e("LianbeiDebug", "db dump failed", e)
            } finally {
                result.finish()
            }
        }
    }
}
