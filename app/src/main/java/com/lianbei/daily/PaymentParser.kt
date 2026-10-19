package com.lianbei.daily

import java.security.MessageDigest

data class ParsedPayment(val cents: Long, val merchant: String, val source: String, val transactionId: String? = null)
data class PaymentParseResult(val payment: ParsedPayment? = null, val reason: String)

object PaymentParser {
    val sources = mapOf("com.tencent.mm" to "微信", "com.eg.android.AlipayGphone" to "支付宝")
    private val titles = setOf("微信支付", "支付宝", "支付成功", "付款成功", "支付通知", "支付宝通知")
    private val success = Regex("支付成功|付款成功|成功付款|成功支付")
    // Observed HyperOS WeChat notification: 微信支付 / 已支付¥22.00.
    // Anchor the compact wording so quoted chat-like text is not accepted.
    private val compactPaid = Regex("""^\s*已支付\s*[：:]?\s*[¥￥]\s*\d""")
    private val excluded = Regex("退款|收款(?!方)|到账|转账|红包|失败|待支付|待付款|取消|验证码|付款请求|待收款")
    private const val number = """\d+(?:\.\d{1,2})?(?![\d.,])"""
    private val money = Regex("""(?<![\d.,-])(?:[¥￥]\s*($number)|($number)\s*元)""")
    private val paidLabel = Regex("""(?:实付金额|实际支付|实付款|实付|付款金额|支付金额)\s*[：:]?\s*""")
    private val paid = Regex("""(?:实付金额|实际支付|实付款|实付|付款金额|支付金额)\s*[：:]?\s*(?:[¥￥]\s*($number)|($number)\s*元)""")

    fun parse(pkg: String, title: String, body: String): ParsedPayment? = inspect(pkg,title,body).payment

    fun inspect(pkg: String, title: String, body: String): PaymentParseResult {
        val source=sources[pkg] ?: return PaymentParseResult(reason="来源未支持")
        if(title.trim() !in titles) return PaymentParseResult(reason="不是支持的支付服务标题")
        if(body.length>6000) return PaymentParseResult(reason="内容过长，已跳过")
        if(body.isBlank() || Regex("敏感.*隐藏|内容.*隐藏|解锁.*查看|sensitive.*hidden",RegexOption.IGNORE_CASE).containsMatchIn(body))
            return PaymentParseResult(reason="内容为空或已隐藏")
        val text=title + " " + body
        if(excluded.containsMatchIn(text)) return PaymentParseResult(reason="退款、转账、收款或非完成交易")
        if(!success.containsMatchIn(text) && !(pkg=="com.tencent.mm" && title.trim()=="微信支付" && compactPaid.containsMatchIn(body))) return PaymentParseResult(reason="未找到明确付款成功")
        val labels=paidLabel.findAll(body).count()
        if(labels==0 && Regex("优惠|折扣|抵扣|订单金额|订单总额|原价|余额|积分").containsMatchIn(body))
            return PaymentParseResult(reason="只有优惠或订单信息，实付金额不明确")
        val matches=(if(labels>0) paid else money).findAll(body).toList()
        // An invalid explicitly labelled amount must never fall back to an order total.
        if(matches.size!=1 || (labels>0 && labels!=1)) return PaymentParseResult(reason="付款金额不唯一或格式不支持")
        val amount=matches.single().groupValues.let {it[1].ifEmpty {it[2]}}
        val cents=RecordRules.cents(amount) ?: return PaymentParseResult(reason="付款金额无效")
        val merchant=Regex("""(?:商户名称|商家名称|收款方|商户|商家)\s*[：:]\s*([^\n，,。]+)""")
            .find(body)?.groupValues?.get(1)?.trim()?.take(60) ?: "待补充商户"
        val transaction=Regex("""(?:交易单号|交易号)\s*[：:]\s*([A-Za-z0-9_-]{8,80})(?![A-Za-z0-9_-])""")
            .find(body)?.groupValues?.get(1)
        return PaymentParseResult(ParsedPayment(cents,merchant,source,transaction),"识别成功")
    }

    fun fingerprint(pkg: String, key: String, postedAt: Long, payment: ParsedPayment): String {
        val identity=payment.transactionId?.let {"$pkg|transaction|$it"}
            ?: "$pkg|$key|$postedAt|${payment.cents}|${payment.merchant}"
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
