package com.lianbei.daily

import android.app.Notification
import android.os.Bundle
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.Assert.*

class PaymentCaptureTest {
    @Test fun xiaomiCompactNotificationCreatesOnePendingExpense()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,DailyDatabase::class.java).build()
        val before=PaymentSettings.enabled(context)
        PaymentSettings.enable(context,true)
        try {
            val n=notification(body="已支付¥22.00")
            val dao=db.records()
            assertEquals("已加入待确认",PaymentCapture.accept(context,dao,"com.tencent.mm","xiaomi-sample",1001,n))
            assertEquals("重复通知，已跳过",PaymentCapture.accept(context,dao,"com.tencent.mm","xiaomi-sample",1002,n))
            assertTrue(dao.allRecords().isEmpty())
        } finally {PaymentSettings.enable(context,before);db.close()}
    }
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun notification(time:Long=1000, title:String="微信支付", body:String="付款成功 12元\n商户：早餐店") = Notification.Builder(context,"test")
        .setContentTitle(title).setContentText(body).setWhen(time).build()

    @Test fun notificationUpdateAndReconnectDoNotCreateAnotherPayment()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,DailyDatabase::class.java).build()
        val before=PaymentSettings.enabled(context)
        PaymentSettings.enable(context,true)
        try {
            val n=notification()
            val dao=db.records()
            assertEquals("已加入待确认",PaymentCapture.accept(context,dao,"com.tencent.mm","key",1001,n))
            assertEquals("重复通知，已跳过",PaymentCapture.accept(context,dao,"com.tencent.mm","key",1500,n))
            val p=dao.pending().first().single()
            dao.confirm(p.id,"餐饮")
            PaymentCapture.accept(context,dao,"com.tencent.mm","key",1700,n)
            assertTrue(dao.pending().first().isEmpty())
            assertEquals(1,dao.allRecords().size)
            PaymentCapture.accept(context,dao,"com.tencent.mm","key",3001,notification(3000))
            assertEquals(1,dao.pending().first().size)
        } finally {PaymentSettings.enable(context,before);db.close()}
    }

    @Test fun expandedTextAndLinesUseSamePipelineAndDisabledCaptureStoresNothing()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,DailyDatabase::class.java).build()
        val before=PaymentSettings.enabled(context)
        PaymentSettings.enable(context,true)
        try {
            val n=notification(body="付款成功 12元")
            n.extras.putCharSequence(Notification.EXTRA_BIG_TEXT,"付款成功 12元\n商户：早餐店")
            n.extras.putCharSequenceArray(Notification.EXTRA_TEXT_LINES,arrayOf("付款成功 12元","商户：早餐店"))
            assertEquals("已加入待确认",PaymentCapture.accept(context,db.records(),"com.tencent.mm","a",1001,n))
            assertEquals("早餐店",db.records().pending().first().single().merchant)
            PaymentSettings.enable(context,false)
            assertEquals("识别已关闭",PaymentCapture.accept(context,db.records(),"com.tencent.mm","b",2001,notification(2000)))
            assertEquals(1,db.records().pending().first().size)
        } finally {PaymentSettings.enable(context,before);db.close()}
    }

    @Test fun diagnosticDoesNotPersistNotificationContent()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,DailyDatabase::class.java).build()
        val before=PaymentSettings.enabled(context)
        PaymentSettings.enable(context,true)
        try {
            val marker="private-chat-marker-9384"
            PaymentCapture.accept(context,db.records(),"com.tencent.mm","a",1001,notification(title="朋友",body=marker))
            assertTrue(db.records().pending().first().isEmpty())
            val state=PaymentDiagnostics.snapshot(context)
            assertEquals("不是支持的支付服务标题",state.reason)
            assertFalse(context.getSharedPreferences("payment_diagnostics",0).all.toString().contains(marker))
        } finally {PaymentSettings.enable(context,before);db.close()}
    }
    @Test fun enrichedNotificationAndRepostedTransactionStayOnePayment()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,DailyDatabase::class.java).build()
        val before=PaymentSettings.enabled(context)
        PaymentSettings.enable(context,true)
        try {
            val dao=db.records()
            PaymentCapture.accept(context,dao,"com.tencent.mm","key",1001,notification(body="付款成功 12元"))
            val first=dao.pending().first().single()
            dao.confirm(first.id)
            val enriched=notification(body="付款成功 12元\n商户：早餐店\n交易单号：20261019123456789")
            PaymentCapture.accept(context,dao,"com.tencent.mm","key",1500,enriched)
            PaymentCapture.accept(context,dao,"com.tencent.mm","new-key",2000,enriched)
            assertTrue(dao.pending().first().isEmpty())
            assertEquals(1,dao.allRecords().size)
        } finally {PaymentSettings.enable(context,before);db.close()}
    }
    @Test fun oldOptInSessionCannotCaptureAfterReenable()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,DailyDatabase::class.java).build()
        val before=PaymentSettings.enabled(context)
        try {
            PaymentCapture.setEnabled(context,true)
            val previous=PaymentSettings.session(context)
            PaymentCapture.setEnabled(context,false)
            PaymentCapture.setEnabled(context,true)
            assertEquals("识别已关闭",PaymentCapture.accept(context,db.records(),"com.tencent.mm","key",1001,notification(),previous))
            assertTrue(db.records().pending().first().isEmpty())
        } finally {PaymentCapture.setEnabled(context,before);db.close()}
    }
}
