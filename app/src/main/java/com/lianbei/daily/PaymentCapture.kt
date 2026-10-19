package com.lianbei.daily

import android.app.Notification
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class PaymentCaptureStatus(val receivedAt:Long=0,val source:String="",val reason:String="",val capturedAt:Long=0,val selfRequestedAt:Long=0,val selfReceivedAt:Long=0)

object PaymentDiagnostics {
    // Connection state is deliberately not persisted: a previous process being connected proves nothing now.
    val connected=MutableStateFlow(false)
    fun prefs(context:Context)=context.getSharedPreferences("payment_diagnostics",0)
    fun snapshot(context:Context):PaymentCaptureStatus = prefs(context).let {
        PaymentCaptureStatus(it.getLong("received",0),it.getString("source","").orEmpty(),it.getString("reason","").orEmpty(),it.getLong("captured",0),it.getLong("self_requested",0),it.getLong("self_received",0))
    }
    fun record(context:Context,source:String,reason:String) {
        val now=System.currentTimeMillis()
        prefs(context).edit().putLong("received",now).putString("source",source).putString("reason",reason).apply {
            if(reason=="已加入待确认") putLong("captured",now)
        }.apply()
    }
    fun clear(context:Context) { prefs(context).edit().clear().apply() }
}

object PaymentCapture {
    private val mutex=Mutex()
    suspend fun setEnabled(context:Context,value:Boolean)=mutex.withLock {PaymentSettings.enable(context,value)}
    fun content(notification:Notification):Pair<String,String> {
        val extras=notification.extras
        val title=extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val parts=buildList {
            add(extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty())
            add(extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty())
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach {add(it.toString())}
        }.map {it.trim()}.filter {it.isNotEmpty()}.distinct().sortedByDescending {it.length}
        // Some apps repeat their collapsed text inside bigText and textLines.
        val kept=mutableListOf<String>()
        parts.forEach {part -> if(kept.none {it.contains(part)}) kept.add(part)}
        return title to kept.joinToString("\n")
    }

    suspend fun accept(context:Context,dao:RecordDao,pkg:String,key:String,postedAt:Long,notification:Notification,session:String=PaymentSettings.session(context)):String = mutex.withLock {
        if(!PaymentSettings.enabled(context) || session!=PaymentSettings.session(context)) return@withLock "识别已关闭"
        val source=PaymentParser.sources[pkg] ?: return@withLock "来源未支持"
        fun result(reason:String):String { PaymentDiagnostics.record(context,source,reason);return reason }
        if(notification.flags and Notification.FLAG_GROUP_SUMMARY!=0) return@withLock result("通知分组汇总，已跳过")
        try {
            val (title,body)=content(notification)
            val parsed=PaymentParser.inspect(pkg,title,body)
            val payment=parsed.payment ?: return@withLock result(parsed.reason)
            val eventTime=notification.`when`.takeIf {it>0 && it<=postedAt+60_000} ?: postedAt
            val id=PaymentParser.fingerprint(pkg,key,eventTime,payment.copy(transactionId=null,merchant=""))
            val identities=listOfNotNull(id,payment.transactionId?.let {PaymentParser.fingerprint(pkg,key,eventTime,payment)})
            // Honour payments created by the previous postTime-based parser on upgrade.
            val legacyId=PaymentParser.fingerprint(pkg,key,postedAt,payment.copy(transactionId=null))

            val time=Instant.ofEpochMilli(eventTime).atZone(ZoneId.systemDefault())
            val pending=PendingPayment(id,payment.cents,payment.merchant,payment.source,time.toLocalDate().toString(),time.format(DateTimeFormatter.ofPattern("HH:mm")))
            if(!PaymentSettings.enabled(context) || session!=PaymentSettings.session(context)) return@withLock "识别已关闭"
            if(!dao.capturePayment(pending,identities,listOf(legacyId,PaymentParser.fingerprint(pkg,key,eventTime,payment.copy(transactionId=null))))) return@withLock result("重复通知，已跳过")
            // A disabled reminder must never prevent the payment from appearing in the inbox.
            runCatching {PaymentNotifications.show(context,pending)}
            result("已加入待确认")
        } catch(e:CancellationException) {throw e}
        catch(_:Exception) { result("处理失败，请检查待确认列表后重试") }
    }
}
