package com.lianbei.daily
import org.junit.Assert.*
import org.junit.Test

class PaymentParserTest {
    @Test fun xiaomiWechatPaidNotification() {
        val payment=PaymentParser.parse("com.tencent.mm","微信支付","已支付¥22.00")
        assertEquals(2200L,payment?.cents)
        assertEquals("微信",payment?.source)
        assertEquals("待补充商户",payment?.merchant)
        listOf("未支付¥22.00", "尚未支付¥22.00", "已支付退款¥22.00", "已支付¥22.00，待付款", "朋友说已支付¥22.00", "未确认已支付¥22.00").forEach {
            assertNull(it,PaymentParser.parse("com.tencent.mm","微信支付",it))
        }
        assertNull(PaymentParser.parse("com.tencent.mm","普通朋友","已支付¥22.00"))
    }
    @Test fun payment() { assertEquals(1234L, PaymentParser.parse("com.tencent.mm", "微信支付", "付款成功 ￥12.34\n商户：早餐店")?.cents) }
    @Test fun alipay() { assertEquals("咖啡店", PaymentParser.parse("com.eg.android.AlipayGphone", "支付宝", "支付成功 18.00元\n商家：咖啡店")?.merchant) }
    @Test fun unrelatedAndAmbiguousAreRejected() {
        assertNull(PaymentParser.parse("other", "微信支付", "支付成功 1元"))
        assertNull(PaymentParser.parse("com.tencent.mm", "朋友", "支付成功 1元"))
        listOf("退款成功 1元", "收款到账 1元", "转账支付成功 1元", "支付成功", "支付成功 20元，优惠2元", "付款失败 1元", "支付成功 0元", "支付成功 1.234元", "支付成功 -1元", "支付成功 1,000元").forEach {
            assertNull(it, PaymentParser.parse("com.tencent.mm", "微信支付", it))
        }
    }
    @Test fun sameEventDeduplicatesButSeparatePaymentsRemain() {
        val p = ParsedPayment(100, "商店", "微信")
        assertEquals(PaymentParser.fingerprint("wx", "a", 1, p), PaymentParser.fingerprint("wx", "a", 1, p))
        assertNotEquals(PaymentParser.fingerprint("wx", "a", 1, p), PaymentParser.fingerprint("wx", "a", 2, p))
    }

    @Test fun successTitleAndExplicitPaidAmountAreRecognized() {
        assertEquals(1250L, PaymentParser.parse("com.eg.android.AlipayGphone", "付款成功", "付款金额：12.50元\n收款方：街角便利店")?.cents)
        val p=PaymentParser.parse("com.tencent.mm","微信支付","支付成功\n实付金额：18.00元\n订单金额：20.00元\n优惠：2.00元\n商户名称：早餐店")
        assertEquals(1800L,p?.cents)
        assertEquals("早餐店",p?.merchant)
    }
    @Test fun sensitiveOrContradictoryContentCannotBecomePayments() {
        listOf("敏感通知内容已隐藏", "支付成功 待付款 12元", "支付成功 实付金额：12元 实付金额：15元", "支付成功 实付金额：-12元", "支付成功 实付金额：1.234元").forEach {
            assertNull(it,PaymentParser.parse("com.tencent.mm","微信支付",it))
        }
        assertNull(PaymentParser.parse("com.tencent.mm","普通朋友","付款成功 12元"))
    }
    @Test fun transactionIdentitySurvivesRepostedNotifications() {
        val p=PaymentParser.parse("com.tencent.mm","微信支付","支付成功 12元\n交易单号：20261018123456789")!!
        assertEquals("20261018123456789",p.transactionId)
        assertEquals(PaymentParser.fingerprint("wx","old",1,p),PaymentParser.fingerprint("wx","new",999,p))
        assertNotEquals(PaymentParser.fingerprint("wx","old",1,p),PaymentParser.fingerprint("wx","old",1,p.copy(transactionId="20261018123456790")))
    }
    @Test fun discountsAndOrderTotalsAloneAreNotPaidAmounts() {
        listOf("付款成功，优惠5元", "付款成功，订单金额100元", "付款成功，余额18元", "付款成功，红包抵扣5元").forEach {
            assertNull(it,PaymentParser.parse("com.tencent.mm","微信支付",it))
        }
    }
}
