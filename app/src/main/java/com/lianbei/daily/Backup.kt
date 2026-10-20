package com.lianbei.daily

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.graphics.BitmapFactory
import java.io.File
import java.io.InputStream
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class BackupFood(val foodId:String,val expenseId:String?,val ownsExpense:Boolean,val photoBase64:String="",val photoPath:String="")
data class BackupData(val version:Int,val records:List<DailyRecord>,val categories:List<CustomCategory>,
    val subscriptions:List<Subscription>,val subscriptionPayments:List<SubscriptionPayment>,val foodEntries:List<BackupFood>,
    val payments:List<PendingPayment>,val identities:List<PaymentIdentity>)
data class RestoreResult(val added:Int,val skipped:Int,val conflicts:Int)

object Backup {
    const val MAX_BYTES = 64 * 1024 * 1024
    private const val MAX_PHOTO = 8 * 1024 * 1024

    fun read(input:InputStream):String {
        val output=java.io.ByteArrayOutputStream()
        val buffer=ByteArray(8192)
        while(true) {
            val n=input.read(buffer);if(n<0)break
            require(output.size().toLong()+n<=MAX_BYTES) { "备份文件超过64MB，请分批保留照片后重试" }
            output.write(buffer,0,n)
        }
        return output.toString("UTF-8")
    }

    suspend fun export(context:Context,dao:RecordDao):String = FoodPhotos.withLifecycleLock {
        val data=dao.backupSnapshot()
        val json=JSONObject().put("version",4)
        fun array(key:String,rows:List<JSONObject>) { json.put(key,JSONArray(rows)) }
        array("records",data.records.map { r -> obj("id" to r.id,"kind" to r.kind,"date" to r.date,"time" to r.time,"title" to r.title,
            "detail" to r.detail,"value" to r.value,"extra" to r.extra,"createdAt" to r.createdAt) })
        json.put("categories",JSONArray(data.categories.map { it.name }))
        array("subscriptions",data.subscriptions.map { s -> obj("id" to s.id,"name" to s.name,"cents" to s.cents,"cycle" to s.cycle,
            "startDate" to s.startDate,"category" to s.category,"note" to s.note,"paymentUrl" to s.paymentUrl,"color" to s.color,"active" to s.active) })
        array("subscriptionPayments",data.subscriptionPayments.map { obj("subscriptionId" to it.subscriptionId,"dueDate" to it.dueDate,"recordId" to it.recordId) })
        var photoBytes=0L
        array("foodEntries",data.foodEntries.map { f ->
            val photoFile=if(f.photoPath.isBlank())null else requireNotNull(FoodPhotos.file(context,f.photoPath)) { "照片文件缺失，无法完成备份" }
            if(photoFile!=null) {
                require(photoFile.length()<=MAX_PHOTO) { "单张照片超过8MB，无法导出可恢复的备份" }
                photoBytes+=((photoFile.length()+2)/3)*4
                require(photoBytes<MAX_BYTES) { "照片总量超过备份大小限制" }
            }
            obj("foodId" to f.foodId,"expenseId" to f.expenseId,"ownsExpense" to f.ownsExpense,"photoBase64" to FoodPhotos.export(context,f.photoPath))
        })
        array("payments",data.payments.map { p -> obj("id" to p.id,"cents" to p.cents,"merchant" to p.merchant,"source" to p.source,
            "date" to p.date,"time" to p.time,"status" to p.status,"category" to p.category) })
        array("identities",data.identities.map { obj("identity" to it.identity,"paymentId" to it.paymentId) })
        val text=json.toString()
        require(text.toByteArray(Charsets.UTF_8).size<=MAX_BYTES) { "备份文件超过64MB" }
        text
    }

    fun parse(text:String):BackupData {
        require(text.toByteArray(Charsets.UTF_8).size<=MAX_BYTES) { "备份文件超过64MB" }
        val root=JSONObject(text.removePrefix("\uFEFF"))
        val rawVersion=integer(root,"version")
        require(rawVersion in 1L..4L) { "不支持此备份版本，请更新练呗后重试" }
        val version=rawVersion.toInt()
        fun objects(key:String,required:Boolean=false):List<JSONObject> {
            if(!root.has(key)) {require(!required) { "备份缺少$key" };return emptyList()}
            val array=root.getJSONArray(key)
            return (0 until array.length()).map { array.getJSONObject(it) }
        }
        val records=objects("records",true).map { r ->
            val kind=str(r,"kind");require(kind in Kind.entries.map { it.name }) { "备份包含未知记录类型" }
            val value=if(!r.has("value") || r.isNull("value")) null else integer(r,"value")
            require(value==null || value>0) { "备份金额或时长无效" }
            require(kind!="EXPENSE" || value!=null) { "消费记录缺少金额" }
            DailyRecord(str(r,"id"),kind,date(r,"date"),time(r,"time"),str(r,"title"),str(r,"detail",""),value,
                str(r,"extra",""),if(r.has("createdAt"))integer(r,"createdAt") else 0)
        }
        val categories=if(!root.has("categories")) emptyList() else root.getJSONArray("categories").let { a ->
            (0 until a.length()).map {
                val name=a.get(it)
                require(name is String && name.isNotBlank()) { "类别名称无效" }
                CustomCategory(name)
            }
        }
        val subscriptions=objects("subscriptions").map { s ->
            val cycle=str(s,"cycle");require(cycle in SubscriptionRules.cycles) { "订阅周期无效" }
            val cents=integer(s,"cents");require(cents>0) { "订阅金额无效" }
            val link=str(s,"paymentUrl","");require(SubscriptionRules.validLink(link)) { "缴费链接无效" }
            val color=if(s.has("color"))integer(s,"color") else 0L
            require(color in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "订阅颜色无效" }
            Subscription(str(s,"id"),str(s,"name"),cents,cycle,date(s,"startDate"),str(s,"category","其他"),
                str(s,"note",""),link,color.toInt(),boolean(s,"active",true))
        }
        val subscriptionPayments=objects("subscriptionPayments").map { SubscriptionPayment(str(it,"subscriptionId"),date(it,"dueDate"),str(it,"recordId")) }
        val foods=objects("foodEntries").map { f ->
            val photo=str(f,"photoBase64","",12*1024*1024)
            require(photo.length<=((MAX_PHOTO+2)/3)*4) { "单张照片超过8MB" }
            BackupFood(str(f,"foodId"),if(f.isNull("expenseId") || !f.has("expenseId"))null else str(f,"expenseId"),
                boolean(f,"ownsExpense",false),photo)
        }
        val payments=objects("payments").map { p ->
            val cents=integer(p,"cents");require(cents>0) { "待确认金额无效" }
            val status=str(p,"status","pending");require(status in listOf("pending","confirmed","ignored")) { "支付状态无效" }
            val category=str(p,"category","其他")
            require(validCategory(category)) { "支付分类无效" }
            PendingPayment(str(p,"id"),cents,str(p,"merchant"),str(p,"source"),date(p,"date"),time(p,"time"),status,category)
        }
        val identities=objects("identities").map { PaymentIdentity(str(it,"identity"),str(it,"paymentId")) }
        unique(records.map{it.id});unique(categories.map{it.name});unique(subscriptions.map{it.id});unique(foods.map{it.foodId})
        unique(payments.map{it.id});unique(identities.map{it.identity});unique(subscriptionPayments.map{it.subscriptionId+"\n"+it.dueDate})
        unique(foods.mapNotNull{it.expenseId});unique(subscriptionPayments.map{it.recordId})
        val recordMap=records.associateBy{it.id}
        foods.forEach { f ->
            require(recordMap[f.foodId]?.kind=="FOOD") { "饮食照片缺少对应记录" }
            require(f.expenseId==null || recordMap[f.expenseId]?.kind=="EXPENSE") { "饮食关联的消费不存在" }
            require(!f.ownsExpense || f.expenseId!=null) { "饮食价格关联无效" }
        }
        // Historical subscription links may outlive a deleted subscription or expense by design.
        subscriptionPayments.forEach { require(recordMap[it.recordId]?.kind.let { kind -> kind==null || kind=="EXPENSE" }) { "订阅付款关联类型无效" } }
        require(foods.mapNotNull{it.expenseId}.intersect(subscriptionPayments.map{it.recordId}.toSet()).isEmpty()) { "一笔消费关联了多个用途" }
        val paymentIds=payments.map{it.id}.toSet()
        identities.forEach { require(it.paymentId in paymentIds) { "支付去重身份缺少对应支付" } }
        return BackupData(version,records,categories,subscriptions,subscriptionPayments,foods,payments,identities)
    }

    suspend fun restore(context:Context,dao:RecordDao,data:BackupData):RestoreResult = FoodPhotos.withLifecycleLock {
        val created=mutableListOf<File>()
        try {
            val foods=data.foodEntries.map { f ->
                // Validate every picture, including records which will preserve the local copy.
                if(f.photoBase64.isEmpty()) f.copy(photoPath="")
                else {
                    require(f.photoBase64.matches(Regex("[A-Za-z0-9+/]*={0,2}"))) { "照片编码损坏" }
                    val bytes=Base64.decode(f.photoBase64,Base64.NO_WRAP)
                    require(bytes.size<=MAX_PHOTO) { "照片超过8MB" }
                    val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                    BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                    require(bounds.outWidth>0 && bounds.outHeight>0 && bounds.outWidth.toLong()*bounds.outHeight<=40_000_000) { "照片损坏或尺寸过大" }
                    val temporary=File.createTempFile("backup-photo-",".img",context.cacheDir)
                    try {
                        temporary.writeBytes(bytes)
                        val draft=FoodPhotos.import(context,Uri.fromFile(temporary))
                        try {
                            if(dao.record(f.foodId)!=null) f.copy(photoPath="")
                            else {
                                val path=FoodPhotos.commit(context,draft)
                                created.add(File(path))
                                f.copy(photoPath=path)
                            }
                        } finally { FoodPhotos.discardDraft(context,draft) }
                    } finally { temporary.delete() }
                }
            }
            val result=dao.mergeBackup(data.copy(foodEntries=foods))
            val referenced=dao.allFoodEntries().map{it.photoPath}.toSet()
            created.filter{it.path !in referenced}.forEach{it.delete()}
            result
        } catch(e:Exception) {
            // Room rolls back on failure. Protect any file whose transaction already committed
            // (e.g. cancellation immediately after Room returns) before removing staged copies.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                val referenced=runCatching { dao.allFoodEntries().map{it.photoPath}.toSet() }.getOrNull()
                if(referenced!=null)created.filter{it.path !in referenced}.forEach{it.delete()}
            }
            throw e
        }
    }

    /** Called only through RecordDao.mergeBackup's Room transaction. */
    internal suspend fun merge(dao:RecordDao,data:BackupData):RestoreResult {
        var added=0;var skipped=0;var conflicts=0
        // Resolve business identities before inserting financial records. Row IDs alone
        // cannot identify the same payment captured/restored independently on two devices.
        val aliasesByPayment=data.identities.groupBy{it.paymentId}
        val canonicalPayments=mutableMapOf<String,String?>()
        val canonicalExpenses=mutableMapOf<String,String>()
        val blockedRecords=mutableSetOf<String>()
        for(p in data.payments) {
            val local=dao.payment(p.id)
            val owners=aliasesByPayment[p.id].orEmpty().mapNotNull{dao.identityPayment(it.identity)}.toMutableSet()
            if(local!=null)owners.add(local.id)
            val owner=owners.singleOrNull()
            val canonical=when {
                owners.size>1 -> null
                owner!=null -> owner.takeIf{dao.payment(it)!=null}
                p.status=="pending" && dao.record("payment-${p.id}")!=null -> null
                else -> p.id
            }
            canonicalPayments[p.id]=canonical
            if(canonical!=null && canonical!=p.id && dao.record("payment-$canonical")?.kind=="EXPENSE")
                canonicalExpenses["payment-${p.id}"]="payment-$canonical"
            if(canonical!=p.id || (local!=null && local.status!="confirmed"))
                blockedRecords.add("payment-${p.id}")
        }
        // v1–3 did not export payment rows, but their expense IDs still identify local decisions.
        for(r in data.records)if(r.kind=="EXPENSE" && r.id.startsWith("payment-")) {
            val local=dao.payment(r.id.removePrefix("payment-"))
            if(local!=null && local.status!="confirmed")blockedRecords.add(r.id)
        }
        val blockedPeriods=mutableSetOf<Pair<String,String>>()
        for(p in data.subscriptionPayments) {
            val local=dao.subscriptionPayment(p.subscriptionId,p.dueDate)
            if(local!=null && local.recordId!=p.recordId)blockedRecords.add(p.recordId)
            val subscription=dao.subscription(p.subscriptionId)
            val day=LocalDate.parse(p.dueDate)
            if(subscription!=null && SubscriptionRules.dates(subscription.copy(active=true),day,day).isEmpty()) {
                blockedPeriods.add(p.subscriptionId to p.dueDate)
                blockedRecords.add(p.recordId)
            }
        }
        val newRecords=mutableSetOf<String>()
        val compatibleRecords=mutableSetOf<String>()
        for(r in data.records) {
            if(r.id in blockedRecords){skipped++;conflicts++;continue}
            val local=dao.record(r.id)
            if(local==null) {dao.save(r);newRecords.add(r.id);compatibleRecords.add(r.id);added++}
            else { skipped++;if(local!=r)conflicts++ else compatibleRecords.add(r.id) }
        }
        val categories=dao.allCategories().map{it.name}.toMutableSet()
        for(c in data.categories)if(categories.add(c.name)){dao.addCategory(c);added++}else skipped++
        for(s in data.subscriptions) {
            val local=dao.subscription(s.id)
            if(local==null){dao.saveSubscription(s);added++}
            else {skipped++;if(local!=s)conflicts++}
        }
        for(f in data.foodEntries) {
            if(f.foodId !in newRecords || dao.foodEntry(f.foodId)!=null){skipped++;continue}
            val candidate=canonicalExpenses[f.expenseId] ?: f.expenseId
            val usable=candidate?.takeIf{(f.expenseId in canonicalExpenses || it in compatibleRecords) &&
                dao.foodForExpense(it)==null && dao.subscriptionUsesExpense(it)==0}
            if(usable!=f.expenseId)conflicts++
            dao.putFoodEntry(FoodEntry(f.foodId,f.photoPath,usable,f.ownsExpense && usable!=null && usable==f.expenseId));added++
        }
        val usedSubscriptionExpenses=dao.allSubscriptionPayments().map{it.recordId}.toMutableSet()
        for(p in data.subscriptionPayments) {
            if(dao.subscriptionPayment(p.subscriptionId,p.dueDate)!=null){skipped++;continue}
            val recordId=canonicalExpenses[p.recordId] ?: p.recordId
            if((p.recordId in blockedRecords && p.recordId !in canonicalExpenses) ||
                (p.subscriptionId to p.dueDate) in blockedPeriods){skipped++;conflicts++;continue}
            val hasLocalRecord=dao.record(recordId)!=null
            if((hasLocalRecord && recordId !in compatibleRecords && p.recordId !in canonicalExpenses) ||
                recordId in usedSubscriptionExpenses || dao.foodForExpense(recordId)!=null) {
                conflicts++;skipped++;continue
            }
            dao.addSubscriptionPayment(p.copy(recordId=recordId));usedSubscriptionExpenses.add(recordId);added++
        }
        for(p in data.payments) {
            val canonical=canonicalPayments[p.id]
            if(canonical!=p.id){skipped++;conflicts++;continue}
            val local=dao.payment(p.id)
            if(local==null) {
                if("payment-${p.id}" in blockedRecords) {
                    canonicalPayments[p.id]=null;conflicts++;skipped++;continue
                }
                dao.addPayment(p);added++
            } else {
                skipped++
                if(local!=p)conflicts++
            }
        }
        for(i in data.identities) {
            val canonical=canonicalPayments[i.paymentId]
            val owner=dao.identityPayment(i.identity)
            if(owner!=null){skipped++;if(owner!=canonical)conflicts++}
            else if(canonical!=null){dao.addIdentity(i.copy(paymentId=canonical));added++}
            else {skipped++;conflicts++}
        }
        return RestoreResult(added,skipped,conflicts)
    }

    private fun obj(vararg fields:Pair<String,Any?>)=JSONObject().apply { fields.forEach{put(it.first,it.second ?: JSONObject.NULL)} }
    private fun str(row:JSONObject,key:String,default:String?=null,max:Int=MAX_BYTES):String {
        val value=if(!row.has(key))requireNotNull(default){"备份缺少$key"}else row.get(key).let{require(it is String){"备份${key}类型无效"};it}
        require(value.length<=max && (default!=null || value.isNotBlank())) { "备份${key}内容无效" }
        return value
    }
    private fun integer(row:JSONObject,key:String):Long {
        val value=row.get(key)
        require(value is Int || value is Long) { "备份${key}不是整数" }
        return (value as Number).toLong()
    }
    private fun boolean(row:JSONObject,key:String,default:Boolean):Boolean {
        if(!row.has(key))return default
        val value=row.get(key)
        require(value is Boolean) { "备份${key}不是布尔值" }
        return value
    }
    private fun date(row:JSONObject,key:String)=str(row,key).also { LocalDate.parse(it) }
    private fun time(row:JSONObject,key:String)=str(row,key).also { LocalTime.parse(it) }
    private fun unique(values:List<String>) {require(values.size==values.toSet().size) { "备份存在重复标识或重复关联" }}
    private fun validCategory(category:String):Boolean {
        val parent=category.substringBefore(" / ");val child=category.substringAfter(" / ","")
        return parent in ExpenseCategories.groups && (child.isBlank() || child in ExpenseCategories.groups.getValue(parent))
    }
}
