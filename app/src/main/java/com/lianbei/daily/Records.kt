package com.lianbei.daily

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.math.BigDecimal
import java.time.LocalTime
import java.time.Duration
import android.content.Context
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

enum class Kind(val label: String, val hint: String) {
    FOOD("饮食", "吃了什么"), SLEEP("睡眠", "昨晚睡得怎样"), TRAINING("训练", "记录一次活动"),
    EXPENSE("消费", "花在哪里"), STATE("今天状态", "此刻的心情与精力"), NOTE("随手记", "想到就记下来")
}

@Entity(tableName = "records")
data class DailyRecord(
    @PrimaryKey val id: String,
    val kind: String,
    val date: String,
    val time: String,
    val title: String,
    val detail: String = "",
    val value: Long? = null,
    val extra: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "categories")
data class CustomCategory(@PrimaryKey val name: String)

@Entity(tableName = "payments")
data class PendingPayment(@PrimaryKey val id: String, val cents: Long, val merchant: String, val source: String, val date: String, val time: String, val status: String = "pending", @ColumnInfo(defaultValue = "'其他'") val category: String = "其他")

@Dao
interface RecordDao {
    @Query("SELECT * FROM categories") suspend fun allCategories():List<CustomCategory>
    @Query("SELECT * FROM payments") suspend fun allPayments():List<PendingPayment>
    @Query("SELECT * FROM payment_identities") suspend fun allIdentities():List<PaymentIdentity>
    @Transaction suspend fun backupSnapshot():BackupData = BackupData(4, allRecords(), allCategories(), allSubscriptions(),
        allSubscriptionPayments(), allFoodEntries().map { BackupFood(it.foodId,it.expenseId,it.ownsExpense,photoPath=it.photoPath) }, allPayments(), allIdentities())
    @Transaction suspend fun mergeBackup(data:BackupData):RestoreResult = Backup.merge(this,data)
    @Query("UPDATE payments SET cents=:cents,merchant=:merchant,date=:date,time=:time,category=:category WHERE id=:id AND status='pending'")
    suspend fun editPaymentRow(id:String,cents:Long,merchant:String,date:String,time:String,category:String):Int
    @Transaction suspend fun updatePending(p:PendingPayment):Boolean {
        require(p.cents>0) { "金额需大于零" }
        require(p.merchant.isNotBlank() && p.merchant.length<=200) { "请填写商户名称（最多200字）" }
        require(java.time.LocalDate.parse(p.date)<=java.time.LocalDate.now()) { "日期不能晚于今天" }
        java.time.LocalTime.parse(p.time)
        val parent=p.category.substringBefore(" / ")
        val child=p.category.substringAfter(" / ","")
        require(parent in ExpenseCategories.groups && (child.isEmpty() || child in ExpenseCategories.groups.getValue(parent))) { "请选择有效分类" }
        return editPaymentRow(p.id,p.cents,p.merchant.trim(),p.date,p.time,p.category)==1
    }
    @Transaction suspend fun confirmMany(ids:List<String>):Int {
        var count=0
        ids.distinct().forEach { id -> payment(id)?.takeIf { it.status=="pending" }?.let { confirm(id,it.category);count++ } }
        return count
    }
    @Transaction suspend fun ignoreMany(ids:List<String>):Int {
        var count=0
        ids.distinct().forEach { count+=resolve(it,"ignored") }
        return count
    }
    @Query("SELECT * FROM records") suspend fun allRecords():List<DailyRecord>
    @Query("SELECT * FROM food_entries") suspend fun allFoodEntries():List<FoodEntry>
    @Query("SELECT * FROM subscriptions") suspend fun allSubscriptions():List<Subscription>
    @Query("SELECT * FROM subscription_payments") suspend fun allSubscriptionPayments():List<SubscriptionPayment>
    @Transaction suspend fun addDemo(today:String) {DemoData.populate(this,java.time.LocalDate.parse(today))}
    @Transaction suspend fun clearDemo() {DemoData.remove(this)}
    @Query("SELECT * FROM food_entries") fun foodEntries():Flow<List<FoodEntry>>
    @Query("SELECT * FROM food_entries WHERE foodId = :id") suspend fun foodEntry(id:String):FoodEntry?
    @Query("SELECT * FROM food_entries WHERE expenseId = :id") suspend fun foodForExpense(id:String):FoodEntry?
    @Query("SELECT COUNT(*) FROM subscription_payments WHERE recordId = :id") suspend fun subscriptionUsesExpense(id:String):Int
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putFoodEntry(entry:FoodEntry)
    @Query("DELETE FROM food_entries WHERE foodId = :id") suspend fun removeFoodEntry(id:String)
    @Query("UPDATE food_entries SET expenseId = NULL, ownsExpense = 0 WHERE expenseId = :id") suspend fun unlinkFoodExpense(id:String)
    @Transaction suspend fun saveFood(food:DailyRecord,photoPath:String,cents:Long?,existingId:String?) {
        require(food.kind=="FOOD" && food.title.isNotBlank())
        require(cents==null || cents>0) { "价格需大于零" }
        val previous=foodEntry(food.id)
        val expenseId:String?
        val owned:Boolean
        if(existingId!=null) {
            require(record(existingId)?.kind=="EXPENSE") { "所选消费已不存在，请重新选择" }
            require(foodForExpense(existingId)?.foodId.let {it==null || it==food.id} && subscriptionUsesExpense(existingId)==0) { "这笔消费已关联其他记录" }
            if(previous?.ownsExpense==true && previous.expenseId!=existingId) previous.expenseId?.let {record(it)?.let {r->delete(r)}}
            expenseId=existingId;owned=previous?.expenseId==existingId && previous.ownsExpense
        } else if(cents!=null) {
            // Reuse only the still-owned expense, never overwrite a detached payment.
            expenseId=previous?.takeIf {it.ownsExpense}?.expenseId?.takeIf {record(it)!=null}
                ?: "food-expense-${food.id}-${java.util.UUID.randomUUID()}"
            owned=true
            save(DailyRecord(expenseId,"EXPENSE",food.date,food.time,"食品酒水","${food.title} · ${food.detail.ifBlank {"饮食记录"}}",cents,extra=ExpenseCategories.foodMeal(food.title),createdAt=record(expenseId)?.createdAt ?: food.createdAt))
        } else {expenseId=null;owned=false}
        save(food.copy(value=null))
        putFoodEntry(FoodEntry(food.id,photoPath,expenseId,owned))
    }
    @Query("SELECT * FROM subscriptions ORDER BY name") fun subscriptions(): Flow<List<Subscription>>
    @Query("SELECT * FROM subscriptions WHERE id = :id") suspend fun subscription(id:String): Subscription?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun saveSubscription(s:Subscription)
    @Query("DELETE FROM subscriptions WHERE id = :id") suspend fun deleteSubscription(id:String)
    @Query("SELECT * FROM subscription_payments") fun subscriptionPayments(): Flow<List<SubscriptionPayment>>
    @Query("SELECT * FROM subscription_payments WHERE subscriptionId = :id AND dueDate = :due") suspend fun subscriptionPayment(id:String,due:String):SubscriptionPayment?
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun addSubscriptionPayment(p:SubscriptionPayment)
    @Query("DELETE FROM subscription_payments WHERE subscriptionId = :id AND dueDate = :due") suspend fun removeSubscriptionPayment(id:String,due:String)
    @Query("SELECT * FROM records WHERE id = :id") suspend fun record(id:String):DailyRecord?
    @Transaction suspend fun confirmSubscription(id:String,due:String,cents:Long,date:String,existingId:String?=null) {
        val prior=subscriptionPayment(id,due)
        if(prior!=null && record(prior.recordId)!=null) return
        if(prior!=null) removeSubscriptionPayment(id,due)
        val s=checkNotNull(subscription(id)) { "订阅已删除" }
        val day=java.time.LocalDate.parse(date)
        require(day<=java.time.LocalDate.now() && cents>0) { "请检查付款日期和金额" }
        val dueDay=java.time.LocalDate.parse(due)
        require(SubscriptionRules.dates(s.copy(active=true),dueDay,dueDay).isNotEmpty()) { "扣费日期不属于当前周期" }
        val recordId=existingId ?: "subscription-$id-$due"
        if(existingId!=null) require(record(existingId)?.kind==Kind.EXPENSE.name && foodForExpense(existingId)==null) { "消费记录不存在或已关联饮食" }
        addSubscriptionPayment(SubscriptionPayment(id,due,recordId))
        if(existingId==null) save(DailyRecord(recordId,"EXPENSE",date,java.time.LocalTime.now().toString().take(5),s.category,"${s.name} · 订阅付款（计划 $due）",cents))
    }
    @Query("SELECT paymentId FROM payment_identities WHERE identity = :identity") suspend fun identityPayment(identity:String):String?
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun addIdentity(identity:PaymentIdentity)
    @Query("UPDATE payments SET merchant = :merchant WHERE id = :id AND status = 'pending' AND merchant = '待补充商户'") suspend fun enrichPayment(id:String,merchant:String)
    @Transaction suspend fun capturePayment(pending:PendingPayment,identities:List<String>,legacyIds:List<String>):Boolean {
        val existing=identities.firstNotNullOfOrNull {identityPayment(it)}
            ?: legacyIds.firstOrNull {payment(it)!=null}
        val target=existing ?: pending.id
        val inserted=existing==null && addPayment(pending)!=-1L
        identities.forEach {addIdentity(PaymentIdentity(it,target))}
        if(existing!=null && pending.merchant!="待补充商户") enrichPayment(existing,pending.merchant)
        return inserted
    }
    @Query("SELECT * FROM payments WHERE status = 'pending' ORDER BY date DESC, time DESC") fun pending(): Flow<List<PendingPayment>>
    @Query("SELECT * FROM payments WHERE id = :id") suspend fun payment(id: String): PendingPayment?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addPayment(payment: PendingPayment): Long
    @Query("UPDATE payments SET status = :status WHERE id = :id AND status = 'pending'") suspend fun resolve(id: String, status: String): Int
    @Query("DELETE FROM payments WHERE id = :id") suspend fun deletePending(id: String)
    @Transaction suspend fun confirm(id: String, category: String = "") {
        val p = payment(id) ?: return
        if (resolve(id, "confirmed") == 1) {
            val selected=category.ifBlank { p.category }
            val main=ExpenseCategories.main(selected.substringBefore(" / "))
            val child=selected.substringAfter(" / ","").takeIf {it in ExpenseCategories.groups[main].orEmpty()}.orEmpty()
            save(DailyRecord("payment-$id", "EXPENSE", p.date, p.time, main, "${p.merchant} · ${p.source}通知识别", p.cents,extra=child))
        }
    }
    @Query("SELECT * FROM records ORDER BY date DESC, time DESC, createdAt DESC")
    fun observeAll(): Flow<List<DailyRecord>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(record: DailyRecord)
    @Delete suspend fun deleteRow(record: DailyRecord)
    @Transaction suspend fun delete(record:DailyRecord) {
        if(record.kind=="FOOD") removeFoodEntry(record.id)
        if(record.kind=="EXPENSE") unlinkFoodExpense(record.id)
        deleteRow(record)
    }
    @Query("SELECT * FROM categories ORDER BY name") fun categories(): Flow<List<CustomCategory>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addCategory(category: CustomCategory)
}

@Database(entities = [DailyRecord::class, CustomCategory::class, PendingPayment::class, Subscription::class, SubscriptionPayment::class, FoodEntry::class, PaymentIdentity::class], version = 6, exportSchema = false)
abstract class DailyDatabase : RoomDatabase() {
    abstract fun records(): RecordDao
    companion object {
        val MIGRATION_5_6 = object : Migration(5,6) {
            override fun migrate(db:SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE payments ADD COLUMN category TEXT NOT NULL DEFAULT '其他'")
            }
        }
        val MIGRATION_4_5 = object : Migration(4,5) {
            override fun migrate(db:SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS payment_identities (identity TEXT NOT NULL PRIMARY KEY, paymentId TEXT NOT NULL)")
            }
        }
        val MIGRATION_3_4 = object : Migration(3,4) {
            override fun migrate(db:SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS food_entries (foodId TEXT NOT NULL PRIMARY KEY, photoPath TEXT NOT NULL, expenseId TEXT, ownsExpense INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_food_entries_expenseId ON food_entries (expenseId)")
            }
        }
        val MIGRATION_2_3 = object : Migration(2,3) {
            override fun migrate(db:SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS subscriptions (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, cents INTEGER NOT NULL, cycle TEXT NOT NULL, startDate TEXT NOT NULL, category TEXT NOT NULL, note TEXT NOT NULL, paymentUrl TEXT NOT NULL, color INTEGER NOT NULL, active INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS subscription_payments (subscriptionId TEXT NOT NULL, dueDate TEXT NOT NULL, recordId TEXT NOT NULL, PRIMARY KEY(subscriptionId,dueDate))")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_subscription_payments_recordId ON subscription_payments (recordId)")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS payments (id TEXT NOT NULL PRIMARY KEY, cents INTEGER NOT NULL, merchant TEXT NOT NULL, source TEXT NOT NULL, date TEXT NOT NULL, time TEXT NOT NULL, status TEXT NOT NULL)")
            }
        }
        @Volatile private var instance: DailyDatabase? = null
        fun get(context: Context): DailyDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, DailyDatabase::class.java, "daily.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build().also { instance = it }
        }
    }
}

object RecordRules {
    fun cents(text: String): Long? = try {
        BigDecimal(text.trim()).takeIf { it.signum() > 0 && it.scale() <= 2 }
            ?.movePointRight(2)?.longValueExact()
    } catch (_: Exception) { null }

    fun sleepMinutes(start: String, end: String): Long? = try {
        val minutes = Duration.between(LocalTime.parse(start), LocalTime.parse(end)).toMinutes()
        (if (minutes < 0) minutes + 1440 else minutes).takeIf { it > 0 }
    } catch (_: Exception) { null }

    fun money(cents: Long): String = BigDecimal.valueOf(cents, 2).toPlainString()
}
